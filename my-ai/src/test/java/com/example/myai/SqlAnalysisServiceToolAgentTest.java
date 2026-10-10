package com.example.myai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 用脚本化的假模型验证原生工具调用循环：错误回传、ask_user 中断、写操作拦截、轮数上限。 */
class SqlAnalysisServiceToolAgentTest {

    private JdbcTemplate jdbc;
    private ScriptedModel model;
    private SqlAnalysisService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tool-agent-" + UUID.randomUUID() + ";MODE=MSSQLServer;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS dbo");
        jdbc.execute("CREATE TABLE dbo.inout (id BIGINT PRIMARY KEY, 单据类型 NVARCHAR(20), 商品名称 NVARCHAR(50), 出库数量 DECIMAL(18,2))");
        jdbc.update("INSERT INTO dbo.inout VALUES (1, N'销售出库', N'阿莫西林胶囊', 5), (2, N'销售出库', N'医用外科口罩', 9), (3, N'销售退货', N'医用外科口罩', 2)");

        model = new ScriptedModel();
        AgentChatModelFactory factory = new AgentChatModelFactory() {
            @Override
            public ChatModel get(String baseUrl, String apiKey, String modelName, long timeoutSeconds) {
                return model;
            }
        };
        service = new SqlAnalysisService(jdbc, WebClient.builder(), new ObjectMapper(), factory);
        ReflectionTestUtils.setField(service, "deepseekApiKey", "test-key");
        ReflectionTestUtils.setField(service, "deepseekBaseUrl", "http://localhost");
        ReflectionTestUtils.setField(service, "deepseekModel", "test-model");
        ReflectionTestUtils.setField(service, "maxQueryRows", 200);
        ReflectionTestUtils.setField(service, "analysisMaxPreviewRows", 30);
        ReflectionTestUtils.setField(service, "analysisMaxCellLength", 120);
    }

    @Test
    void sqlErrorIsReturnedToModelWhichCorrectsItself() {
        model.toolCall("run_sql", "{\"sql\":\"SELECT SUM(不存在的列) FROM dbo.inout\",\"params\":[],\"reason\":\"first try\"}");
        model.toolCall("run_sql", "{\"sql\":\"SELECT SUM(出库数量) AS qty FROM dbo.inout WHERE 单据类型 = ?\",\"params\":[\"销售出库\"],\"reason\":\"fixed\"}");
        model.text("销售出库数量合计 14。");

        SqlAnalysisService.TracedAnalysisResult result = service.analyzeWithTrace("销售出库数量合计是多少？", List.of(), null, "tools", "tester");

        assertThat(result.error()).isNull();
        assertThat(result.answer()).isEqualTo("销售出库数量合计 14。");
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.finalSql()).contains("单据类型 = ?");
        List<SqlAnalysisService.StepTrace> sqlSteps = result.steps().stream().filter(s -> s.action().equals("run_sql")).toList();
        assertThat(sqlSteps).hasSize(2);
        assertThat(sqlSteps.get(0).error()).isNotBlank();
        assertThat(sqlSteps.get(1).error()).isNull();
        // 第二次请求里，模型能看到第一次 SQL 的错误信息
        assertThat(model.toolResponsesSentAt(1)).anyMatch(text -> text.contains("\"error\""));
    }

    @Test
    void askUserStopsTheLoopAndReturnsTheQuestion() {
        model.toolCall("ask_user", "{\"question\":\"你想查哪个商品的库存？\"}");

        SqlAnalysisService.TracedAnalysisResult result = service.analyzeWithTrace("帮我查一下那个药的库存", List.of(), null, "tools", "tester");

        assertThat(result.answer()).isEqualTo("你想查哪个商品的库存？");
        assertThat(result.steps()).extracting(SqlAnalysisService.StepTrace::action).containsExactly("ask_user");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void writeStatementsAreRejectedAndTableIsUnchanged() {
        model.toolCall("run_sql", "{\"sql\":\"DELETE FROM dbo.inout\",\"params\":[],\"reason\":\"user asked\"}");
        model.text("无法执行删除操作。");

        SqlAnalysisService.TracedAnalysisResult result = service.analyzeWithTrace("删除所有记录", List.of(), null, "tools", "tester");

        assertThat(result.steps().get(0).error()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.inout", Integer.class)).isEqualTo(3);
    }

    @Test
    void reachingTurnLimitForcesAnAnswerWithoutTools() {
        ReflectionTestUtils.setField(service, "maxToolTurns", 2);
        model.toolCall("run_sql", "{\"sql\":\"SELECT COUNT(*) AS n FROM dbo.inout\",\"params\":[],\"reason\":\"a\"}");
        model.toolCall("run_sql", "{\"sql\":\"SELECT COUNT(*) AS n FROM dbo.inout\",\"params\":[],\"reason\":\"b\"}");
        model.text("共 3 行。");

        SqlAnalysisService.TracedAnalysisResult result = service.analyzeWithTrace("一共有多少行？", List.of(), null, "tools", "tester");

        assertThat(result.answer()).isEqualTo("共 3 行。");
        assertThat(result.steps()).extracting(SqlAnalysisService.StepTrace::action).contains("synthesize");
        OpenAiChatOptions lastOptions = (OpenAiChatOptions) model.prompts.get(model.prompts.size() - 1).getOptions();
        assertThat(String.valueOf(lastOptions.getToolChoice())).isEqualTo("none");
    }

    @Test
    void describeTableRejectsTablesOutsideTheWhitelist() {
        model.toolCall("describe_table", "{\"table\":\"sys.objects\"}");
        model.text("ok");

        service.analyzeWithTrace("看看系统表", List.of(), null, "tools", "tester");

        String described = model.toolResponsesSentAt(1).get(0);
        assertThat(described).contains("不允许查询").contains("allowedTables").contains("dbo.inout");
    }

    /** 按顺序返回预先编排的回复，并记录每次收到的 Prompt。 */
    private static final class ScriptedModel implements ChatModel {
        private final Deque<AssistantMessage> script = new ArrayDeque<>();
        private final List<Prompt> prompts = new ArrayList<>();

        void toolCall(String name, String arguments) {
            script.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + script.size(), "function", name, arguments)))
                    .build());
        }

        void text(String content) {
            script.add(new AssistantMessage(content));
        }

        int calls() {
            return prompts.size();
        }

        List<String> toolResponsesSentAt(int callIndex) {
            List<String> texts = new ArrayList<>();
            for (Message message : prompts.get(callIndex).getInstructions()) {
                if (message instanceof ToolResponseMessage toolResponse) {
                    toolResponse.getResponses().forEach(r -> texts.add(r.responseData()));
                }
            }
            return texts;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            AssistantMessage next = script.isEmpty() ? new AssistantMessage("(script exhausted)") : script.poll();
            return new ChatResponse(List.of(new Generation(next)));
        }
    }
}
