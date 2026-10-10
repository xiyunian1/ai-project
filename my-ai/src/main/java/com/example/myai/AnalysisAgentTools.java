package com.example.myai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析 Agent 的工具集。Spring AI 根据 @Tool / @ToolParam 生成发给模型的工具定义（JSON Schema）；
 * 调用循环（轮数上限、轨迹记录、ask_user 中断、错误回传）由 SqlAnalysisService 控制。
 */
public class AnalysisAgentTools {

    public static final String LIST_TABLES = "list_tables";
    public static final String DESCRIBE_TABLE = "describe_table";
    public static final String RUN_SQL = "run_sql";
    public static final String ASK_USER = "ask_user";
    public static final String SEARCH_DOCS = "search_docs";

    /** 工具的实际执行由 SqlAnalysisService 提供，以复用其中的只读校验、表白名单和方言修复。 */
    public interface Backend {
        List<Map<String, Object>> listTables();

        Map<String, Object> describeTable(String table);

        Map<String, Object> runSql(String sql, List<String> params, String reason, boolean finalResult);

        Map<String, Object> searchDocs(String query);
    }

    private final Backend backend;
    private final ObjectMapper objectMapper;

    public AnalysisAgentTools(Backend backend, ObjectMapper objectMapper) {
        this.backend = backend;
        this.objectMapper = objectMapper;
    }

    @Tool(name = LIST_TABLES, description = "列出当前可以查询的业务表，以及每张表的用途说明和行数。")
    public String listTables() {
        return toJson(backend.listTables());
    }

    @Tool(name = DESCRIBE_TABLE, description = "查看一张表的字段：字段名、类型、业务含义、空值数量、数值范围，"
            + "以及文本字段的取值（取值较少的枚举字段会列出全部取值）。写 SQL 前用它确认列名、口径和取值，不要猜。")
    public String describeTable(@ToolParam(description = "表名，例如 dbo.inout") String table) {
        return toJson(backend.describeTable(table));
    }

    @Tool(name = RUN_SQL, description = "在 SQL Server 上执行一条只读查询（以 SELECT 或 WITH 开头的单条语句），"
            + "返回总行数和前 30 行数据。执行出错时返回错误信息，请据此修正 SQL 后重试。")
    public String runSql(
            @ToolParam(description = "要执行的 SQL。字面值可以直接写在 SQL 中，也可以用 ? 占位并通过 params 传入") String sql,
            @ToolParam(description = "按顺序对应 ? 占位符的参数值，没有占位符时传空数组", required = false) List<String> params,
            @ToolParam(description = "这条查询的目的，一句话") String reason,
            @ToolParam(description = "这条查询的结果是否就是回答所依据的最终结果。探索、核对类查询填 false；"
                    + "给出结论前必须有一条 true 的查询，其结果应正好对应回答：问哪一个就只返回那一行，"
                    + "问前 N 名就返回 N 行，问总数就返回总数。", required = false) Boolean isFinalResult
    ) {
        return toJson(backend.runSql(sql, params == null ? List.of() : params, reason, Boolean.TRUE.equals(isFinalResult)));
    }

    @Tool(name = SEARCH_DOCS, description = "在当前用户上传的业务资料（管理制度、操作规范、指标口径等）中检索相关段落。"
            + "问题涉及公司规定、标准、阈值、审批或流程时，先用它查到规则，再用 run_sql 按规则查数据。")
    public String searchDocs(@ToolParam(description = "检索内容，尽量具体，例如「近效期的定义」「盘点差异复核标准」") String query) {
        return toJson(backend.searchDocs(query));
    }

    @Tool(name = ASK_USER, description = "问题缺少关键条件（例如没说是哪个商品、哪个客户）且无法合理推断时，"
            + "向用户提出一个澄清问题。调用后本轮分析结束，等待用户回复。能用合理默认值查询的问题不要调用。")
    public String askUser(@ToolParam(description = "要问用户的问题") String question) {
        return question;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "结果序列化失败: " + ex.getMessage());
            return String.valueOf(error);
        }
    }
}
