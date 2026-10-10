package com.example.myai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Supplier;

@Service
public class SqlAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(SqlAnalysisService.class);

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("(?s)\\{.*}");
    private static final Pattern TABLE_REF_PATTERN = Pattern.compile("(?i)\\b(?:from|join)\\s+([\\[\\]A-Za-z0-9_.]+)");
    private static final Pattern CTE_NAME_PATTERN = Pattern.compile("(?i)(?:\\bwith\\b|,)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+as\\s*\\(");
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})[-/\\u5E74](\\d{1,2})[-/\\u6708](\\d{1,2})(?:\\u65E5)?");
    private static final Pattern MONTH_PATTERN = Pattern.compile("(\\d{4})[-/\\u5E74](\\d{1,2})(?:\\u6708)?");

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate", "merge",
            "grant", "revoke", "exec", "execute", "xp_", "sp_", "backup", "restore"
    );

    private static final String[] FOLLOW_UP_HINTS = new String[]{
            "that day", "that month", "then", "above", "previous",
            "\u90A3\u5929", "\u5F53\u5929", "\u4E0A\u9762", "\u521A\u624D", "\u7EE7\u7EED", "\u7136\u540E",
            "\u90A3\u4E2A\u6708", "\u5F53\u6708", "\u8FD9\u4E2A\u6708", "\u4E0A\u4E2A\u6708"
    };

    private static final String[] DATE_WORD_HINTS = new String[]{
            "today", "yesterday", "this month", "last month", "this year",
            "\u4ECA\u5929", "\u6628\u5929", "\u672C\u6708", "\u4E0A\u6708", "\u4ECA\u5E74"
    };

    private static final String[] ANALYSIS_INTENT_HINTS = new String[]{
            "sales", "outbound", "amount", "count", "trend", "compare",
            "\u9500\u552E", "\u51FA\u5E93", "\u91D1\u989D", "\u6570\u91CF", "\u8D8B\u52BF", "\u5BF9\u6BD4"
    };
    private static final String DEFAULT_SQLSERVER_DRIVER = "net.sourceforge.jtds.jdbc.Driver";
    private static final int DEFAULT_SQLSERVER_PORT = 1433;
    private static final ThreadLocal<ExecutionContext> EXECUTION_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<ModelConnectionConfig> MODEL_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<RunTrace> RUN_TRACE = new ThreadLocal<>();
    /** 当前登录用户，search_docs 只检索该用户上传的资料。 */
    private static final ThreadLocal<String> USER_CONTEXT = new ThreadLocal<>();

    @Value("${nl2sql.provider:deepseek}")
    private String provider;

    @Value("${nl2sql.allowed-tables:dbo.inout,aisql.dbo.inout,inout}")
    private String allowedTablesConfig;

    @Value("${analysis.agent.max-query-rows:${nl2sql.max-rows:200}}")
    private int maxQueryRows;

    @Value("${analysis.agent.max-steps:4}")
    private int maxAgentSteps;

    @Value("${analysis.max-preview-rows:30}")
    private int analysisMaxPreviewRows;

    @Value("${analysis.max-cell-length:120}")
    private int analysisMaxCellLength;

    @Value("${analysis.custom-db-enabled:false}")
    private boolean customDbEnabled;

    @Value("${analysis.agent.request-timeout-seconds:${llm.request-timeout-seconds:45}}")
    private long requestTimeoutSeconds;

    @Value("${llm.api-key:${deepseek.api-key:}}")
    private String deepseekApiKey;

    @Value("${llm.base-url:${deepseek.base-url:https://api.deepseek.com}}")
    private String deepseekBaseUrl;

    @Value("${llm.model:${deepseek.model:deepseek-chat}}")
    private String deepseekModel;

    /** json：模型输出 JSON 文本动作（原实现）；tools：原生 Function Calling。 */
    @Value("${analysis.agent.mode:json}")
    private String agentMode = "json";

    @Value("${analysis.agent.max-tool-turns:8}")
    private int maxToolTurns = 8;

    private final JdbcTemplate jdbcTemplate;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AgentChatModelFactory chatModelFactory;
    private final com.example.myai.rag.KnowledgeIndexService knowledgeIndexService;
    private final Map<String, CachedTableDescription> tableDescriptionCache = new java.util.concurrent.ConcurrentHashMap<>();

    public SqlAnalysisService(
            JdbcTemplate jdbcTemplate,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper
    ) {
        this(jdbcTemplate, webClientBuilder, objectMapper, new AgentChatModelFactory(), null);
    }

    public SqlAnalysisService(
            JdbcTemplate jdbcTemplate,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            AgentChatModelFactory chatModelFactory
    ) {
        this(jdbcTemplate, webClientBuilder, objectMapper, chatModelFactory, null);
    }

    @Autowired
    public SqlAnalysisService(
            JdbcTemplate jdbcTemplate,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            AgentChatModelFactory chatModelFactory,
            com.example.myai.rag.KnowledgeIndexService knowledgeIndexService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.chatModelFactory = chatModelFactory;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    public AnalysisResult analyze(String rawQuery) {
        return analyze(rawQuery, List.of());
    }

    public AnalysisResult analyze(String rawQuery, List<HistoryTurn> history) {
        return analyze(rawQuery, history, null);
    }

    public AnalysisResult analyze(String rawQuery, List<HistoryTurn> history, DbConnectionConfig dbConfig) {
        ExecutionContext context = resolveExecutionContext(dbConfig);
        return withExecutionContext(context, () -> {
            String userQuery = normalizeQuery(rawQuery);
            List<HistoryTurn> safeHistory = normalizeHistory(history);
            String effectiveQuery = applyContextHints(userQuery, safeHistory);

            AgentRunResult runResult = runConfiguredAgent(null, userQuery, effectiveQuery, safeHistory, null, null);
            String answer = mergeAnswerWithToolTail(runResult.answer(), runResult.toolResults());

            ToolResult last = runResult.evidenceOrLast();

            if (last == null) {
                return new AnalysisResult(answer, "-", 0, List.of());
            }
            return new AnalysisResult(answer, last.sqlPreview(), last.rowCount(), last.rows());
        });
    }

    /**
     * 与 analyze 相同的 Agent 流程，额外返回逐步轨迹、token 用量和耗时，供离线评测使用。
     * 运行失败时不抛异常，而是把错误写入结果，便于评测脚本统计。
     */
    public TracedAnalysisResult analyzeWithTrace(
            String rawQuery,
            List<HistoryTurn> history,
            ModelConnectionConfig modelConfig,
            String requestedMode,
            String username
    ) {
        RunTrace trace = new RunTrace();
        RUN_TRACE.set(trace);
        USER_CONTEXT.set(username);
        long started = System.nanoTime();
        try {
            ExecutionContext context = resolveExecutionContext(null);
            ModelConnectionConfig safeModelConfig = normalizeModelConfig(modelConfig);
            return withExecutionAndModelContext(context, safeModelConfig, () -> {
                String userQuery = normalizeQuery(rawQuery);
                List<HistoryTurn> safeHistory = normalizeHistory(history);
                String effectiveQuery = applyContextHints(userQuery, safeHistory);
                AgentRunResult runResult = runConfiguredAgent(requestedMode, userQuery, effectiveQuery, safeHistory, safeModelConfig, null);
                ToolResult last = runResult.evidenceOrLast();
                return trace.toResult(runResult.answer(), last, elapsedMs(started), null);
            });
        } catch (RuntimeException ex) {
            return trace.toResult("", null, elapsedMs(started), formatError(ex));
        } finally {
            RUN_TRACE.remove();
            USER_CONTEXT.remove();
        }
    }

    public Flux<String> analyzeStream(String rawQuery, List<HistoryTurn> history) {
        return analyzeStream(rawQuery, history, null);
    }

    public Flux<String> analyzeStream(String rawQuery, List<HistoryTurn> history, DbConnectionConfig dbConfig) {
        return analyzeStream(rawQuery, history, dbConfig, null);
    }

    public Flux<String> analyzeStream(
            String rawQuery,
            List<HistoryTurn> history,
            DbConnectionConfig dbConfig,
            ModelConnectionConfig modelConfig
    ) {
        return analyzeStream(rawQuery, history, dbConfig, modelConfig, null);
    }

    public Flux<String> analyzeStream(
            String rawQuery,
            List<HistoryTurn> history,
            DbConnectionConfig dbConfig,
            ModelConnectionConfig modelConfig,
            String username
    ) {
        return Flux.create(sink -> Schedulers.boundedElastic().schedule(() -> {
            USER_CONTEXT.set(username);
            try {
                ExecutionContext context = resolveExecutionContext(dbConfig);
                withExecutionAndModelContext(context, normalizeModelConfig(modelConfig), () -> {
                    String userQuery = normalizeQuery(rawQuery);
                    List<HistoryTurn> safeHistory = normalizeHistory(history);
                    String effectiveQuery = applyContextHints(userQuery, safeHistory);

                    sink.next(toStatusPayload("\u6B63\u5728\u7406\u89E3\u95EE\u9898..."));

                    AgentRunResult runResult = runConfiguredAgent(
                            null,
                            userQuery,
                            effectiveQuery,
                            safeHistory,
                            normalizeModelConfig(modelConfig),
                            message -> sink.next(toStatusPayload(message))
                    );

                    String answer = mergeAnswerWithToolTail(runResult.answer(), runResult.toolResults());
                    for (String chunk : splitTextToPayloadChunks(answer, 220)) {
                        sink.next(chunk);
                    }
                    return null;
                });
                sink.next(toStreamPayload("message_end", ""));
            } catch (Exception ex) {
                sink.next(toStreamPayload("text_generated", formatError(ex)));
                sink.next(toStreamPayload("message_end", ""));
            } finally {
                USER_CONTEXT.remove();
                sink.complete();
            }
        }));
    }

    private AgentRunResult runConfiguredAgent(
            String requestedMode,
            String userQuery,
            String effectiveQuery,
            List<HistoryTurn> history,
            ModelConnectionConfig modelConfig,
            StreamReporter reporter
    ) {
        String mode = requestedMode == null || requestedMode.isBlank() ? agentMode : requestedMode;
        if ("tools".equalsIgnoreCase(mode.trim())) {
            return runToolAgent(userQuery, history, reporter);
        }
        return runAgent(userQuery, effectiveQuery, history, modelConfig, reporter);
    }

    // ---------------------------------------------------------------------
    // 原生 Function Calling 模式
    // ---------------------------------------------------------------------

    private static final String TOOL_AGENT_SYSTEM_PROMPT = """
            你是仓储数据分析助手，连接的是 Microsoft SQL Server 数据库。通过工具查询数据后回答用户的问题。

            工作方式：
            1. 不确定有哪些表时调用 list_tables；写 SQL 前先用 describe_table 确认列名、字段口径和取值，不要猜。
            2. 用 run_sql 执行只读查询（单条 SELECT 或 WITH 语句）。可以先做探索、核对类查询（is_final_result=false），
               但给出结论前必须执行一条 is_final_result=true 的查询，它的结果要正好对应你的回答：
               问"哪一个最多"就只返回那一行，问前 N 名就返回 N 行，问总数就返回一个数，问清单就返回清单。
               用户会用这条 SQL 核对你的结论。
            3. run_sql 返回 error 时，根据错误信息修正 SQL 后重试，不要编造数据。
            4. 问题缺少关键条件、也无法合理推断时（例如没说是哪个商品、哪个客户），调用 ask_user 追问；
               能用合理默认值查询的问题直接查询。
            5. 问题涉及公司制度、标准或审批阈值（例如近效期怎么定义、安全库存是多少、什么情况需要复核）时，
               先用 search_docs 在业务资料里查到规则，再用 run_sql 按规则查数据，回答中注明依据的资料名称。
            6. 只能读取数据。用户要求修改、删除数据或查看系统表时，直接说明无法执行，不要调用 run_sql。
            7. 用中文回答，先给结论和关键数字，再补充必要说明，简洁直接。

            当前日期：%s（"今年"指 %s 年）。
            可查询的表：
            %s

            SQL Server 写法：
            - 取前几条用 TOP n，不支持 LIMIT。
            - 文本类型的日期列，比较或按月、季度统计前先 CAST(列 AS DATE)。
            - 中文字符串常量加 N 前缀，例如 N'销售出库'。
            - 字段说明中列出了取值的枚举列（如单据类型），按取值精确匹配，不要用 LIKE 模糊匹配，
              以免把含义不同的取值（例如销售出库和销售退货）混在一起。
            """;

    private AgentRunResult runToolAgent(String userQuery, List<HistoryTurn> history, StreamReporter reporter) {
        ResolvedModel resolved = resolveModel();
        if (resolved == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务未配置 API Key，请检查模型配置后重试");
        }
        ChatModel chatModel = chatModelFactory.get(resolved.baseUrl(), resolved.apiKey(), resolved.model(), requestTimeoutSeconds);

        List<ToolResult> toolResults = new ArrayList<>();
        ToolResult[] evidence = {null};
        int[] currentTurn = {0};
        AnalysisAgentTools tools = new AnalysisAgentTools(new AnalysisAgentTools.Backend() {
            @Override
            public List<Map<String, Object>> listTables() {
                recordStep(currentTurn[0], AnalysisAgentTools.LIST_TABLES, "", "", "", null, null, 0);
                return listTablesForAgent();
            }

            @Override
            public Map<String, Object> describeTable(String table) {
                long started = System.nanoTime();
                Map<String, Object> result = describeTableForAgent(table);
                recordStep(currentTurn[0], AnalysisAgentTools.DESCRIBE_TABLE, "", safeText(table), "", null,
                        (String) result.get("error"), elapsedMs(started));
                return result;
            }

            @Override
            public Map<String, Object> searchDocs(String query) {
                long started = System.nanoTime();
                Map<String, Object> result = searchDocsForAgent(query);
                Object hits = result.get("results");
                recordStep(currentTurn[0], AnalysisAgentTools.SEARCH_DOCS, "", safeText(query), "",
                        hits instanceof List<?> list ? list.size() : null, (String) result.get("error"), elapsedMs(started));
                return result;
            }

            @Override
            public Map<String, Object> runSql(String sql, List<String> params, String reason, boolean finalResult) {
                int before = toolResults.size();
                Map<String, Object> result = runSqlForAgent(currentTurn[0], sql, params, reason, finalResult, toolResults, reporter);
                if (finalResult && toolResults.size() > before) {
                    evidence[0] = toolResults.get(toolResults.size() - 1);
                }
                return result;
            }
        }, objectMapper);

        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
        for (ToolCallback callback : callbacks) {
            callbacksByName.put(callback.getToolDefinition().name(), callback);
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(resolved.model())
                .temperature(0.1)
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .parallelToolCalls(true)
                .build();

        List<Message> messages = new ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        messages.add(new SystemMessage(TOOL_AGENT_SYSTEM_PROMPT.formatted(today, today.getYear(), formatTablesForPrompt())));
        int historyStart = Math.max(0, history.size() - 8);
        for (HistoryTurn turn : history.subList(historyStart, history.size())) {
            String content = compressContextText(turn.content(), 1200, 800);
            messages.add("assistant".equalsIgnoreCase(turn.role()) ? new AssistantMessage(content) : new UserMessage(content));
        }
        messages.add(new UserMessage(userQuery));

        int maxTurns = Math.max(2, Math.min(maxToolTurns, 12));
        for (int turn = 1; turn <= maxTurns; turn++) {
            currentTurn[0] = turn;
            if (reporter != null) {
                reporter.emit("正在思考分析路径 (step " + turn + "/" + maxTurns + ")...\n\n");
            }
            AssistantMessage assistant = callAgentModel(chatModel, new Prompt(messages, options));
            if (!assistant.hasToolCalls()) {
                String answer = safeText(assistant.getText());
                recordStep(turn, "final", "", "", answer, null, null, 0);
                if (!answer.isBlank()) {
                    return new AgentRunResult(answer, toolResults, evidence[0]);
                }
                break;
            }

            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (AnalysisAgentTools.ASK_USER.equals(call.name())) {
                    String question = readJsonField(call.arguments(), "question");
                    recordStep(turn, "ask_user", "", "", question, null, null, 0);
                    return new AgentRunResult(question.isBlank()
                            ? "这个问题还缺少必要条件，你能补充一下吗？"
                            : question, toolResults, evidence[0]);
                }
            }

            messages.add(assistant);
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                ToolCallback callback = callbacksByName.get(call.name());
                String result;
                if (callback == null) {
                    result = toJsonQuietly(Map.of("error", "未知工具：" + call.name()));
                } else {
                    try {
                        result = callback.call(call.arguments());
                    } catch (RuntimeException ex) {
                        // 参数解析失败等情况：把错误交还给模型，让它修正调用
                        result = toJsonQuietly(Map.of("error", "工具调用失败：" + rootErrorMessage(ex)));
                        recordStep(turn, call.name(), "", "", "", null, rootErrorMessage(ex), 0);
                    }
                }
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }

        // 达到轮数上限仍未给出结论：禁止继续调用工具，要求模型基于已有结果作答
        recordStep(maxTurns + 1, "synthesize", "", "", "", null, null, 0);
        messages.add(new UserMessage("已达到工具调用次数上限。请基于以上查询结果直接给出结论，不要再调用工具。"));
        OpenAiChatOptions finalOptions = OpenAiChatOptions.builder()
                .model(resolved.model())
                .temperature(0.1)
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.NONE)
                .build();
        String answer = safeText(callAgentModel(chatModel, new Prompt(messages, finalOptions)).getText());
        if (answer.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务未返回分析结论，请检查模型配置后重试");
        }
        return new AgentRunResult(answer, toolResults, evidence[0]);
    }

    private AssistantMessage callAgentModel(ChatModel chatModel, Prompt prompt) {
        RunTrace trace = RUN_TRACE.get();
        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException ex) {
            if (trace != null) {
                trace.llmErrors++;
            }
            log.warn("Tool-calling analysis agent request failed", ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务请求失败：" + rootErrorMessage(ex));
        }
        if (trace != null) {
            trace.llmCalls++;
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (usage != null) {
                trace.promptTokens += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                trace.completionTokens += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage
                        && nativeUsage.promptTokensDetails() != null
                        && nativeUsage.promptTokensDetails().cachedTokens() != null) {
                    trace.cachedPromptTokens += nativeUsage.promptTokensDetails().cachedTokens();
                }
            }
        }
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务未返回内容");
        }
        return response.getResult().getOutput();
    }

    private Map<String, Object> runSqlForAgent(
            int turn,
            String sql,
            List<String> params,
            String reason,
            boolean finalResult,
            List<ToolResult> toolResults,
            StreamReporter reporter
    ) {
        String normalized = normalizeSql(sql);
        List<Object> safeParams = new ArrayList<>(params == null ? List.of() : params);
        long started = System.nanoTime();
        try {
            if (normalized.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SQL is empty.");
            }
            if (reporter != null) {
                reporter.emit("正在查询数据库...\n");
            }
            ToolResult result = executeToolQuery(normalized, safeParams, reason);
            toolResults.add(result);
            recordStep(turn, AnalysisAgentTools.RUN_SQL, result.sqlPreview(), reason, finalResult ? "final_result" : "",
                    result.rowCount(), null, elapsedMs(started));
            if (reporter != null) {
                reporter.emit("查询完成，返回 " + result.rowCount() + " 行。\n\n");
            }
            List<Map<String, Object>> preview = previewRows(result.rows());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rowCount", result.rowCount());
            body.put("rows", preview);
            body.put("truncated", result.rowCount() > preview.size());
            if (result.rowCount() >= Math.max(1, maxQueryRows)) {
                body.put("note", "结果达到行数上限 " + maxQueryRows + "，可能不完整，请加筛选或聚合。");
            }
            return body;
        } catch (RuntimeException ex) {
            String error = rootErrorMessage(ex);
            recordStep(turn, AnalysisAgentTools.RUN_SQL, toSqlPreview(normalized, safeParams), reason, "", null, error, elapsedMs(started));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", error);
            body.put("hint", "SQL 未执行成功。请根据错误信息修正后重新调用 run_sql；如果是列名或取值问题，先调用 describe_table 确认。");
            return body;
        }
    }

    private String formatTablesForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> table : listTablesForAgent()) {
            sb.append("- ").append(table.get("table"));
            Object description = table.get("description");
            if (description != null && !String.valueOf(description).isBlank()) {
                sb.append("：").append(description);
            }
            Object rowCount = table.get("rowCount");
            if (rowCount != null) {
                sb.append("（").append(rowCount).append(" 行）");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private Map<String, Object> searchDocsForAgent(String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        String username = USER_CONTEXT.get();
        if (knowledgeIndexService == null || !knowledgeIndexService.isReady()) {
            body.put("error", "资料库当前不可用：" + (knowledgeIndexService == null ? "未启用" : knowledgeIndexService.status()));
            return body;
        }
        if (username == null || username.isBlank()) {
            body.put("error", "未识别当前用户，无法检索资料");
            return body;
        }
        try {
            List<Map<String, Object>> results = new ArrayList<>();
            for (var chunk : knowledgeIndexService.search(username, safeText(query), knowledgeIndexService.defaultMode(), 4)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", chunk.fileName());
                item.put("content", chunk.content());
                results.add(item);
            }
            body.put("results", results);
            if (results.isEmpty()) {
                body.put("note", "没有找到相关资料，请换个说法或说明资料中没有相关规定。");
            }
        } catch (RuntimeException ex) {
            body.put("error", "资料检索失败：" + rootErrorMessage(ex));
        }
        return body;
    }

    private List<Map<String, Object>> listTablesForAgent() {
        List<Map<String, Object>> tables = new ArrayList<>();
        Set<Object> seenObjects = new LinkedHashSet<>();
        for (String table : tablesForSchemaHint(getAllowedTablesNormalized())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", table);
            try {
                Integer objectId = activeJdbcTemplate().queryForObject("SELECT OBJECT_ID(?)", Integer.class, table);
                if (objectId == null || !seenObjects.add(objectId)) {
                    continue;
                }
                item.put("description", readTableDescription(table));
                item.put("rowCount", activeJdbcTemplate().queryForObject("SELECT COUNT(*) FROM " + toSqlIdentifier(table), Long.class));
            } catch (Exception ex) {
                // 非 SQL Server（如 H2 测试库）没有 OBJECT_ID / 扩展属性，只返回表名
                if (!seenObjects.add(table)) {
                    continue;
                }
            }
            tables.add(item);
        }
        return tables;
    }

    private Map<String, Object> describeTableForAgent(String rawTable) {
        String normalized = normalizeTableRef(rawTable == null ? "" : rawTable);
        Set<String> allowed = getAllowedTablesNormalized();
        if (normalized.isBlank() || !allowed.contains(normalized)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "表不存在或不允许查询：" + rawTable);
            error.put("allowedTables", tablesForSchemaHint(allowed));
            return error;
        }
        String cacheKey = System.identityHashCode(activeJdbcTemplate()) + "|" + normalized;
        CachedTableDescription cached = tableDescriptionCache.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.description();
        }
        Map<String, Object> description = buildTableDescription(normalized);
        tableDescriptionCache.put(cacheKey, new CachedTableDescription(description, System.currentTimeMillis() + 10 * 60_000L));
        return description;
    }

    private Map<String, Object> buildTableDescription(String table) {
        JdbcTemplate jdbc = activeJdbcTemplate();
        String tableSql = toSqlIdentifier(table);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("table", table);

        List<Map<String, Object>> columns;
        try {
            columns = jdbc.queryForList("""
                    SELECT c.name AS name, t.name AS type, CAST(ep.value AS NVARCHAR(1000)) AS description
                    FROM sys.columns c
                    JOIN sys.types t ON t.user_type_id = c.user_type_id
                    LEFT JOIN sys.extended_properties ep
                      ON ep.class = 1 AND ep.major_id = c.object_id AND ep.minor_id = c.column_id AND ep.name = 'MS_Description'
                    WHERE c.object_id = OBJECT_ID(?)
                    ORDER BY c.column_id
                    """, table);
            result.put("description", readTableDescription(table));
        } catch (Exception ex) {
            columns = new ArrayList<>();
        }
        if (columns.isEmpty()) {
            // 非 SQL Server（如 H2）没有 sys.columns / 扩展属性，退化为只取列名
            for (String name : readColumnsForTable(table)) {
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("name", name);
                columns.add(column);
            }
        }
        if (columns.isEmpty()) {
            result.put("error", "读取表结构失败：" + table);
            return result;
        }

        // 一次聚合拿到总行数、各列空值数和数值列范围
        StringBuilder statsSql = new StringBuilder("SELECT COUNT(*) AS [__total]");
        for (Map<String, Object> column : columns) {
            String name = String.valueOf(column.get("name"));
            String col = quoteIdentifier(name);
            statsSql.append(", SUM(CASE WHEN ").append(col).append(" IS NULL THEN 1 ELSE 0 END) AS ").append(quoteIdentifier("null:" + name));
            if (isNumericType(column.get("type"))) {
                statsSql.append(", MIN(").append(col).append(") AS ").append(quoteIdentifier("min:" + name));
                statsSql.append(", MAX(").append(col).append(") AS ").append(quoteIdentifier("max:" + name));
            }
        }
        statsSql.append(" FROM ").append(tableSql);
        Map<String, Object> stats = Map.of();
        try {
            stats = jdbc.queryForMap(statsSql.toString());
            result.put("rowCount", stats.get("__total"));
        } catch (Exception ex) {
            log.debug("Column stats unavailable for {}", table, ex);
        }

        List<Map<String, Object>> described = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String name = String.valueOf(column.get("name"));
            Map<String, Object> item = new LinkedHashMap<>(column);
            item.values().removeIf(v -> v == null);
            Object nulls = stats.get("null:" + name);
            if (nulls != null) {
                item.put("nullCount", nulls);
            }
            if (stats.containsKey("min:" + name)) {
                item.put("min", stats.get("min:" + name));
                item.put("max", stats.get("max:" + name));
            }
            if (isTextType(column.get("type")) && !"id".equalsIgnoreCase(name)) {
                addValueProfile(jdbc, tableSql, name, item);
            }
            described.add(item);
        }
        result.put("columns", described);
        return result;
    }

    private void addValueProfile(JdbcTemplate jdbc, String tableSql, String column, Map<String, Object> item) {
        String col = quoteIdentifier(column);
        try {
            Long distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT " + col + ") FROM " + tableSql, Long.class);
            item.put("distinctCount", distinct);
            int limit = distinct != null && distinct <= 30 ? 30 : 3;
            List<Object> values = jdbc.queryForList("SELECT TOP " + limit + " " + col + " FROM " + tableSql
                    + " WHERE " + col + " IS NOT NULL GROUP BY " + col + " ORDER BY COUNT(*) DESC", Object.class);
            item.put(limit == 30 ? "values" : "examples", values);
        } catch (Exception ex) {
            log.debug("Value profile unavailable for {}.{}", tableSql, column, ex);
        }
    }

    private String readTableDescription(String table) {
        try {
            return activeJdbcTemplate().queryForObject("""
                    SELECT CAST(value AS NVARCHAR(1000)) FROM sys.extended_properties
                    WHERE class = 1 AND major_id = OBJECT_ID(?) AND minor_id = 0 AND name = 'MS_Description'
                    """, String.class, table);
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean isNumericType(Object type) {
        String t = String.valueOf(type).toLowerCase(Locale.ROOT);
        return t.contains("int") || t.contains("decimal") || t.contains("numeric") || t.contains("float")
                || t.contains("real") || t.contains("money");
    }

    private static boolean isTextType(Object type) {
        String t = String.valueOf(type).toLowerCase(Locale.ROOT);
        return type == null || t.contains("char") || t.contains("text");
    }

    private static String quoteIdentifier(String name) {
        return "[" + name.replace("]", "]]") + "]";
    }

    private String readJsonField(String json, String field) {
        try {
            return safeText(objectMapper.readTree(json).path(field).asText(""));
        } catch (Exception ex) {
            return "";
        }
    }

    private String toJsonQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private ResolvedModel resolveModel() {
        ModelConnectionConfig modelConfig = MODEL_CONTEXT.get();
        String apiKey = firstNonBlank(
                modelConfig == null ? null : modelConfig.apiKey(),
                modelConfig == null ? null : modelConfig.deepseekApiKey(),
                deepseekApiKey
        );
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String baseUrl = firstNonBlank(
                modelConfig == null ? null : modelConfig.baseUrl(),
                modelConfig == null ? null : modelConfig.deepseekBaseUrl(),
                deepseekBaseUrl
        );
        String model = firstNonBlank(
                modelConfig == null ? null : modelConfig.model(),
                modelConfig == null ? null : modelConfig.deepseekModel(),
                deepseekModel
        );
        return new ResolvedModel(baseUrl, apiKey, model);
    }

    private record ResolvedModel(String baseUrl, String apiKey, String model) {
    }

    private record CachedTableDescription(Map<String, Object> description, long expiresAt) {
    }

    // ---------------------------------------------------------------------
    // JSON 文本动作模式（原实现）
    // ---------------------------------------------------------------------

    private AgentRunResult runAgent(
            String userQuery,
            String effectiveQuery,
            List<HistoryTurn> history,
            ModelConnectionConfig modelConfig,
            StreamReporter reporter
    ) {
        String schemaHint = buildSchemaHint();
        List<ToolResult> toolResults = new ArrayList<>();
        int safeSteps = Math.max(1, Math.min(maxAgentSteps, 8));

        for (int step = 1; step <= safeSteps; step++) {
            if (reporter != null) {
                reporter.emit("\u6B63\u5728\u601D\u8003\u5206\u6790\u8DEF\u5F84 (step " + step + "/" + safeSteps + ")...\n\n");
            }

            AgentDecision decision = requestAgentDecision(
                    schemaHint,
                    userQuery,
                    effectiveQuery,
                    history,
                    toolResults,
                    step,
                    safeSteps
            );

            if (decision == null) {
                recordStep(step, "no_decision", "", "", "", null, null, 0);
                break;
            }

            if ("ask_user".equals(decision.action())) {
                String message = safeText(decision.message());
                if (message.isBlank()) {
                    message = "\u8FD9\u4E2A\u95EE\u9898\u8FD8\u7F3A\u5C11\u5FC5\u8981\u6761\u4EF6\uff0c\u4F60\u80FD\u8865\u5145\u4E00\u4E0B\u65F6\u95F4\u8303\u56F4\u6216\u6307\u6807\u53E3\u5F84\u5417\uff1F";
                }
                recordStep(step, "ask_user", "", decision.reason(), message, null, null, 0);
                return new AgentRunResult(message, toolResults);
            }

            if ("run_sql".equals(decision.action())) {
                String sql = normalizeSql(decision.sql());
                if (sql.isBlank()) {
                    recordStep(step, "run_sql", "", decision.reason(), "", null, "empty sql", 0);
                    continue;
                }
                if (reporter != null) {
                    reporter.emit("\u6B63\u5728\u67E5\u8BE2\u6570\u636E\u5E93...\n");
                }
                long queryStarted = System.nanoTime();
                ToolResult toolResult;
                try {
                    toolResult = executeToolQuery(sql, decision.params(), decision.reason());
                } catch (RuntimeException ex) {
                    recordStep(step, "run_sql", toSqlPreview(sql, decision.params()), decision.reason(), "",
                            null, rootErrorMessage(ex), elapsedMs(queryStarted));
                    throw ex;
                }
                recordStep(step, "run_sql", toolResult.sqlPreview(), decision.reason(), "",
                        toolResult.rowCount(), null, elapsedMs(queryStarted));
                toolResults.add(toolResult);
                if (reporter != null) {
                    reporter.emit("\u67E5\u8BE2\u5B8C\u6210\uff0C\u8FD4\u56DE " + toolResult.rowCount() + " \u884C\u3002\n\n");
                }
                continue;
            }

            if ("final".equals(decision.action())) {
                String answer = safeText(decision.message());
                recordStep(step, "final", "", decision.reason(), answer, null, null, 0);
                if (!answer.isBlank()) {
                    return new AgentRunResult(answer, toolResults);
                }
            }
        }

        recordStep(safeSteps + 1, "synthesize", "", "", "", null, null, 0);
        String finalAnswer = synthesizeFinalAnswer(userQuery, effectiveQuery, history, toolResults);
        return new AgentRunResult(finalAnswer, toolResults);
    }

    private AgentDecision requestAgentDecision(
            String schemaHint,
            String userQuery,
            String effectiveQuery,
            List<HistoryTurn> history,
            List<ToolResult> toolResults,
            int step,
            int totalSteps
    ) {
        String systemPrompt = buildAgentSystemPrompt(schemaHint);
        String userPrompt = buildDecisionPrompt(userQuery, effectiveQuery, history, toolResults, step, totalSteps);

        String rawOutput = requestModelBlocking(systemPrompt, userPrompt);
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        return parseAgentDecision(rawOutput);
    }

    private ToolResult executeToolQuery(String sql, List<Object> params, String reason) {
        List<Object> safeParams = params == null ? List.of() : params;
        String currentSql = normalizeSql(sql);

        validateReadOnlySql(currentSql);
        validateTableReferences(currentSql);

        Set<String> seenSql = new LinkedHashSet<>();
        seenSql.add(currentSql.toLowerCase(Locale.ROOT));

        RuntimeException lastEx = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                List<Map<String, Object>> rows = executeQuery(currentSql, safeParams);
                return new ToolResult(
                        toSqlPreview(currentSql, safeParams),
                        rows.size(),
                        rows,
                        safeText(reason)
                );
            } catch (RuntimeException ex) {
                lastEx = ex;
                log.warn("SQL execution failed: {} | sql={}", rootErrorMessage(ex), currentSql);
                String repairedSql = tryRepairSql(currentSql, ex);
                if (repairedSql.isBlank()) {
                    break;
                }

                String normalizedKey = repairedSql.toLowerCase(Locale.ROOT);
                if (seenSql.contains(normalizedKey)) {
                    break;
                }

                seenSql.add(normalizedKey);
                log.info("Retrying SQL after auto-repair (attempt {}): {}", attempt, rootErrorMessage(ex));

                validateReadOnlySql(repairedSql);
                validateTableReferences(repairedSql);
                currentSql = repairedSql;
            }
        }

        if (lastEx != null) {
            throw lastEx;
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SQL execution failed without detailed error.");
    }

    private List<Map<String, Object>> executeQuery(String sql, List<Object> params) {
        int rowLimit = Math.max(1, maxQueryRows);
        return activeJdbcTemplate().query(con -> {
            var ps = con.prepareStatement(sql);
            ps.setMaxRows(rowLimit);
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            return ps;
        }, rs -> {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    String label = meta.getColumnLabel(i);
                    if (label == null || label.isBlank()) {
                        label = meta.getColumnName(i);
                    }
                    if (label == null || label.isBlank()) {
                        label = "col" + i;
                    }
                    row.put(label, rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        });
    }

    private String tryRepairSql(String sql, RuntimeException ex) {
        String repairedTryCast = tryRepairSqlForTryCastCompatibility(sql, ex);
        if (!repairedTryCast.isBlank()) {
            return repairedTryCast;
        }
        String repairedTryConvert = tryRepairSqlForTryConvertCompatibility(sql, ex);
        if (!repairedTryConvert.isBlank()) {
            return repairedTryConvert;
        }
        String repairedOffsetFetch = tryRepairSqlForOffsetFetchCompatibility(sql, ex);
        if (!repairedOffsetFetch.isBlank()) {
            return repairedOffsetFetch;
        }
        String repairedUnionOrderBy = tryRepairSqlForUnionOrderByCompatibility(sql, ex);
        if (!repairedUnionOrderBy.isBlank()) {
            return repairedUnionOrderBy;
        }
        String repairedUnionColumnCount = tryRepairSqlForUnionColumnCountMismatch(sql, ex);
        if (!repairedUnionColumnCount.isBlank()) {
            return repairedUnionColumnCount;
        }
        String repairedTextIntComparison = tryRepairSqlForTextIntComparison(sql, ex);
        if (!repairedTextIntComparison.isBlank()) {
            return repairedTextIntComparison;
        }
        return tryRepairSqlForAggregateTypeError(sql, ex);
    }

    private String tryRepairSqlForTryCastCompatibility(String sql, RuntimeException ex) {
        if (!isTryCastCompatibilityError(sql, ex)) {
            return "";
        }
        String rewritten = rewriteTryCastCalls(sql);
        if (rewritten == null || rewritten.isBlank()) {
            return "";
        }
        String normalizedOriginal = normalizeSql(sql).toLowerCase(Locale.ROOT);
        String normalizedRewritten = normalizeSql(rewritten).toLowerCase(Locale.ROOT);
        if (normalizedOriginal.equals(normalizedRewritten)) {
            return "";
        }
        return normalizeSql(rewritten);
    }

    private String tryRepairSqlForTryConvertCompatibility(String sql, RuntimeException ex) {
        if (!isTryConvertCompatibilityError(sql, ex)) {
            return "";
        }
        String rewritten = rewriteTryConvertCalls(sql);
        if (rewritten == null || rewritten.isBlank()) {
            return "";
        }
        String normalizedOriginal = normalizeSql(sql).toLowerCase(Locale.ROOT);
        String normalizedRewritten = normalizeSql(rewritten).toLowerCase(Locale.ROOT);
        if (normalizedOriginal.equals(normalizedRewritten)) {
            return "";
        }
        return normalizeSql(rewritten);
    }

    private String tryRepairSqlForAggregateTypeError(String sql, RuntimeException ex) {
        if (!isNvarcharAggregateError(ex)) {
            return "";
        }
        String rewritten = rewriteNumericAggregates(sql);
        if (rewritten == null || rewritten.isBlank()) {
            return "";
        }
        String normalizedOriginal = normalizeSql(sql).toLowerCase(Locale.ROOT);
        String normalizedRewritten = normalizeSql(rewritten).toLowerCase(Locale.ROOT);
        if (normalizedOriginal.equals(normalizedRewritten)) {
            return "";
        }
        return normalizeSql(rewritten);
    }

    private String tryRepairSqlForTextIntComparison(String sql, RuntimeException ex) {
        if (!isTextIntConversionError(ex)) {
            return "";
        }
        String rewritten = rewriteTextIntComparisons(sql);
        if (rewritten == null || rewritten.isBlank()) {
            return "";
        }
        String normalizedOriginal = normalizeSql(sql).toLowerCase(Locale.ROOT);
        String normalizedRewritten = normalizeSql(rewritten).toLowerCase(Locale.ROOT);
        if (normalizedOriginal.equals(normalizedRewritten)) {
            return "";
        }
        return normalizeSql(rewritten);
    }

    private String tryRepairSqlForUnionOrderByCompatibility(String sql, RuntimeException ex) {
        if (!isUnionOrderBySyntaxError(sql, ex)) {
            return "";
        }
        String rewritten = rewriteUnionBranchesWithOrderBy(sql);
        if (rewritten == null || rewritten.isBlank()) {
            return "";
        }
        String normalizedOriginal = normalizeSql(sql).toLowerCase(Locale.ROOT);
        String normalizedRewritten = normalizeSql(rewritten).toLowerCase(Locale.ROOT);
        if (normalizedOriginal.equals(normalizedRewritten)) {
            return "";
        }
        return normalizeSql(rewritten);
    }

    private String tryRepairSqlForOffsetFetchCompatibility(String sql, RuntimeException ex) {
        if (!isOffsetFetchCompatibilityError(sql, ex)) {
            return "";
        }
        String rewritten = rewriteOffsetFetchPagination(sql);
        if (rewritten == null || rewritten.isBlank()) {
            return "";
        }
        String normalizedOriginal = normalizeSql(sql).toLowerCase(Locale.ROOT);
        String normalizedRewritten = normalizeSql(rewritten).toLowerCase(Locale.ROOT);
        if (normalizedOriginal.equals(normalizedRewritten)) {
            return "";
        }
        return normalizeSql(rewritten);
    }

    private String tryRepairSqlForUnionColumnCountMismatch(String sql, RuntimeException ex) {
        if (!isUnionColumnCountMismatchError(sql, ex)) {
            return "";
        }
        String rewritten = rewriteUnionBranchesToXmlDetail(sql);
        if (rewritten == null || rewritten.isBlank()) {
            return "";
        }
        String normalizedOriginal = normalizeSql(sql).toLowerCase(Locale.ROOT);
        String normalizedRewritten = normalizeSql(rewritten).toLowerCase(Locale.ROOT);
        if (normalizedOriginal.equals(normalizedRewritten)) {
            return "";
        }
        return normalizeSql(rewritten);
    }

    private boolean isNvarcharAggregateError(Throwable ex) {
        String message = rootErrorMessage(ex).toLowerCase(Locale.ROOT);
        if (!(message.contains("nvarchar") || message.contains("varchar"))) {
            return false;
        }
        return message.contains("sum")
                || message.contains("avg")
                || message.contains("operand data type")
                || message.contains("converting data type")
                || message.contains("invalid for sum")
                || message.contains("invalid for avg");
    }

    private boolean isTextIntConversionError(Throwable ex) {
        String message = rootErrorMessage(ex).toLowerCase(Locale.ROOT);
        if (!(message.contains("nvarchar") || message.contains("varchar"))) {
            return false;
        }
        return (message.contains("int") || message.contains("integer"))
                && (message.contains("convert")
                || message.contains("converting")
                || message.contains("转换")
                || message.contains("转换成数据类型"));
    }

    private boolean isUnionOrderBySyntaxError(String sql, Throwable ex) {
        String lowerSql = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
        if (!containsWord(lowerSql, "union")) {
            return false;
        }

        String message = rootErrorMessage(ex).toLowerCase(Locale.ROOT);
        return message.contains("union")
                && (message.contains("syntax")
                || message.contains("incorrect syntax")
                || message.contains("附近有语法错误")
                || message.contains("关键字"));
    }

    private boolean isOffsetFetchCompatibilityError(String sql, Throwable ex) {
        String lowerSql = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
        if (!containsWord(lowerSql, "offset")) {
            return false;
        }

        String message = rootErrorMessage(ex).toLowerCase(Locale.ROOT);
        return message.contains("offset")
                && (message.contains("syntax")
                || message.contains("incorrect syntax")
                || message.contains("附近有语法错误")
                || message.contains("关键字"));
    }

    private boolean isUnionColumnCountMismatchError(String sql, Throwable ex) {
        String lowerSql = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
        if (!containsWord(lowerSql, "union")) {
            return false;
        }

        String message = rootErrorMessage(ex).toLowerCase(Locale.ROOT);
        return message.contains("union")
                && (message.contains("same number")
                || message.contains("相同数目")
                || message.contains("target list"));
    }

    private boolean isTryConvertCompatibilityError(String sql, Throwable ex) {
        String lowerSql = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
        if (!lowerSql.contains("try_convert(")) {
            return false;
        }

        String message = rootErrorMessage(ex).toLowerCase(Locale.ROOT);
        return message.contains("try_convert")
                || message.contains("decimal")
                || message.contains("built-in function")
                || message.contains("内置函数");
    }

    private boolean isTryCastCompatibilityError(String sql, Throwable ex) {
        String lowerSql = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
        if (!lowerSql.contains("try_cast(")) {
            return false;
        }

        String message = rootErrorMessage(ex).toLowerCase(Locale.ROOT);
        return message.contains("try_cast")
                || message.contains("decimal")
                || message.contains("built-in function")
                || message.contains("内置函数");
    }

    private String rootErrorMessage(Throwable ex) {
        if (ex == null) return "";
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? "" : message;
    }

    private String rewriteNumericAggregates(String sql) {
        if (sql == null || sql.isBlank()) return sql;
        String withSafeSum = rewriteAggregateFunction(sql, "sum");
        return rewriteAggregateFunction(withSafeSum, "avg");
    }

    private String rewriteTextIntComparisons(String sql) {
        if (sql == null || sql.isBlank()) return sql;

        String rewritten = rewriteCastIntComparisons(sql);
        return rewriteKnownTextColumnNumericComparisons(rewritten);
    }

    private String rewriteCastIntComparisons(String sql) {
        Matcher matcher = Pattern.compile(
                "(?is)CAST\\s*\\(\\s*([^()]+?)\\s+AS\\s+(?:BIGINT|INT|SMALLINT|TINYINT)\\s*\\)\\s*(=|<>|!=)\\s*(-?\\d+)"
        ).matcher(sql);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String operator = matcher.group(2).trim();
            String value = matcher.group(3).trim();
            matcher.appendReplacement(out, Matcher.quoteReplacement(buildTextNumericComparison(expression, operator, value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String rewriteKnownTextColumnNumericComparisons(String sql) {
        Matcher matcher = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_\\]])(\\[?(?:商品编号|商品名称|商品规格|单据编号|单据类型|供应商名称|按日期|有效期至)\\]?)\\s*(=|<>|!=)\\s*(-?\\d+)(?![A-Za-z0-9_])"
        ).matcher(sql);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String operator = matcher.group(2).trim();
            String value = matcher.group(3).trim();
            matcher.appendReplacement(out, Matcher.quoteReplacement(buildTextNumericComparison(expression, operator, value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String buildTextNumericComparison(String expression, String operator, String value) {
        return "CONVERT(NVARCHAR(4000), " + expression + ") " + operator + " N'" + value + "'";
    }

    private String rewriteTryConvertCalls(String sql) {
        if (sql == null || sql.isBlank()) return sql;

        String lower = sql.toLowerCase(Locale.ROOT);
        String name = "try_convert";
        StringBuilder out = new StringBuilder(sql.length() + 80);
        int cursor = 0;

        while (cursor < sql.length()) {
            int idx = lower.indexOf(name, cursor);
            if (idx < 0) {
                out.append(sql, cursor, sql.length());
                break;
            }

            if (!isWordBoundary(sql, idx - 1) || !isWordBoundary(sql, idx + name.length())) {
                out.append(sql, cursor, idx + name.length());
                cursor = idx + name.length();
                continue;
            }

            int openIdx = idx + name.length();
            while (openIdx < sql.length() && Character.isWhitespace(sql.charAt(openIdx))) {
                openIdx++;
            }
            if (openIdx >= sql.length() || sql.charAt(openIdx) != '(') {
                out.append(sql, cursor, idx + name.length());
                cursor = idx + name.length();
                continue;
            }

            int closeIdx = findMatchingParenthesis(sql, openIdx);
            if (closeIdx < 0) {
                out.append(sql, cursor, sql.length());
                break;
            }

            String inner = sql.substring(openIdx + 1, closeIdx);
            List<String> args = splitTopLevelArgs(inner);
            if (args.size() < 2) {
                out.append(sql, cursor, closeIdx + 1);
                cursor = closeIdx + 1;
                continue;
            }

            String targetType = args.get(0).trim();
            String expression = args.get(1).trim();

            String replacement;
            if (isNumericSqlType(targetType)) {
                replacement = buildSafeNumericExpression(expression);
            } else {
                replacement = "CONVERT(" + targetType + ", " + expression + ")";
            }

            out.append(sql, cursor, idx);
            out.append(replacement);
            cursor = closeIdx + 1;
        }

        return out.toString();
    }

    private String rewriteTryCastCalls(String sql) {
        if (sql == null || sql.isBlank()) return sql;

        String lower = sql.toLowerCase(Locale.ROOT);
        String name = "try_cast";
        StringBuilder out = new StringBuilder(sql.length() + 80);
        int cursor = 0;

        while (cursor < sql.length()) {
            int idx = lower.indexOf(name, cursor);
            if (idx < 0) {
                out.append(sql, cursor, sql.length());
                break;
            }

            if (!isWordBoundary(sql, idx - 1) || !isWordBoundary(sql, idx + name.length())) {
                out.append(sql, cursor, idx + name.length());
                cursor = idx + name.length();
                continue;
            }

            int openIdx = idx + name.length();
            while (openIdx < sql.length() && Character.isWhitespace(sql.charAt(openIdx))) {
                openIdx++;
            }
            if (openIdx >= sql.length() || sql.charAt(openIdx) != '(') {
                out.append(sql, cursor, idx + name.length());
                cursor = idx + name.length();
                continue;
            }

            int closeIdx = findMatchingParenthesis(sql, openIdx);
            if (closeIdx < 0) {
                out.append(sql, cursor, sql.length());
                break;
            }

            String inner = sql.substring(openIdx + 1, closeIdx).trim();
            int asIdx = findTopLevelAsKeyword(inner);
            if (asIdx < 0) {
                out.append(sql, cursor, closeIdx + 1);
                cursor = closeIdx + 1;
                continue;
            }

            String expression = inner.substring(0, asIdx).trim();
            String targetType = inner.substring(asIdx + 2).trim();
            if (expression.isBlank() || targetType.isBlank()) {
                out.append(sql, cursor, closeIdx + 1);
                cursor = closeIdx + 1;
                continue;
            }

            String replacement;
            if (isNumericSqlType(targetType)) {
                replacement = buildSafeNumericExpression(expression);
            } else {
                replacement = "CONVERT(" + targetType + ", " + expression + ")";
            }

            out.append(sql, cursor, idx);
            out.append(replacement);
            cursor = closeIdx + 1;
        }

        return out.toString();
    }

    private String rewriteAggregateFunction(String sql, String functionName) {
        String lower = sql.toLowerCase(Locale.ROOT);
        String name = functionName.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(sql.length() + 64);
        int cursor = 0;

        while (cursor < sql.length()) {
            int idx = lower.indexOf(name, cursor);
            if (idx < 0) {
                out.append(sql, cursor, sql.length());
                break;
            }

            if (!isWordBoundary(sql, idx - 1) || !isWordBoundary(sql, idx + name.length())) {
                out.append(sql, cursor, idx + name.length());
                cursor = idx + name.length();
                continue;
            }

            int openIdx = idx + name.length();
            while (openIdx < sql.length() && Character.isWhitespace(sql.charAt(openIdx))) {
                openIdx++;
            }
            if (openIdx >= sql.length() || sql.charAt(openIdx) != '(') {
                out.append(sql, cursor, idx + name.length());
                cursor = idx + name.length();
                continue;
            }

            int closeIdx = findMatchingParenthesis(sql, openIdx);
            if (closeIdx < 0) {
                out.append(sql, cursor, sql.length());
                break;
            }

            String inner = sql.substring(openIdx + 1, closeIdx);
            String rewrittenInner = rewriteAggregateInner(inner);
            out.append(sql, cursor, idx);
            out.append(sql, idx, idx + name.length());
            out.append("(").append(rewrittenInner).append(")");
            cursor = closeIdx + 1;
        }

        return out.toString();
    }

    private String rewriteUnionBranchesWithOrderBy(String sql) {
        List<UnionSegment> segments = splitTopLevelUnionSegments(sql);
        if (segments.size() < 2) {
            return sql;
        }

        boolean changed = false;
        StringBuilder out = new StringBuilder(sql.length() + segments.size() * 48);
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                out.append(' ').append(segments.get(i - 1).operator()).append(' ');
            }

            String segmentSql = segments.get(i).sql();
            if (shouldWrapUnionBranch(segmentSql)) {
                segmentSql = "SELECT * FROM (\n" + segmentSql.trim() + "\n) AS union_branch_" + (i + 1);
                changed = true;
            }
            out.append(segmentSql.trim());
        }

        return changed ? out.toString() : sql;
    }

    private String rewriteOffsetFetchPagination(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }

        int offsetIdx = findTopLevelWordIndex(sql, "offset");
        if (offsetIdx < 0) {
            return sql;
        }

        String paginationTail = sql.substring(offsetIdx).trim();
        Matcher matcher = Pattern.compile(
                "(?is)^offset\\s+(\\d+)\\s+rows\\s+fetch\\s+next\\s+(\\d+)\\s+rows\\s+only\\s*$"
        ).matcher(paginationTail);
        if (!matcher.matches()) {
            return sql;
        }

        long offset = Long.parseLong(matcher.group(1));
        long fetch = Long.parseLong(matcher.group(2));
        if (fetch <= 0 || offset != 0) {
            return sql;
        }

        String head = sql.substring(0, offsetIdx).trim();
        if (head.isBlank()) {
            return sql;
        }

        String lowerHead = head.toLowerCase(Locale.ROOT);
        if (lowerHead.startsWith("with ")) {
            return sql;
        }
        if (Pattern.compile("(?is)^\\s*select\\s+(distinct\\s+)?top\\s+").matcher(head).find()) {
            return sql;
        }

        Matcher selectMatcher = Pattern.compile("(?is)^\\s*select\\s+(distinct\\s+)?").matcher(head);
        if (!selectMatcher.find()) {
            return sql;
        }

        int insertPos = selectMatcher.end();
        return head.substring(0, insertPos) + "TOP " + fetch + " " + head.substring(insertPos);
    }

    private String rewriteUnionBranchesToXmlDetail(String sql) {
        List<UnionSegment> segments = splitTopLevelUnionSegments(sql);
        if (segments.size() < 2) {
            return sql;
        }

        boolean changed = false;
        StringBuilder out = new StringBuilder(sql.length() + segments.size() * 80);
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                out.append(' ').append(segments.get(i - 1).operator()).append(' ');
            }

            String segmentSql = segments.get(i).sql();
            String rewrittenSegment = rewriteUnionBranchToXmlDetail(segmentSql);
            if (!rewrittenSegment.equals(segmentSql)) {
                changed = true;
            }
            out.append(rewrittenSegment.trim());
        }

        if (!changed) {
            return sql;
        }
        return stripTrailingTopLevelOrderBy(out.toString());
    }

    private String rewriteUnionBranchToXmlDetail(String segmentSql) {
        if (segmentSql == null || segmentSql.isBlank()) {
            return segmentSql == null ? "" : segmentSql;
        }

        int selectIdx = findLastTopLevelWordIndex(segmentSql, "select");
        if (selectIdx < 0) {
            return segmentSql;
        }

        String prefix = segmentSql.substring(0, selectIdx);
        String branch = stripTrailingTopLevelOrderBy(segmentSql.substring(selectIdx).trim());
        Matcher labeledStarMatcher = Pattern.compile(
                "(?is)^select\\s+(top\\s*\\(?\\s*\\d+\\s*\\)?\\s+)?(n?'(?:[^']|'')*')\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*\\*\\s+from\\s+([\\[\\]A-Za-z0-9_.]+)\\s*$"
        ).matcher(branch);
        if (labeledStarMatcher.matches()) {
            String topClause = normalizeTopClause(labeledStarMatcher.group(1));
            String labelExpr = labeledStarMatcher.group(2).trim();
            String alias = labeledStarMatcher.group(3).trim();
            String source = labeledStarMatcher.group(4).trim();
            String rewrittenBranch = "SELECT " + labelExpr + " AS " + alias
                    + ", CAST((SELECT " + topClause + "* FROM " + source + " FOR XML RAW('row'), ROOT('rows')) AS NVARCHAR(MAX)) AS detail";
            return prefix + rewrittenBranch;
        }

        Matcher plainStarMatcher = Pattern.compile(
                "(?is)^select\\s+(top\\s*\\(?\\s*\\d+\\s*\\)?\\s+)?\\*\\s+from\\s+([\\[\\]A-Za-z0-9_.]+)\\s*$"
        ).matcher(branch);
        if (!plainStarMatcher.matches()) {
            return segmentSql;
        }

        String topClause = normalizeTopClause(plainStarMatcher.group(1));
        String source = plainStarMatcher.group(2).trim();
        String label = "'" + escapeSqlStringLiteral(extractSourceLabel(source)) + "'";
        String rewrittenBranch = "SELECT " + label + " AS analysis_type"
                + ", CAST((SELECT " + topClause + "* FROM " + source + " FOR XML RAW('row'), ROOT('rows')) AS NVARCHAR(MAX)) AS detail";
        return prefix + rewrittenBranch;
    }

    private String normalizeTopClause(String topClause) {
        if (topClause == null || topClause.isBlank()) {
            return "";
        }
        return topClause.trim().replaceAll("\\s+", " ") + " ";
    }

    private String extractSourceLabel(String source) {
        if (source == null || source.isBlank()) {
            return "branch";
        }

        String normalized = source.trim();
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex + 1 < normalized.length()) {
            normalized = normalized.substring(dotIndex + 1);
        }
        normalized = normalized.replace("[", "").replace("]", "").trim();
        return normalized.isBlank() ? "branch" : normalized;
    }

    private String escapeSqlStringLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("'", "''");
    }

    private String stripTrailingTopLevelOrderBy(String sql) {
        int orderByIdx = findLastTopLevelOrderByIndex(sql);
        if (orderByIdx < 0) {
            return sql;
        }
        return sql.substring(0, orderByIdx).trim();
    }

    private boolean shouldWrapUnionBranch(String segmentSql) {
        if (segmentSql == null || segmentSql.isBlank()) {
            return false;
        }
        String trimmed = segmentSql.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("with ")) {
            return false;
        }
        return lower.startsWith("select")
                && containsTopLevelWord(trimmed, "top")
                && hasTopLevelOrderBy(trimmed);
    }

    private List<UnionSegment> splitTopLevelUnionSegments(String sql) {
        List<UnionSegment> segments = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return segments;
        }

        String lower = sql.toLowerCase(Locale.ROOT);
        int depth = 0;
        boolean inString = false;
        boolean inBracketIdentifier = false;
        int start = 0;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);

            if (inBracketIdentifier) {
                if (ch == ']') {
                    inBracketIdentifier = false;
                }
                continue;
            }

            if (ch == '[') {
                inBracketIdentifier = true;
                continue;
            }

            if (ch == '\'') {
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0) {
                continue;
            }

            if (lower.startsWith("union", i)
                    && isWordBoundary(lower, i - 1)
                    && isWordBoundary(lower, i + 5)) {
                int operatorEnd = i + 5;
                while (operatorEnd < sql.length() && Character.isWhitespace(sql.charAt(operatorEnd))) {
                    operatorEnd++;
                }

                String operator = "UNION";
                if (lower.startsWith("all", operatorEnd)
                        && isWordBoundary(lower, operatorEnd - 1)
                        && isWordBoundary(lower, operatorEnd + 3)) {
                    operator = "UNION ALL";
                    operatorEnd += 3;
                }

                String branchSql = sql.substring(start, i).trim();
                if (!branchSql.isBlank()) {
                    segments.add(new UnionSegment(branchSql, operator));
                }
                start = operatorEnd;
                i = operatorEnd - 1;
            }
        }

        String tail = sql.substring(start).trim();
        if (!tail.isBlank()) {
            segments.add(new UnionSegment(tail, ""));
        }
        return segments;
    }

    private boolean hasTopLevelOrderBy(String sql) {
        return findLastTopLevelOrderByIndex(sql) >= 0;
    }

    private int findLastTopLevelOrderByIndex(String sql) {
        if (sql == null || sql.isBlank()) {
            return -1;
        }

        String lower = sql.toLowerCase(Locale.ROOT);
        int depth = 0;
        boolean inString = false;
        boolean inBracketIdentifier = false;
        int lastOrderBy = -1;

        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);

            if (inBracketIdentifier) {
                if (ch == ']') {
                    inBracketIdentifier = false;
                }
                continue;
            }

            if (ch == '[') {
                inBracketIdentifier = true;
                continue;
            }

            if (ch == '\'') {
                if (inString && i + 1 < lower.length() && lower.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0) {
                continue;
            }

            if (lower.startsWith("order", i)
                    && isWordBoundary(lower, i - 1)
                    && isWordBoundary(lower, i + 5)) {
                int next = i + 5;
                while (next < lower.length() && Character.isWhitespace(lower.charAt(next))) {
                    next++;
                }
                if (lower.startsWith("by", next)
                        && isWordBoundary(lower, next - 1)
                        && isWordBoundary(lower, next + 2)) {
                    lastOrderBy = i;
                }
            }
        }
        return lastOrderBy;
    }

    private boolean containsTopLevelWord(String sql, String word) {
        if (sql == null || sql.isBlank() || word == null || word.isBlank()) {
            return false;
        }

        return findTopLevelWordIndex(sql, word) >= 0;
    }

    private int findTopLevelWordIndex(String sql, String word) {
        if (sql == null || sql.isBlank() || word == null || word.isBlank()) {
            return -1;
        }

        String lower = sql.toLowerCase(Locale.ROOT);
        String lowerWord = word.toLowerCase(Locale.ROOT);
        int depth = 0;
        boolean inString = false;
        boolean inBracketIdentifier = false;

        for (int i = 0; i <= lower.length() - lowerWord.length(); i++) {
            char ch = lower.charAt(i);

            if (inBracketIdentifier) {
                if (ch == ']') {
                    inBracketIdentifier = false;
                }
                continue;
            }

            if (ch == '[') {
                inBracketIdentifier = true;
                continue;
            }

            if (ch == '\'') {
                if (inString && i + 1 < lower.length() && lower.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0) {
                continue;
            }

            if (lower.startsWith(lowerWord, i)
                    && isWordBoundary(lower, i - 1)
                    && isWordBoundary(lower, i + lowerWord.length())) {
                return i;
            }
        }
        return -1;
    }

    private int findLastTopLevelWordIndex(String sql, String word) {
        if (sql == null || sql.isBlank() || word == null || word.isBlank()) {
            return -1;
        }

        int last = -1;
        int searchFrom = 0;
        while (searchFrom < sql.length()) {
            int idx = findTopLevelWordIndex(sql.substring(searchFrom), word);
            if (idx < 0) {
                break;
            }
            last = searchFrom + idx;
            searchFrom = last + word.length();
        }
        return last;
    }

    private record UnionSegment(String sql, String operator) {
    }

    private int findMatchingParenthesis(String text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findTopLevelAsKeyword(String text) {
        if (text == null || text.isBlank()) return -1;

        int depth = 0;
        boolean inString = false;
        String lower = text.toLowerCase(Locale.ROOT);

        for (int i = 0; i < lower.length() - 1; i++) {
            char ch = lower.charAt(i);
            if (ch == '\'') {
                if (inString && i + 1 < lower.length() && lower.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
                continue;
            }

            if (inString) continue;

            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0) continue;

            if (lower.startsWith("as", i)
                    && isWordBoundary(lower, i - 1)
                    && isWordBoundary(lower, i + 2)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isWordBoundary(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return true;
        }
        char ch = text.charAt(index);
        return !(Character.isLetterOrDigit(ch) || ch == '_');
    }

    private String rewriteAggregateInner(String inner) {
        if (inner == null || inner.isBlank()) return inner == null ? "" : inner;

        String trimmed = inner.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (isAlreadySafeNumericExpression(lower)) {
            return inner;
        }

        String prefix = "";
        String expr = trimmed;
        if (lower.startsWith("distinct ")) {
            prefix = "DISTINCT ";
            expr = trimmed.substring("distinct".length()).trim();
        }
        if (expr.isBlank()) {
            return inner;
        }
        return prefix + buildSafeNumericExpression(expr);
    }

    private boolean isAlreadySafeNumericExpression(String lowerInner) {
        if (lowerInner == null || lowerInner.isBlank()) return false;
        return lowerInner.contains("as decimal(38,6)")
                && lowerInner.contains("convert(nvarchar(4000),");
    }

    private boolean isNumericSqlType(String typeExpr) {
        if (typeExpr == null || typeExpr.isBlank()) return false;
        String value = typeExpr.toLowerCase(Locale.ROOT).replace(" ", "");
        return value.startsWith("decimal")
                || value.startsWith("numeric")
                || value.startsWith("float")
                || value.startsWith("real")
                || value.startsWith("money")
                || value.startsWith("smallmoney")
                || value.startsWith("bigint")
                || value.startsWith("int")
                || value.startsWith("smallint")
                || value.startsWith("tinyint");
    }

    private List<String> splitTopLevelArgs(String inner) {
        List<String> args = new ArrayList<>();
        if (inner == null) return args;

        int depth = 0;
        boolean inString = false;
        int start = 0;

        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);

            if (ch == '\'') {
                if (inString && i + 1 < inner.length() && inner.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
                continue;
            }

            if (inString) continue;

            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (ch == ',' && depth == 0) {
                args.add(inner.substring(start, i).trim());
                start = i + 1;
            }
        }

        if (start <= inner.length()) {
            args.add(inner.substring(start).trim());
        }
        return args;
    }

    private String buildSafeNumericExpression(String expr) {
        String source = "LTRIM(RTRIM(CONVERT(NVARCHAR(4000), " + expr + ")))";
        return "CASE " +
                "WHEN " + expr + " IS NULL THEN NULL " +
                "WHEN " + source + " = '' THEN NULL " +
                "WHEN " + source + " LIKE '%[^0-9.-]%' THEN NULL " +
                "WHEN LEN(" + source + ") - LEN(REPLACE(" + source + ", '.', '')) > 1 THEN NULL " +
                "WHEN LEN(" + source + ") - LEN(REPLACE(" + source + ", '-', '')) > 1 THEN NULL " +
                "WHEN CHARINDEX('-', " + source + ") > 1 THEN NULL " +
                "ELSE CAST(" + source + " AS DECIMAL(38,6)) END";
    }

    private String synthesizeFinalAnswer(
            String userQuery,
            String effectiveQuery,
            List<HistoryTurn> history,
            List<ToolResult> toolResults
    ) {
        String systemPrompt = """
                You are a business data assistant.
                Answer in the same language as the user.
                Use only verified SQL evidence. Do not fabricate.
                If evidence is insufficient, say what is missing and ask one concise follow-up question.
                Keep the response practical and direct.
                """;

        String userPrompt = buildFinalPrompt(userQuery, effectiveQuery, history, toolResults);
        String rawOutput = requestModelBlocking(systemPrompt, userPrompt);
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务未返回分析结论，请检查模型配置后重试");
        }

        AgentDecision maybeDecision = parseAgentDecision(rawOutput);
        if (maybeDecision != null && "final".equals(maybeDecision.action()) && !safeText(maybeDecision.message()).isBlank()) {
            return maybeDecision.message().trim();
        }
        return rawOutput.trim();
    }

    private String buildAgentSystemPrompt(String schemaHint) {
        return """
                You are a SQL analysis agent for Microsoft SQL Server.
                You can choose ONE action at a time, and your output MUST be one JSON object only.

                Allowed actions:
                1) ask_user
                2) run_sql
                3) final

                JSON schema:
                {
                  "action": "ask_user | run_sql | final",
                  "message": "message to user",
                  "sql": "SELECT ... WHERE col = ?",
                  "params": ["value1", 123],
                  "reason": "short reason"
                }

                Rules:
                - Use run_sql when data is needed.
                - SQL must be read-only and single-statement (SELECT / WITH only).
                - Use '?' placeholders and put values in params by order.
                - If the question is broad but still executable, prefer run_sql with reasonable defaults.
                - Use ask_user only when key conditions are truly missing and cannot be inferred.
                - You may run multiple SQL steps before final.
                - Never fabricate data.
                - When using final, put the final answer in "message".
                - Respond in the same language as the user's question. If the user writes in Chinese, all ask_user and final messages must be in Chinese.

                Domain hints for this dataset:
                - Date column is usually 按日期.
                - Document type column is usually 单据类型.
                - For broad sales intent, prefer 单据类型 LIKE '%销售%' unless user asks exact type.
                - Typical KPI SQL fields: SUM(出库数量), SUM(开单金额), COUNT(DISTINCT 单据编号).
                - Business codes and names such as 商品编号, 商品名称, 商品规格, 单据编号, 单据类型, 供应商名称, 按日期, 有效期至 are text fields; do not cast business codes to numbers; compare them as quoted strings.

                - For SQL Server 2008 compatibility, avoid TRY_CONVERT and TRY_CAST.
                - Avoid OFFSET / FETCH. Prefer TOP for first-page ranking queries.
                - If SUM/AVG may use text-typed numeric fields, use CASE + CAST guarding (or rely on tool auto-repair).
                - All UNION / UNION ALL branches must project the same number of columns. Do not use SELECT 'type', * across different CTEs unless their schemas match.
                - If you use UNION / UNION ALL with SELECT TOP ... ORDER BY ..., wrap each branch in a subquery or CTE. Do not place ORDER BY directly before UNION.

                Allowed schema:
                """ + schemaHint;
    }

    private String buildDecisionPrompt(
            String userQuery,
            String effectiveQuery,
            List<HistoryTurn> history,
            List<ToolResult> toolResults,
            int step,
            int totalSteps
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("step: ").append(step).append("/").append(totalSteps).append("\n\n");
        sb.append("current_user_question:\n").append(userQuery).append("\n\n");
        if (!effectiveQuery.equals(userQuery)) {
            sb.append("resolved_query_with_context:\n").append(effectiveQuery).append("\n\n");
        }
        sb.append("history:\n").append(formatHistoryForPrompt(history)).append("\n\n");
        sb.append("tool_results_so_far:\n").append(formatToolResultsForPrompt(toolResults)).append("\n\n");
        sb.append("Return one JSON object only.");
        return sb.toString();
    }

    private String buildFinalPrompt(
            String userQuery,
            String effectiveQuery,
            List<HistoryTurn> history,
            List<ToolResult> toolResults
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("user_question:\n").append(userQuery).append("\n\n");
        if (!effectiveQuery.equals(userQuery)) {
            sb.append("resolved_query_with_context:\n").append(effectiveQuery).append("\n\n");
        }
        sb.append("history:\n").append(formatHistoryForPrompt(history)).append("\n\n");
        sb.append("verified_sql_results:\n").append(formatToolResultsForPrompt(toolResults)).append("\n\n");
        sb.append("Please provide the final response now. Do not return JSON.");
        return sb.toString();
    }

    private String formatHistoryForPrompt(List<HistoryTurn> history) {
        if (history == null || history.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, history.size() - 8);
        for (int i = start; i < history.size(); i++) {
            HistoryTurn turn = history.get(i);
            sb.append(turn.role()).append(": ")
                    .append(compressContextText(turn.content(), 480, 300))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String formatToolResultsForPrompt(List<ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        int maxTools = Math.min(toolResults.size(), 4);
        for (int i = toolResults.size() - maxTools; i < toolResults.size(); i++) {
            ToolResult result = toolResults.get(i);
            sb.append("#").append(i + 1).append("\n");
            if (!result.reason().isBlank()) {
                sb.append("reason: ").append(result.reason()).append("\n");
            }
            sb.append("sql: ").append(result.sqlPreview()).append("\n");
            sb.append("rows: ").append(result.rowCount()).append("\n");
            sb.append("sample: ").append(toRowsJson(previewRows(result.rows()))).append("\n\n");
        }
        return compressContextText(sb.toString().trim(), 6000, 3600);
    }

    private String buildSchemaHint() {
        Set<String> allowed = getAllowedTablesNormalized();
        StringBuilder sb = new StringBuilder();
        int limit = 30;
        int count = 0;
        for (String table : tablesForSchemaHint(allowed)) {
            if (count >= limit) break;
            List<String> columns = readColumnsForTable(table);
            if (columns.isEmpty()) {
                sb.append("- ").append(table).append(": columns unavailable\n");
            } else {
                sb.append("- ").append(table).append(": ").append(String.join(", ", columns)).append("\n");
            }
            count++;
        }
        if (allowed.size() > count) {
            sb.append("- ... ").append(allowed.size() - count).append(" more table refs available\n");
        }
        return sb.toString().trim();
    }

    private List<String> readColumnsForTable(String normalizedTable) {
        String sqlExpr = toSqlIdentifier(normalizedTable);
        String sql = "SELECT * FROM " + sqlExpr + " WHERE 1 = 0";
        try {
            return activeJdbcTemplate().query(sql, rs -> {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    cols.add(meta.getColumnName(i));
                }
                return cols;
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String toSqlIdentifier(String normalizedTable) {
        String[] parts = normalizedTable.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('.');
            sb.append('[').append(parts[i]).append(']');
        }
        return sb.toString();
    }

    private AgentDecision parseAgentDecision(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }

        String json = extractJsonObject(rawText);
        if (json.isBlank()) {
            return new AgentDecision("final", "", List.of(), rawText.trim(), "");
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            String action = root.path("action").asText("").trim().toLowerCase(Locale.ROOT);
            String message = firstNonBlank(
                    root.path("message").asText(""),
                    root.path("answer").asText(""),
                    root.path("question").asText("")
            );
            String sql = root.path("sql").asText("");
            List<Object> params = parseParams(root.path("params"));
            String reason = root.path("reason").asText("");

            if (action.isBlank()) {
                if (sql != null && !sql.isBlank()) {
                    action = "run_sql";
                } else if (message != null && !message.isBlank()) {
                    action = "final";
                }
            }

            if (!"ask_user".equals(action) && !"run_sql".equals(action) && !"final".equals(action)) {
                if (action.contains("ask") || action.contains("clarif")) {
                    action = "ask_user";
                } else if (action.contains("sql") || action.contains("query") || action.contains("tool")) {
                    action = "run_sql";
                } else {
                    action = "final";
                }
            }
            return new AgentDecision(action, safeText(sql), params, safeText(message), safeText(reason));
        } catch (Exception ex) {
            return new AgentDecision("final", "", List.of(), rawText.trim(), "");
        }
    }

    private String requestModelBlocking(String systemPrompt, String userPrompt) {
        ModelConnectionConfig modelConfig = MODEL_CONTEXT.get();
        String normalizedProvider = normalizeProvider(firstNonBlank(
                modelConfig == null ? null : modelConfig.provider(),
                provider
        ));
        return requestByDeepSeek(systemPrompt, userPrompt);
    }

    private String requestByDeepSeek(String systemPrompt, String userPrompt) {
        ModelConnectionConfig modelConfig = MODEL_CONTEXT.get();
        String apiKey = firstNonBlank(
                modelConfig == null ? null : modelConfig.apiKey(),
                modelConfig == null ? null : modelConfig.deepseekApiKey(),
                deepseekApiKey
        );
        String baseUrl = firstNonBlank(
                modelConfig == null ? null : modelConfig.baseUrl(),
                modelConfig == null ? null : modelConfig.deepseekBaseUrl(),
                deepseekBaseUrl
        );
        String model = firstNonBlank(
                modelConfig == null ? null : modelConfig.model(),
                modelConfig == null ? null : modelConfig.deepseekModel(),
                deepseekModel
        );
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        try {
            String responseBody = webClient.post()
                    .uri(trimTrailingSlash(baseUrl) + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            Mono.error(new RuntimeException("OpenAI-compatible request failed: " + response.statusCode()))
                    )
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .block();
            recordUsage(responseBody);
            return extractDeepSeekContent(responseBody);
        } catch (Exception ex) {
            log.warn("OpenAI-compatible analysis agent request failed", ex);
            RunTrace trace = RUN_TRACE.get();
            if (trace != null) {
                trace.llmErrors++;
            }
            return "";
        }
    }

    private void recordUsage(String responseBody) {
        RunTrace trace = RUN_TRACE.get();
        if (trace == null || responseBody == null || responseBody.isBlank()) {
            return;
        }
        trace.llmCalls++;
        try {
            JsonNode usage = objectMapper.readTree(responseBody).path("usage");
            trace.promptTokens += usage.path("prompt_tokens").asLong(0);
            trace.completionTokens += usage.path("completion_tokens").asLong(0);
            long cached = usage.path("prompt_cache_hit_tokens").asLong(
                    usage.path("prompt_tokens_details").path("cached_tokens").asLong(0));
            trace.cachedPromptTokens += cached;
        } catch (Exception ignored) {
            // 用量缺失不影响主流程
        }
    }

    private void recordStep(int step, String action, String sql, String reason, String message,
                            Integer rowCount, String error, long durationMs) {
        RunTrace trace = RUN_TRACE.get();
        if (trace == null) {
            return;
        }
        trace.steps.add(new StepTrace(step, action, safeText(sql), safeText(reason),
                compressContextText(safeText(message), 800, 600), rowCount, error, durationMs));
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String extractDeepSeekContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText("").trim();
            }
            return "";
        } catch (Exception ex) {
            return "";
        }
    }

    private List<Object> parseParams(JsonNode paramsNode) {
        if (paramsNode == null || !paramsNode.isArray()) return List.of();
        List<Object> params = new ArrayList<>();
        for (JsonNode node : paramsNode) {
            if (node.isNull()) {
                params.add(null);
            } else if (node.isInt()) {
                params.add(node.intValue());
            } else if (node.isLong()) {
                params.add(node.longValue());
            } else if (node.isFloatingPointNumber()) {
                params.add(node.decimalValue());
            } else if (node.isBoolean()) {
                params.add(node.booleanValue());
            } else {
                params.add(node.asText());
            }
        }
        return params;
    }

    private String mergeAnswerWithToolTail(String answer, List<ToolResult> toolResults) {
        String cleanAnswer = safeText(answer);
        if (toolResults == null || toolResults.isEmpty()) {
            return cleanAnswer;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(cleanAnswer);
        sb.append("\n\n---\n");
        for (int i = 0; i < toolResults.size(); i++) {
            ToolResult result = toolResults.get(i);
            sb.append("SQL ").append(i + 1).append(": ").append(result.sqlPreview()).append("\n");
            sb.append("Rows: ").append(result.rowCount()).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatError(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        return "Analysis failed: " + message;
    }

    private void validateReadOnlySql(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT).trim();
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only SELECT queries are allowed.");
        }
        if (lower.contains(";")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Multi-statement SQL is not allowed.");
        }
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (containsWord(lower, keyword)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unsafe SQL keyword detected: " + keyword);
            }
        }
    }

    private void validateTableReferences(String sql) {
        Set<String> allowed = getAllowedTablesNormalized();
        Set<String> metadataTables = getMetadataTablesNormalized();
        Set<String> cteNames = extractCteNames(sql);
        Matcher matcher = TABLE_REF_PATTERN.matcher(sql);
        Set<String> referenced = new LinkedHashSet<>();

        while (matcher.find()) {
            String normalized = normalizeTableRef(matcher.group(1));
            if (!normalized.isBlank()) {
                referenced.add(normalized);
            }
        }

        if (referenced.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No table reference found in generated SQL.");
        }

        boolean hasBaseTableReference = false;
        for (String table : referenced) {
            if (cteNames.contains(table)) {
                continue;
            }
            hasBaseTableReference = true;
            if (!allowed.contains(table) && !metadataTables.contains(table)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Table not allowed: " + table);
            }
        }

        if (!hasBaseTableReference) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No base table reference found in generated SQL.");
        }
    }

    private Set<String> getAllowedTablesNormalized() {
        ExecutionContext context = EXECUTION_CONTEXT.get();
        if (context != null && context.allowedTables() != null && !context.allowedTables().isEmpty()) {
            return context.allowedTables();
        }

        Set<String> allowed = parseAllowedTables(allowedTablesConfig);
        if (allowed.isEmpty()) {
            allowed.add("dbo.inout");
            allowed.add("aisql.dbo.inout");
            allowed.add("inout");
        }
        return allowed;
    }

    private Set<String> getMetadataTablesNormalized() {
        return Set.of(
                "information_schema.columns",
                "information_schema.tables",
                "sys.columns",
                "sys.tables",
                "sys.schemas",
                "sys.objects"
        );
    }

    private Set<String> parseAllowedTables(String rawConfig) {
        Set<String> allowed = new LinkedHashSet<>();
        String[] parts = rawConfig == null ? new String[0] : rawConfig.split(",");
        for (String part : parts) {
            String normalized = normalizeTableRef(part);
            if (!normalized.isBlank()) {
                allowed.add(normalized);
            }
        }
        return allowed;
    }

    private <T> T withExecutionContext(ExecutionContext context, Supplier<T> action) {
        EXECUTION_CONTEXT.set(context);
        try {
            return action.get();
        } finally {
            EXECUTION_CONTEXT.remove();
        }
    }

    private <T> T withExecutionAndModelContext(
            ExecutionContext context,
            ModelConnectionConfig modelConfig,
            Supplier<T> action
    ) {
        EXECUTION_CONTEXT.set(context);
        if (modelConfig != null) {
            MODEL_CONTEXT.set(modelConfig);
        }
        try {
            return action.get();
        } finally {
            EXECUTION_CONTEXT.remove();
            MODEL_CONTEXT.remove();
        }
    }

    private ModelConnectionConfig normalizeModelConfig(ModelConnectionConfig config) {
        if (config == null || !Boolean.TRUE.equals(config.enabled())) {
            return null;
        }
        return new ModelConnectionConfig(
                true,
                safeText(config.provider()),
                safeText(config.nl2sqlProvider()),
                safeText(config.baseUrl()),
                config.apiKey(),
                safeText(config.model()),
                safeText(config.deepseekBaseUrl()),
                config.deepseekApiKey(),
                safeText(config.deepseekModel())
        );
    }

    private JdbcTemplate activeJdbcTemplate() {
        ExecutionContext context = EXECUTION_CONTEXT.get();
        if (context != null && context.jdbcTemplate() != null) {
            return context.jdbcTemplate();
        }
        return jdbcTemplate;
    }

    private ExecutionContext resolveExecutionContext(DbConnectionConfig dbConfig) {
        if (dbConfig != null && !customDbEnabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Custom database connections are disabled.");
        }

        if (!hasCustomDbConfig(dbConfig)) {
            return new ExecutionContext(jdbcTemplate, getAllowedTablesNormalized());
        }

        String jdbcUrl = safeText(dbConfig.jdbcUrl());
        if (jdbcUrl.isBlank()) {
            jdbcUrl = buildSqlServerJdbcUrl(dbConfig);
        }
        String driver = firstNonBlank(dbConfig.driver(), DEFAULT_SQLSERVER_DRIVER);

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(driver);
        dataSource.setUrl(jdbcUrl);

        String username = safeText(dbConfig.username());
        if (!username.isBlank()) {
            dataSource.setUsername(username);
        }
        if (dbConfig.password() != null) {
            dataSource.setPassword(dbConfig.password());
        }

        JdbcTemplate customJdbcTemplate = new JdbcTemplate(dataSource);
        try {
            customJdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot connect SQL Server: " + rootErrorMessage(ex));
        }

        Set<String> allowed = loadAllowedTablesForContext(customJdbcTemplate, dbConfig);
        if (allowed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No table found in selected SQL Server database.");
        }
        return new ExecutionContext(customJdbcTemplate, allowed);
    }

    private boolean hasCustomDbConfig(DbConnectionConfig dbConfig) {
        if (dbConfig == null) return false;
        if (!Boolean.TRUE.equals(dbConfig.enabled())) return false;
        return !safeText(dbConfig.jdbcUrl()).isBlank()
                || !safeText(dbConfig.server()).isBlank()
                || !safeText(dbConfig.database()).isBlank();
    }

    private String buildSqlServerJdbcUrl(DbConnectionConfig dbConfig) {
        String server = safeText(dbConfig.server());
        String database = safeText(dbConfig.database());
        if (server.isBlank() || database.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "dbConfig.server and dbConfig.database are required.");
        }
        int port = dbConfig.port() == null || dbConfig.port() <= 0
                ? DEFAULT_SQLSERVER_PORT
                : dbConfig.port();
        return "jdbc:jtds:sqlserver://" + server + ":" + port + "/" + database;
    }

    private Set<String> loadAllowedTablesForContext(JdbcTemplate customJdbcTemplate, DbConnectionConfig dbConfig) {
        Set<String> configuredAllowed = parseAllowedTables(dbConfig.allowedTables());
        if (!configuredAllowed.isEmpty()) {
            return configuredAllowed;
        }

        boolean discoverTables = dbConfig.discoverTables() == null || dbConfig.discoverTables();
        if (!discoverTables) {
            return parseAllowedTables(allowedTablesConfig);
        }
        return discoverTablesFromDatabase(customJdbcTemplate, safeText(dbConfig.database()));
    }

    private Set<String> discoverTablesFromDatabase(JdbcTemplate customJdbcTemplate, String database) {
        Set<String> allowed = new LinkedHashSet<>();
        List<Map<String, Object>> rows = customJdbcTemplate.queryForList("""
                SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME AS table_name
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_TYPE = 'BASE TABLE'
                ORDER BY TABLE_SCHEMA, TABLE_NAME
                """);

        int limit = 300;
        int count = 0;
        for (Map<String, Object> row : rows) {
            if (count >= limit) break;
            String schema = row.get("schema_name") == null ? "" : String.valueOf(row.get("schema_name"));
            String table = row.get("table_name") == null ? "" : String.valueOf(row.get("table_name"));
            addAllowedTableVariants(allowed, database, schema, table);
            count++;
        }
        return allowed;
    }

    private void addAllowedTableVariants(Set<String> allowed, String database, String schema, String table) {
        String normalizedTable = normalizeTableRef(table);
        if (!normalizedTable.isBlank()) {
            allowed.add(normalizedTable);
        }

        String normalizedSchemaTable = normalizeTableRef(schema + "." + table);
        if (!normalizedSchemaTable.isBlank()) {
            allowed.add(normalizedSchemaTable);
        }

        if (database != null && !database.isBlank()) {
            String normalizedDbSchemaTable = normalizeTableRef(database + "." + schema + "." + table);
            if (!normalizedDbSchemaTable.isBlank()) {
                allowed.add(normalizedDbSchemaTable);
            }
        }
    }

    private List<String> tablesForSchemaHint(Set<String> allowed) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String table : allowed) {
            if (countDots(table) == 1) ordered.add(table);
        }
        for (String table : allowed) {
            if (countDots(table) == 2) ordered.add(table);
        }
        for (String table : allowed) {
            if (countDots(table) == 0) ordered.add(table);
        }
        return new ArrayList<>(ordered);
    }

    private int countDots(String value) {
        if (value == null || value.isBlank()) return 0;
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '.') count++;
        }
        return count;
    }

    private Set<String> extractCteNames(String sql) {
        if (sql == null || sql.isBlank()) return Set.of();
        Matcher matcher = CTE_NAME_PATTERN.matcher(sql);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name == null || name.isBlank()) continue;
            names.add(name.trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private String normalizeTableRef(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isBlank()) return "";
        int aliasIdx = value.indexOf(' ');
        if (aliasIdx > 0) {
            value = value.substring(0, aliasIdx);
        }
        return value
                .replace("[", "")
                .replace("]", "")
                .replace("`", "")
                .replace("\"", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(text)
                .find();
    }

    private String normalizeProvider(String rawProvider) {
        if (rawProvider == null || rawProvider.isBlank()) {
            return "openai-compatible";
        }
        String value = rawProvider.trim().toLowerCase(Locale.ROOT);
        if ("openai-compatible".equals(value) || "openai".equals(value) || "deepseek".equals(value)) {
            return value;
        }
        return "openai-compatible";
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com";
        }
        return value.trim().replaceAll("/+$", "");
    }

    private String normalizeQuery(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "query cannot be empty");
        }
        return query;
    }

    private List<HistoryTurn> normalizeHistory(List<HistoryTurn> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<HistoryTurn> normalized = new ArrayList<>();
        for (HistoryTurn turn : history) {
            if (turn == null) continue;
            String role = turn.role == null ? "" : turn.role.trim().toLowerCase(Locale.ROOT);
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String content = safeText(turn.content);
            if (content.isBlank()) continue;
            normalized.add(new HistoryTurn(role, compressContextText(content, 1800, 1000)));
        }
        if (normalized.size() <= 14) return normalized;
        return normalized.subList(normalized.size() - 14, normalized.size());
    }

    private String applyContextHints(String query, List<HistoryTurn> history) {
        if (history == null || history.isEmpty()) return query;
        if (containsDateHint(query)) return query;

        if (!looksLikeFollowUp(query) && !mentionsAnalysisIntent(query)) {
            return query;
        }

        String latestDate = findLatestDate(history);
        if (latestDate != null) {
            return query + "\n[context_hint] date=" + latestDate;
        }

        String latestMonth = findLatestMonth(history);
        if (latestMonth != null) {
            return query + "\n[context_hint] month=" + latestMonth;
        }
        return query;
    }

    private boolean containsDateHint(String text) {
        if (text == null || text.isBlank()) return false;
        if (DATE_PATTERN.matcher(text).find() || MONTH_PATTERN.matcher(text).find()) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String hint : DATE_WORD_HINTS) {
            if (lower.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return text.contains("[context_hint]");
    }

    private boolean looksLikeFollowUp(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String hint : FOLLOW_UP_HINTS) {
            if (lower.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean mentionsAnalysisIntent(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String hint : ANALYSIS_INTENT_HINTS) {
            if (lower.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String findLatestDate(List<HistoryTurn> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Matcher matcher = DATE_PATTERN.matcher(history.get(i).content());
            String found = null;
            while (matcher.find()) {
                found = formatYmd(matcher.group(1), matcher.group(2), matcher.group(3));
            }
            if (found != null) return found;
        }
        return null;
    }

    private String findLatestMonth(List<HistoryTurn> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Matcher matcher = MONTH_PATTERN.matcher(history.get(i).content());
            String found = null;
            while (matcher.find()) {
                found = formatYm(matcher.group(1), matcher.group(2));
            }
            if (found != null) return found;
        }
        return null;
    }

    private String formatYmd(String y, String m, String d) {
        return Integer.parseInt(y) + "-" + twoDigits(m) + "-" + twoDigits(d);
    }

    private String formatYm(String y, String m) {
        return Integer.parseInt(y) + "-" + twoDigits(m);
    }

    private String twoDigits(String value) {
        int number = Integer.parseInt(value);
        return number < 10 ? "0" + number : String.valueOf(number);
    }

    private String toSqlPreview(String sql, List<Object> params) {
        if (params == null || params.isEmpty()) return sql;
        return sql + " /* params=" + params + " */";
    }

    private String extractJsonObject(String text) {
        if (text == null) return "";
        String value = text.trim();
        if (value.isBlank()) return "";

        Matcher fenced = CODE_FENCE_PATTERN.matcher(value);
        if (fenced.find()) {
            value = fenced.group(1).trim();
        }

        Matcher jsonMatcher = JSON_OBJECT_PATTERN.matcher(value);
        if (jsonMatcher.find()) {
            return jsonMatcher.group().trim();
        }
        return "";
    }

    private List<Map<String, Object>> previewRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        int rowLimit = Math.max(1, analysisMaxPreviewRows);
        int maxCell = Math.max(20, analysisMaxCellLength);
        List<Map<String, Object>> preview = new ArrayList<>();

        for (int i = 0; i < rows.size() && i < rowLimit; i++) {
            Map<String, Object> sourceRow = rows.get(i);
            Map<String, Object> targetRow = new LinkedHashMap<>();
            int colCount = 0;
            for (Map.Entry<String, Object> entry : sourceRow.entrySet()) {
                colCount++;
                if (colCount > 24) break;
                targetRow.put(entry.getKey(), truncateCell(entry.getValue(), maxCell));
            }
            preview.add(targetRow);
        }
        return preview;
    }

    private Object truncateCell(Object value, int maxCell) {
        if (value == null) return null;
        if (value instanceof Number || value instanceof Boolean) return value;
        String text = String.valueOf(value);
        if (text.length() <= maxCell) return text;
        return text.substring(0, maxCell) + "...";
    }

    private String toRowsJson(List<Map<String, Object>> rows) {
        try {
            return objectMapper.writeValueAsString(rows == null ? List.of() : rows);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String normalizeSql(String sql) {
        if (sql == null) return "";
        String cleaned = sql.trim();
        if (cleaned.isBlank()) return "";
        while (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private String safeText(String text) {
        if (text == null) return "";
        return text.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String compressContextText(String content, int maxLen, int headLen) {
        if (content == null) return "";
        String text = content.trim();
        if (text.length() <= maxLen) return text;

        int safeMaxLen = Math.max(120, maxLen);
        int safeHeadLen = Math.min(Math.max(40, headLen), safeMaxLen - 20);
        int tailLen = safeMaxLen - safeHeadLen - 8;
        if (tailLen < 20) {
            tailLen = 20;
            safeHeadLen = safeMaxLen - tailLen - 8;
        }
        return text.substring(0, safeHeadLen) + "\n...\n" + text.substring(text.length() - tailLen);
    }

    private String toStreamPayload(String event, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        if (text != null) {
            payload.put("text", text);
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"event\":\"" + event + "\",\"text\":\"\"}";
        }
    }

    private String toStatusPayload(String text) {
        return toStreamPayload("status", text);
    }

    private List<String> splitTextToPayloadChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;
        int safeChunkSize = Math.max(40, chunkSize);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + safeChunkSize);
            chunks.add(toStreamPayload("text_generated", text.substring(start, end)));
            start = end;
        }
        return chunks;
    }

    public record DbConnectionConfig(
            Boolean enabled,
            String server,
            Integer port,
            String database,
            String username,
            String password,
            String jdbcUrl,
            String driver,
            String allowedTables,
            Boolean discoverTables
    ) {
    }

    public record ModelConnectionConfig(
            Boolean enabled,
            String provider,
            String nl2sqlProvider,
            String baseUrl,
            String apiKey,
            String model,
            String deepseekBaseUrl,
            String deepseekApiKey,
            String deepseekModel
    ) {
    }

    public record AnalysisResult(
            String answer,
            String sqlTemplate,
            int rowCount,
            List<Map<String, Object>> rows
    ) {
    }

    public record HistoryTurn(String role, String content) {
    }

    public record StepTrace(
            int step,
            String action,
            String sql,
            String reason,
            String message,
            Integer rowCount,
            String error,
            long durationMs
    ) {
    }

    public record UsageTrace(
            int llmCalls,
            int llmErrors,
            long promptTokens,
            long completionTokens,
            long cachedPromptTokens
    ) {
    }

    public record TracedAnalysisResult(
            String answer,
            String finalSql,
            int rowCount,
            List<Map<String, Object>> rows,
            List<StepTrace> steps,
            UsageTrace usage,
            long latencyMs,
            String error
    ) {
    }

    private static final class RunTrace {
        private final List<StepTrace> steps = new ArrayList<>();
        private int llmCalls;
        private int llmErrors;
        private long promptTokens;
        private long completionTokens;
        private long cachedPromptTokens;

        private TracedAnalysisResult toResult(String answer, ToolResult last, long latencyMs, String error) {
            UsageTrace usage = new UsageTrace(llmCalls, llmErrors, promptTokens, completionTokens, cachedPromptTokens);
            return new TracedAnalysisResult(
                    answer == null ? "" : answer,
                    last == null ? "" : last.sqlPreview(),
                    last == null ? 0 : last.rowCount(),
                    last == null ? List.of() : last.rows(),
                    List.copyOf(steps),
                    usage,
                    latencyMs,
                    error
            );
        }
    }

    /** evidence：模型标记为"回答依据"的查询；未标记时取最后一条成功的查询。 */
    private record AgentRunResult(String answer, List<ToolResult> toolResults, ToolResult evidence) {
        private AgentRunResult(String answer, List<ToolResult> toolResults) {
            this(answer, toolResults, null);
        }

        private ToolResult evidenceOrLast() {
            if (evidence != null) {
                return evidence;
            }
            return toolResults.isEmpty() ? null : toolResults.get(toolResults.size() - 1);
        }
    }

    private record AgentDecision(
            String action,
            String sql,
            List<Object> params,
            String message,
            String reason
    ) {
        private AgentDecision(String action, String sql, List<Object> params, String message, String reason) {
            this.action = action == null ? "final" : action;
            this.sql = sql == null ? "" : sql;
            this.params = params == null ? Collections.emptyList() : params;
            this.message = message == null ? "" : message;
            this.reason = reason == null ? "" : reason;
        }
    }

    private record ToolResult(
            String sqlPreview,
            int rowCount,
            List<Map<String, Object>> rows,
            String reason
    ) {
    }

    private record ExecutionContext(JdbcTemplate jdbcTemplate, Set<String> allowedTables) {
    }

    @FunctionalInterface
    private interface StreamReporter {
        void emit(String message);
    }
}

