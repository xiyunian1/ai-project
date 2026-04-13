package com.example.myai;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SqlChatService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int DEFAULT_LOW_STOCK_THRESHOLD = 10;
    private static final int MAX_LIMIT = 50;

    private static final Pattern TOP_PATTERN = Pattern.compile("(?i)(?:top|前)\\s*(\\d{1,3})");
    private static final Pattern LOW_THRESHOLD_PATTERN = Pattern.compile("(?:低于|小于|少于|不足|<=|<)\\s*(\\d{1,6})");
    private static final Pattern BELOW_THRESHOLD_PATTERN = Pattern.compile("(\\d{1,6})\\s*(?:以下|以内|及以下)");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("[\"“”'‘’]([^\"“”'‘’]{1,64})[\"“”'‘’]");
    private static final Pattern STOCK_TARGET_PATTERN = Pattern.compile("([\\p{IsHan}A-Za-z0-9_\\-]{2,64})\\s*(?:的)?\\s*库存");

    // e.g. "aisql的表inout"
    private static final Pattern DB_TABLE_PATTERN = Pattern.compile("(?i)([A-Za-z_][A-Za-z0-9_]*)\\s*(?:库)?\\s*的\\s*表\\s*([A-Za-z_][A-Za-z0-9_]*)");
    // e.g. "表inout"
    private static final Pattern TABLE_ONLY_PATTERN = Pattern.compile("(?i)表\\s*([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static final String[] KEYWORD_STOP_WORDS = new String[]{
            "帮我", "请", "一下", "查一下", "查下", "查", "查询", "看看", "看下",
            "商品", "货品", "物料", "库存", "的", "有多少", "是多少", "多少", "信息", "数据"
    };

    private final InoutRepository inoutRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SqlNl2SqlAgentService sqlNl2SqlAgentService;

    public SqlChatService(
            InoutRepository inoutRepository,
            JdbcTemplate jdbcTemplate,
            SqlNl2SqlAgentService sqlNl2SqlAgentService
    ) {
        this.inoutRepository = inoutRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.sqlNl2SqlAgentService = sqlNl2SqlAgentService;
    }

    public SqlChatResult query(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "query cannot be empty");
        }

        SqlChatResult agentResult = sqlNl2SqlAgentService.tryQuery(query);
        if (agentResult != null) {
            return agentResult;
        }

        // Rule-based fallback. Kept for resilience when LLM output is unavailable.
        try {
            if (isTotalStockQuery(query)) {
                return queryTotalStock();
            }

            if (isRowCountQuery(query)) {
                return queryRowCount(query);
            }

            if (isLowStockQuery(query)) {
                int threshold = extractThreshold(query, DEFAULT_LOW_STOCK_THRESHOLD);
                int limit = extractTopN(query, DEFAULT_LIMIT);
                return queryLowStock(threshold, limit);
            }

            if (isTopStockQuery(query)) {
                int limit = extractTopN(query, DEFAULT_LIMIT);
                return queryTopStock(limit);
            }

            String keyword = extractKeyword(query);
            if (!keyword.isBlank()) {
                int limit = extractTopN(query, DEFAULT_LIMIT);
                return queryByProductName(keyword, limit);
            }

            return helpResult();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SQL query failed: " + rootMessage(ex));
        }
    }

    private SqlChatResult queryTotalStock() {
        BigDecimal totalStock = safeDecimal(inoutRepository.sumCurrentStock());
        long rowCount = safeLong(inoutRepository.countAllRowsNative());
        long productCount = safeLong(inoutRepository.countDistinctProducts());
        String dbInfo = readDbInfo();

        String answer = "Connected to SQL Server (table inout).\n"
                + dbInfo + "\n\n"
                + "- total stock: " + totalStock.toPlainString() + "\n"
                + "- row count: " + rowCount + "\n"
                + "- distinct products: " + productCount;

        List<Map<String, Object>> rows = List.of(Map.of(
                "totalStock", totalStock,
                "rowCount", rowCount,
                "productCount", productCount
        ));

        return new SqlChatResult(
                answer,
                "SELECT SUM(current_stock), COUNT(*), COUNT(DISTINCT product_name) FROM inout",
                rows.size(),
                rows
        );
    }

    private SqlChatResult queryRowCount(String query) {
        TableTarget target = extractTableTarget(query);
        long rowCount = queryCountByTarget(target);
        String dbInfo = readDbInfo();

        String answer = "Connected to SQL Server.\n"
                + dbInfo + "\n\n"
                + "target: " + target.humanReadable() + "\n"
                + "row count: " + rowCount;

        List<Map<String, Object>> rows = List.of(Map.of(
                "rowCount", rowCount,
                "target", target.humanReadable()
        ));

        return new SqlChatResult(
                answer,
                "SELECT COUNT(*) FROM " + target.sqlExpr(),
                rows.size(),
                rows
        );
    }

    private SqlChatResult queryLowStock(int threshold, int limit) {
        BigDecimal thresholdValue = BigDecimal.valueOf(Math.max(0, threshold));
        int safeLimit = normalizeLimit(limit);

        List<Inout> records = inoutRepository.findLowStockByThreshold(
                thresholdValue,
                PageRequest.of(0, safeLimit)
        );

        String answer;
        if (records.isEmpty()) {
            answer = "Connected to SQL Server.\n\nNo rows with current_stock < " + thresholdValue.toPlainString() + ".";
        } else {
            answer = "Connected to SQL Server.\n\n"
                    + "Found " + records.size() + " low-stock rows (< " + thresholdValue.toPlainString() + "):\n\n"
                    + buildMarkdownTable(records);
        }

        return new SqlChatResult(
                answer,
                "SELECT TOP " + safeLimit + " * FROM inout WHERE current_stock < ? ORDER BY current_stock ASC",
                records.size(),
                toRows(records)
        );
    }

    private SqlChatResult queryTopStock(int limit) {
        int safeLimit = normalizeLimit(limit);
        List<Inout> records = inoutRepository.findTopByCurrentStock(PageRequest.of(0, safeLimit));

        String answer;
        if (records.isEmpty()) {
            answer = "Connected to SQL Server.\n\nNo rows available.";
        } else {
            answer = "Connected to SQL Server.\n\nTop " + safeLimit + " by current_stock:\n\n"
                    + buildMarkdownTable(records);
        }

        return new SqlChatResult(
                answer,
                "SELECT TOP " + safeLimit + " * FROM inout ORDER BY current_stock DESC",
                records.size(),
                toRows(records)
        );
    }

    private SqlChatResult queryByProductName(String keyword, int limit) {
        int safeLimit = normalizeLimit(limit);
        List<Inout> records = inoutRepository.findByProductNameLikeOrderByCurrentStockDesc(
                keyword,
                PageRequest.of(0, safeLimit)
        );

        String answer;
        if (records.isEmpty()) {
            answer = "Connected to SQL Server.\n\nNo rows found for keyword: " + keyword;
        } else {
            answer = "Connected to SQL Server.\n\nFound " + records.size() + " rows for keyword: " + keyword + "\n\n"
                    + buildMarkdownTable(records);
        }

        return new SqlChatResult(
                answer,
                "SELECT TOP " + safeLimit + " * FROM inout WHERE product_name LIKE ? ORDER BY current_stock DESC",
                records.size(),
                toRows(records)
        );
    }

    private SqlChatResult helpResult() {
        String answer = "NL2SQL agent is unavailable, switched to fallback rules.\n\n"
                + "You can ask examples like:\n"
                + "1. 总库存\n"
                + "2. 低于20的库存\n"
                + "3. 库存 top 10\n"
                + "4. 帮我查 aisql 的表 inout 有多少条数据";
        return new SqlChatResult(answer, "--", 0, List.of());
    }

    private boolean isTotalStockQuery(String query) {
        return query.contains("总库存") || query.contains("库存总量") || query.contains("库存合计");
    }

    private boolean isRowCountQuery(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        boolean mentionsRowCount = query.contains("多少条")
                || query.contains("几条")
                || query.contains("条数据")
                || query.contains("记录数")
                || query.contains("总条数")
                || lower.contains("count");
        boolean mentionsTable = lower.contains("inout") || query.contains("表") || query.contains("数据");
        boolean looksLikeFilteredQuery = query.contains("客户编码")
                || query.contains("机构编号")
                || query.contains("供应商")
                || query.contains("日期")
                || lower.contains(" where ")
                || query.contains("并且")
                || query.contains("且");
        return mentionsRowCount && mentionsTable && !looksLikeFilteredQuery;
    }

    private boolean isLowStockQuery(String query) {
        return query.contains("低库存")
                || query.contains("库存预警")
                || query.contains("缺货")
                || query.contains("不足")
                || LOW_THRESHOLD_PATTERN.matcher(query).find()
                || BELOW_THRESHOLD_PATTERN.matcher(query).find();
    }

    private boolean isTopStockQuery(String query) {
        return TOP_PATTERN.matcher(query).find()
                || query.contains("最高库存")
                || query.contains("最多库存")
                || query.contains("库存排行")
                || query.contains("库存排名");
    }

    private int extractThreshold(String query, int defaultValue) {
        Matcher m = LOW_THRESHOLD_PATTERN.matcher(query);
        if (m.find()) {
            return safeParseInt(m.group(1), defaultValue);
        }
        Matcher m2 = BELOW_THRESHOLD_PATTERN.matcher(query);
        if (m2.find()) {
            return safeParseInt(m2.group(1), defaultValue);
        }
        return defaultValue;
    }

    private int extractTopN(String query, int defaultValue) {
        Matcher m = TOP_PATTERN.matcher(query);
        if (m.find()) {
            return safeParseInt(m.group(1), defaultValue);
        }
        return defaultValue;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private int safeParseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String extractKeyword(String query) {
        Matcher quotedMatcher = QUOTED_PATTERN.matcher(query);
        if (quotedMatcher.find()) {
            return cleanKeyword(quotedMatcher.group(1));
        }

        Matcher stockMatcher = STOCK_TARGET_PATTERN.matcher(query);
        if (stockMatcher.find()) {
            return cleanKeyword(stockMatcher.group(1));
        }

        return cleanKeyword(query);
    }

    private String cleanKeyword(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        for (String stopWord : KEYWORD_STOP_WORDS) {
            value = value.replace(stopWord, "");
        }
        value = value.replaceAll("[^\\p{IsHan}A-Za-z0-9_\\-]", "");
        if (value.length() > 64) {
            value = value.substring(0, 64);
        }
        return value;
    }

    private long queryCountByTarget(TableTarget target) {
        String sql = "SELECT COUNT(*) FROM " + target.sqlExpr();
        try {
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            return safeLong(count);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to count " + target.humanReadable() + ": " + rootMessage(ex));
        }
    }

    private TableTarget extractTableTarget(String query) {
        Matcher dbTableMatcher = DB_TABLE_PATTERN.matcher(query);
        if (dbTableMatcher.find()) {
            String db = normalizeIdentifier(dbTableMatcher.group(1));
            String table = normalizeIdentifier(dbTableMatcher.group(2));
            if (db != null && table != null) {
                return new TableTarget("[" + db + "].[dbo].[" + table + "]", db + ".dbo." + table);
            }
        }

        Matcher tableMatcher = TABLE_ONLY_PATTERN.matcher(query);
        if (tableMatcher.find()) {
            String table = normalizeIdentifier(tableMatcher.group(1));
            if (table != null) {
                return new TableTarget("[dbo].[" + table + "]", "dbo." + table);
            }
        }

        return new TableTarget("[dbo].[inout]", "dbo.inout");
    }

    private String normalizeIdentifier(String value) {
        if (value == null) return null;
        String v = value.trim();
        return IDENTIFIER_PATTERN.matcher(v).matches() ? v : null;
    }

    private List<Map<String, Object>> toRows(List<Inout> records) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Inout item : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("productId", item.getProductId());
            row.put("productName", item.getProductName());
            row.put("productSpec", item.getProductSpec());
            row.put("supplierName", item.getSupplierName());
            row.put("docType", item.getDocType());
            row.put("docId", item.getDocId());
            row.put("date", item.getDate());
            row.put("expiryDate", item.getExpiryDate());
            row.put("stockQty", safeDecimal(item.getStockQty()));
            row.put("currentStock", safeDecimal(item.getCurrentStock()));
            rows.add(row);
        }
        return rows;
    }

    private String buildMarkdownTable(List<Inout> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Product | CurrentStock | Supplier | Spec |\n");
        sb.append("|---|---:|---|---|\n");
        for (Inout item : records) {
            sb.append("| ")
                    .append(escapeMd(item.getProductName()))
                    .append(" | ")
                    .append(safeDecimal(item.getCurrentStock()).toPlainString())
                    .append(" | ")
                    .append(escapeMd(item.getSupplierName()))
                    .append(" | ")
                    .append(escapeMd(item.getProductSpec()))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private String escapeMd(String raw) {
        if (raw == null || raw.isBlank()) return "-";
        return raw.replace("|", "\\|");
    }

    private BigDecimal safeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String readDbInfo() {
        try {
            String server = inoutRepository.currentServerName();
            String db = inoutRepository.currentDatabaseName();
            return "- server: " + (server == null ? "-" : server) + "\n"
                    + "- database: " + (db == null ? "-" : db);
        } catch (Exception ex) {
            return "- server/database: unavailable";
        }
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message;
    }

    public record SqlChatResult(
            String answer,
            String sqlTemplate,
            int rowCount,
            List<Map<String, Object>> rows
    ) {
    }

    private record TableTarget(String sqlExpr, String humanReadable) {
    }
}
