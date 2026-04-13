package com.example.myai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Value("${dify.api-key:}")
    private String difyApiKey;

    @Value("${dify.base-url:http://localhost:8080/v1}")
    private String difyBaseUrl;

    @Value("${analysis.agent.request-timeout-seconds:${dify.request-timeout-seconds:45}}")
    private long requestTimeoutSeconds;

    @Value("${deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Value("${deepseek.model:deepseek-chat}")
    private String deepseekModel;

    private final SqlChatService sqlChatService;
    private final JdbcTemplate jdbcTemplate;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public SqlAnalysisService(
            SqlChatService sqlChatService,
            JdbcTemplate jdbcTemplate,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.sqlChatService = sqlChatService;
        this.jdbcTemplate = jdbcTemplate;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
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

            AgentRunResult runResult = runAgent(userQuery, effectiveQuery, safeHistory, null);
            String answer = mergeAnswerWithToolTail(runResult.answer(), runResult.toolResults());

            ToolResult last = runResult.toolResults().isEmpty()
                    ? null
                    : runResult.toolResults().get(runResult.toolResults().size() - 1);

            if (last == null) {
                return new AnalysisResult(answer, "-", 0, List.of());
            }
            return new AnalysisResult(answer, last.sqlPreview(), last.rowCount(), last.rows());
        });
    }

    public Flux<String> analyzeStream(String rawQuery, List<HistoryTurn> history) {
        return analyzeStream(rawQuery, history, null);
    }

    public Flux<String> analyzeStream(String rawQuery, List<HistoryTurn> history, DbConnectionConfig dbConfig) {
        return Flux.create(sink -> Schedulers.boundedElastic().schedule(() -> {
            try {
                ExecutionContext context = resolveExecutionContext(dbConfig);
                withExecutionContext(context, () -> {
                    String userQuery = normalizeQuery(rawQuery);
                    List<HistoryTurn> safeHistory = normalizeHistory(history);
                    String effectiveQuery = applyContextHints(userQuery, safeHistory);

                    sink.next(toStatusPayload("\u6B63\u5728\u7406\u89E3\u95EE\u9898..."));

                    AgentRunResult runResult = runAgent(
                            userQuery,
                            effectiveQuery,
                            safeHistory,
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
                sink.complete();
            }
        }));
    }

    private AgentRunResult runAgent(
            String userQuery,
            String effectiveQuery,
            List<HistoryTurn> history,
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
                break;
            }

            if ("ask_user".equals(decision.action())) {
                String message = safeText(decision.message());
                if (message.isBlank()) {
                    message = "\u8FD9\u4E2A\u95EE\u9898\u8FD8\u7F3A\u5C11\u5FC5\u8981\u6761\u4EF6\uff0c\u4F60\u80FD\u8865\u5145\u4E00\u4E0B\u65F6\u95F4\u8303\u56F4\u6216\u6307\u6807\u53E3\u5F84\u5417\uff1F";
                }
                return new AgentRunResult(message, toolResults);
            }

            if ("run_sql".equals(decision.action())) {
                String sql = normalizeSql(decision.sql());
                if (sql.isBlank()) {
                    continue;
                }
                if (reporter != null) {
                    reporter.emit("\u6B63\u5728\u67E5\u8BE2\u6570\u636E\u5E93...\n");
                }
                ToolResult toolResult = executeToolQuery(sql, decision.params(), decision.reason());
                toolResults.add(toolResult);
                if (reporter != null) {
                    reporter.emit("\u67E5\u8BE2\u5B8C\u6210\uff0C\u8FD4\u56DE " + toolResult.rowCount() + " \u884C\u3002\n\n");
                }
                continue;
            }

            if ("final".equals(decision.action())) {
                String answer = safeText(decision.message());
                if (!answer.isBlank()) {
                    return new AgentRunResult(answer, toolResults);
                }
            }
        }

        String fallbackAnswer = synthesizeFinalAnswer(userQuery, effectiveQuery, history, toolResults);
        return new AgentRunResult(fallbackAnswer, toolResults);
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
            return buildFallbackText(userQuery, toolResults);
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
                - Date column is usually 鎸夋棩鏈?
                - Document type column is usually 鍗曟嵁绫诲瀷.
                - For broad sales intent, prefer 鍗曟嵁绫诲瀷 LIKE '%閿€鍞?' unless user asks exact type.
                - Typical KPI SQL fields: SUM(鍑哄簱鏁伴噺), SUM(寮€鍗曢噾棰?, COUNT(DISTINCT 鍗曟嵁缂栧彿).

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
        String sql = "SELECT TOP 0 * FROM " + sqlExpr;
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
        String normalizedProvider = normalizeProvider(provider);
        if ("dify".equals(normalizedProvider)) {
            return requestByDify(systemPrompt, userPrompt);
        }
        return requestByDeepSeek(systemPrompt, userPrompt);
    }

    private String requestByDeepSeek(String systemPrompt, String userPrompt) {
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            return "";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", deepseekModel);
        body.put("temperature", 0.1);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        try {
            String responseBody = webClient.post()
                    .uri(deepseekBaseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + deepseekApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            Mono.error(new RuntimeException("DeepSeek request failed: " + response.statusCode()))
                    )
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .block();
            return extractDeepSeekContent(responseBody);
        } catch (Exception ex) {
            log.warn("DeepSeek analysis agent request failed", ex);
            return "";
        }
    }

    private String requestByDify(String systemPrompt, String userPrompt) {
        if (difyApiKey == null || difyApiKey.isBlank()) {
            return "";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", Map.of());
        body.put("query", systemPrompt + "\n\n" + userPrompt);
        body.put("response_mode", "blocking");
        body.put("user", "analysis_agent");
        body.put("conversation_id", "");

        try {
            String responseBody = webClient.post()
                    .uri(difyBaseUrl + "/chat-messages")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + difyApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            Mono.error(new RuntimeException("Dify request failed: " + response.statusCode()))
                    )
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .block();
            return extractDifyContent(responseBody);
        } catch (Exception ex) {
            log.warn("Dify analysis agent request failed", ex);
            return "";
        }
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

    private String extractDifyContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String answer = root.path("answer").asText("");
            if (!answer.isBlank()) return answer.trim();
            String content = root.path("content").asText("");
            if (!content.isBlank()) return content.trim();
            JsonNode message = root.path("message");
            if (!message.isMissingNode()) {
                content = message.path("content").asText("");
                if (!content.isBlank()) return content.trim();
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

    private String buildFallbackText(String userQuery, List<ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "\u6211\u8FD8\u6CA1\u62FF\u5230\u8DB3\u591F\u6570\u636E\u6765\u56DE\u7B54\u8FD9\u4E2A\u95EE\u9898\uff0C\u4F60\u53EF\u4EE5\u518D\u7ED9\u6211\u4E00\u4E2A\u5177\u4F53\u6307\u6807\u6216\u65F6\u95F4\u8303\u56F4\u3002";
        }
        ToolResult last = toolResults.get(toolResults.size() - 1);
        List<Map<String, Object>> rows = last.rows() == null ? List.of() : last.rows();

        if (looksLikeProductRecommendationQuery(userQuery)) {
            String recommendation = buildRecommendationSummary(rows);
            if (!recommendation.isBlank()) {
                return recommendation;
            }
        }

        String metricSummary = buildMetricSummary(rows);
        if (!metricSummary.isBlank()) {
            return metricSummary;
        }

        return "\u5DF2\u5B8C\u6210\u67E5\u8BE2\uff0C\u8FD4\u56DE " + last.rowCount()
                + " \u884C\u3002\u8BF7\u518D\u6307\u5B9A\u4E00\u4E2A\u5206\u6790\u7EF4\u5EA6\uff08\u4F8B\u5982\uFF1A\u6309\u5546\u54C1/\u5BA2\u6237/\u5468\u6B21\u5BF9\u6BD4\uFF09\uFF0C\u6211\u7EE7\u7EED\u7ED9\u4F60\u51C6\u786E\u7ED3\u8BBA\u3002";
    }

    private boolean looksLikeProductRecommendationQuery(String query) {
        if (query == null || query.isBlank()) return false;
        return query.contains("\u5356\u5F97\u6BD4\u8F83\u597D")
                || query.contains("\u5356\u7684\u597D")
                || query.contains("\u70ED\u9500")
                || query.contains("\u8865\u8D27")
                || query.contains("\u63A8\u8350")
                || query.toLowerCase(Locale.ROOT).contains("top")
                || query.toLowerCase(Locale.ROOT).contains("best seller");
    }

    private String buildRecommendationSummary(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "\u5F53\u524D\u67E5\u8BE2\u6CA1\u6709\u8FD4\u56DE\u53EF\u7528\u9500\u552E\u660E\u7EC6\uff0C\u6682\u65F6\u65E0\u6CD5\u7ED9\u51FA\u8865\u8D27\u63A8\u8350\u3002";
        }

        String nameCol = detectColumn(rows.get(0),
                "\u5546\u54C1\u540D\u79F0", "\u5546\u54C1\u540D", "\u54C1\u540D", "product_name", "productname");
        String salesCol = detectColumn(rows.get(0),
                "\u9500\u552E\u6570\u91CF", "\u603B\u51FA\u5E93\u6570\u91CF", "\u51FA\u5E93\u6570\u91CF", "\u9500\u91CF",
                "total_sales_quantity", "total_sales", "qty", "quantity");
        String stockCol = detectColumn(rows.get(0),
                "\u73B0\u6709\u5E93\u5B58", "\u5F53\u524D\u5E93\u5B58", "\u5E93\u5B58\u6570\u91CF",
                "current_stock", "currentstock", "stock_qty", "stockqty");

        if (nameCol == null || salesCol == null) {
            return "";
        }

        List<ProductScore> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = normalizeText(row.get(nameCol));
            double sales = toDouble(row.get(salesCol));
            if (name.isBlank() || sales <= 0) continue;
            double stock = stockCol == null ? -1 : toDouble(row.get(stockCol));
            items.add(new ProductScore(name, sales, stock));
        }
        if (items.isEmpty()) {
            return "";
        }

        items.sort((a, b) -> Double.compare(b.sales, a.sales));
        int topN = Math.min(3, items.size());
        StringBuilder sb = new StringBuilder();
        sb.append("\u6839\u636E\u5F53\u524D\u6570\u636E\uFF0C\u5356\u5F97\u8F83\u597D\u7684\u5546\u54C1\u662F\uFF1A\n");
        for (int i = 0; i < topN; i++) {
            ProductScore item = items.get(i);
            sb.append(i + 1).append(". ").append(item.name)
                    .append("\uFF08\u9500\u91CF ").append(formatNumber(item.sales)).append("\uFF09\n");
        }

        sb.append("\n\u8865\u8D27\u5EFA\u8BAE\uFF1A\n");
        for (int i = 0; i < topN; i++) {
            ProductScore item = items.get(i);
            if (item.stock >= 0) {
                if (item.stock < item.sales * 0.2) {
                    sb.append("- \u4F18\u5148\u8865\u8D27 ").append(item.name)
                            .append("\uFF08\u5E93\u5B58 ").append(formatNumber(item.stock))
                            .append("\uFF0C\u4F4E\u4E8E\u9500\u91CF\u7684 20%\uFF09\n");
                } else {
                    sb.append("- \u53EF\u6301\u7EED\u8DDF\u8E2A ").append(item.name)
                            .append("\uFF08\u5E93\u5B58 ").append(formatNumber(item.stock))
                            .append("\uFF0C\u6DA8\u52BF\u826F\u597D\uFF09\n");
                }
            } else {
                sb.append("- \u5EFA\u8BAE\u4F18\u5148\u8865\u8D27 ").append(item.name)
                        .append("\uFF08\u9AD8\u9500\u91CF\u54C1\u79CD\uFF09\n");
            }
        }
        sb.append("\n\u5982\u9700\u8981\uFF0C\u6211\u53EF\u4EE5\u7EE7\u7EED\u7ED9\u4F60\u7B97\u201C\u5EFA\u8BAE\u8865\u8D27\u91CF\u201D\uFF08\u6309\u5B89\u5168\u5E93\u5B58+\u5468\u8F6C\u5929\u6570\uFF09\u3002");
        return sb.toString().trim();
    }

    private String buildMetricSummary(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "";
        if (rows.size() == 1) {
            Map<String, Object> row = rows.get(0);
            List<String> metrics = new ArrayList<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() instanceof Number number) {
                    metrics.add(entry.getKey() + " = " + formatNumber(number.doubleValue()));
                }
            }
            if (!metrics.isEmpty()) {
                return "\u57FA\u4E8E\u67E5\u8BE2\u7ED3\u679C\uFF0C\u5173\u952E\u6307\u6807\u5982\u4E0B\uFF1A\n- "
                        + String.join("\n- ", metrics);
            }
        }
        return "";
    }

    private String detectColumn(Map<String, Object> row, String... candidates) {
        if (row == null || row.isEmpty() || candidates == null) return null;
        for (String candidate : candidates) {
            for (String key : row.keySet()) {
                if (key.equalsIgnoreCase(candidate)) return key;
            }
        }
        for (String candidate : candidates) {
            String c = candidate.toLowerCase(Locale.ROOT);
            for (String key : row.keySet()) {
                if (key.toLowerCase(Locale.ROOT).contains(c)) return key;
            }
        }
        return null;
    }

    private String normalizeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double toDouble(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
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

    private JdbcTemplate activeJdbcTemplate() {
        ExecutionContext context = EXECUTION_CONTEXT.get();
        if (context != null && context.jdbcTemplate() != null) {
            return context.jdbcTemplate();
        }
        return jdbcTemplate;
    }

    private ExecutionContext resolveExecutionContext(DbConnectionConfig dbConfig) {
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
            return "deepseek";
        }
        String value = rawProvider.trim().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(value) || "dify".equals(value)) {
            return value;
        }
        return "deepseek";
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

    public record AnalysisResult(
            String answer,
            String sqlTemplate,
            int rowCount,
            List<Map<String, Object>> rows
    ) {
    }

    public record HistoryTurn(String role, String content) {
    }

    private record AgentRunResult(String answer, List<ToolResult> toolResults) {
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

    private record ProductScore(String name, double sales, double stock) {
    }

    private record ExecutionContext(JdbcTemplate jdbcTemplate, Set<String> allowedTables) {
    }

    @FunctionalInterface
    private interface StreamReporter {
        void emit(String message);
    }
}

