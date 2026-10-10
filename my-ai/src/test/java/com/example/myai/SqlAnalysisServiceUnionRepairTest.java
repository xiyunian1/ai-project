package com.example.myai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SqlAnalysisServiceUnionRepairTest {

    private final SqlAnalysisService service = new SqlAnalysisService(
            mock(JdbcTemplate.class),
            WebClient.builder(),
            new ObjectMapper()
    );

    @Test
    void tryRepairSql_wrapsUnionBranchesThatUseTopAndOrderBy() throws Exception {
        String sql = """
                SELECT TOP 5 商品名称, 现有库存
                FROM inout
                ORDER BY 现有库存 ASC
                UNION ALL
                SELECT TOP 5 商品名称, 现有库存
                FROM inout
                ORDER BY 现有库存 DESC
                """;

        String repaired = invokeTryRepairSql(sql, new RuntimeException("关键字 'UNION' 附近有语法错误。"));

        assertTrue(repaired.contains("AS union_branch_1"));
        assertTrue(repaired.contains("AS union_branch_2"));
        assertTrue(repaired.contains("UNION ALL"));
        assertTrue(repaired.contains("SELECT * FROM ("));
    }

    @Test
    void tryRepairSql_skipsPlainUnionWithoutBranchOrderBy() throws Exception {
        String sql = """
                SELECT 商品名称
                FROM inout
                WHERE 现有库存 < 10
                UNION ALL
                SELECT 商品名称
                FROM inout
                WHERE 现有库存 > 100
                """;

        String repaired = invokeTryRepairSql(sql, new RuntimeException("关键字 'UNION' 附近有语法错误。"));

        assertEquals("", repaired);
    }

    @Test
    void tryRepairSql_rewritesOffsetFetchToTopWhenOffsetIsZero() throws Exception {
        String sql = """
                SELECT 商品名称, 现有库存
                FROM inout
                ORDER BY 现有库存 DESC
                OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY
                """;

        String repaired = invokeTryRepairSql(sql, new RuntimeException("'OFFSET' 附近有语法错误。"));

        assertTrue(repaired.contains("SELECT TOP 5 商品名称, 现有库存"));
        assertTrue(repaired.contains("ORDER BY 现有库存 DESC"));
        assertTrue(!repaired.toLowerCase().contains("offset"));
    }

    @Test
    void tryRepairSql_rewritesLabelStarUnionBranchesToXmlDetail() throws Exception {
        String sql = """
                WITH a AS (
                    SELECT TOP 1 商品编号, 商品名称
                    FROM inout
                ),
                b AS (
                    SELECT TOP 1 商品编号, 商品名称, 商品规格
                    FROM inout
                )
                SELECT 'a' AS analysis_type, * FROM a
                UNION ALL
                SELECT 'b' AS analysis_type, * FROM b
                """;

        String repaired = invokeTryRepairSql(
                sql,
                new RuntimeException("使用 UNION、INTERSECT 或 EXCEPT 运算符合并的所有查询必须在其目标列表中有相同数目的表达式。")
        );

        assertTrue(repaired.contains("FOR XML RAW('row'), ROOT('rows')"));
        assertTrue(repaired.contains("AS detail"));
        assertTrue(repaired.contains("SELECT 'a' AS analysis_type"));
        assertTrue(repaired.contains("SELECT 'b' AS analysis_type"));
    }

    @Test
    void tryRepairSql_rewritesPlainStarUnionBranchesToXmlDetail() throws Exception {
        String sql = """
                WITH top_negative_inventory AS (
                    SELECT TOP 1 商品编号, 商品名称
                    FROM inout
                ),
                recent_expired AS (
                    SELECT TOP 1 商品编号, 商品名称, 有效期至
                    FROM inout
                )
                SELECT * FROM top_negative_inventory
                UNION ALL
                SELECT * FROM recent_expired
                """;

        String repaired = invokeTryRepairSql(
                sql,
                new RuntimeException("使用 UNION、INTERSECT 或 EXCEPT 运算符合并的所有查询必须在其目标列表中有相同数目的表达式。")
        );

        assertTrue(repaired.contains("SELECT 'top_negative_inventory' AS analysis_type"));
        assertTrue(repaired.contains("SELECT 'recent_expired' AS analysis_type"));
        assertTrue(repaired.contains("FOR XML RAW('row'), ROOT('rows')"));
        assertTrue(repaired.contains("AS detail"));
    }

    @Test
    void tryRepairSql_rewritesTopStarUnionBranchesAndStripsFinalOrderBy() throws Exception {
        String sql = """
                WITH negative_inventory AS (
                    SELECT 商品编号, 商品名称, 库存数量
                    FROM dbo.inout
                ),
                imbalance_records AS (
                    SELECT 商品编号, 商品名称, 入库数量
                    FROM aisql.dbo.inout
                )
                SELECT TOP 20 * FROM negative_inventory
                UNION ALL
                SELECT TOP 20 * FROM imbalance_records
                ORDER BY 库存数量 DESC
                """;

        String repaired = invokeTryRepairSql(
                sql,
                new RuntimeException("使用 UNION、INTERSECT 或 EXCEPT 运算符合并的所有查询必须在其目标列表中有相同数目的表达式。")
        );

        assertTrue(repaired.contains("SELECT 'negative_inventory' AS analysis_type"));
        assertTrue(repaired.contains("SELECT TOP 20 * FROM negative_inventory FOR XML"));
        assertTrue(repaired.contains("SELECT TOP 20 * FROM imbalance_records FOR XML"));
        assertTrue(!repaired.contains("ORDER BY 库存数量 DESC"));
    }

    @Test
    void tryRepairSql_rewritesTextColumnCastToIntComparison() throws Exception {
        String sql = """
                SELECT TOP 10 商品编号, 商品名称, 现有库存
                FROM inout
                WHERE CAST(商品编号 AS INT) = 4
                ORDER BY 现有库存 DESC
                """;

        String repaired = invokeTryRepairSql(
                sql,
                new RuntimeException("在将 nvarchar 值 'TMLY4' 转换成数据类型 int 时失败。")
        );

        assertTrue(repaired.contains("CONVERT(NVARCHAR(4000), 商品编号) = N'4'"));
        assertTrue(!repaired.toLowerCase().contains("cast(商品编号 as int)"));
    }

    @Test
    void tryRepairSql_rewritesKnownTextColumnComparedWithNumber() throws Exception {
        String sql = """
                SELECT TOP 10 商品编号, 商品名称, 现有库存
                FROM inout
                WHERE 商品编号 = 4
                ORDER BY 现有库存 DESC
                """;

        String repaired = invokeTryRepairSql(
                sql,
                new RuntimeException("在将 varchar 值 'TMLY4' 转换成数据类型 int 时失败。")
        );

        assertTrue(repaired.contains("CONVERT(NVARCHAR(4000), 商品编号) = N'4'"));
        assertTrue(!repaired.contains("商品编号 = 4"));
    }

    @Test
    void agentPromptWarnsAgainstCastingBusinessTextCodesToNumbers() throws Exception {
        Method method = SqlAnalysisService.class.getDeclaredMethod("buildAgentSystemPrompt", String.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(service, "- inout: 商品编号, 商品名称, 现有库存");

        assertTrue(prompt.contains("商品编号"));
        assertTrue(prompt.contains("do not cast"));
        assertTrue(prompt.contains("business codes"));
    }

    private String invokeTryRepairSql(String sql, RuntimeException ex) throws Exception {
        Method method = SqlAnalysisService.class.getDeclaredMethod("tryRepairSql", String.class, RuntimeException.class);
        method.setAccessible(true);
        return (String) method.invoke(service, sql, ex);
    }
}
