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
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SqlNl2SqlAgentService {
    private static final Logger log = LoggerFactory.getLogger(SqlNl2SqlAgentService.class);

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("(?s)\\{.*}");
    private static final Pattern TABLE_REF_PATTERN = Pattern.compile("(?i)\\b(?:from|join)\\s+([\\[\\]A-Za-z0-9_.]+)");

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate", "merge",
            "grant", "revoke", "exec", "execute", "xp_", "sp_", "backup", "restore"
    );

    @Value("${nl2sql.enabled:true}")
    private boolean enabled;

    @Value("${nl2sql.max-rows:200}")
    private int maxRows;

    @Value("${nl2sql.allowed-tables:dbo.inout,aisql.dbo.inout,inout}")
    private String allowedTablesConfig;

    @Value("${nl2sql.provider:dify}")
    private String provider;

    @Value("${dify.api-key:}")
    private String apiKey;

    @Value("${dify.base-url:http://localhost:8080/v1}")
    private String baseUrl;

    @Value("${dify.request-timeout-seconds:20}")
    private long requestTimeoutSeconds;

    @Value("${dify.mock-enabled:false}")
    private boolean mockEnabled;

    @Value("${deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Value("${deepseek.model:deepseek-chat}")
    private String deepseekModel;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final InoutRepository inoutRepository;

    public SqlNl2SqlAgentService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            InoutRepository inoutRepository
    ) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.inoutRepository = inoutRepository;
    }

    public SqlChatService.SqlChatResult tryQuery(String userQuery) {
        if (!enabled) return null;
        if (mockEnabled) return null;

        AgentPlan plan = requestPlan(userQuery);
        if (plan == null) return null;

        if (plan.error != null && !plan.error.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NL2SQL cannot resolve this request: " + plan.error);
        }

        String sql = normalizeSql(plan.sql);
        if (sql.isBlank()) return null;

        List<Object> params = plan.params == null ? List.of() : plan.params;

        validateReadOnlySql(sql);
        validateTableReferences(sql);

        List<Map<String, Object>> rows = executeQuery(sql, params);
        String answer = buildAnswer(sql, params, rows, plan.reason);
        return new SqlChatService.SqlChatResult(
                answer,
                toSqlPreview(sql, params),
                rows.size(),
                rows
        );
    }

    private AgentPlan requestPlan(String userQuery) {
        String providerName = normalizeProvider(provider);
        if ("deepseek".equals(providerName)) {
            return requestPlanByDeepSeek(userQuery);
        }
        return requestPlanByDify(userQuery);
    }

    private AgentPlan requestPlanByDify(String userQuery) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String schemaHint = buildSchemaHint();
        String prompt = buildPrompt(userQuery, schemaHint);

        Map<String, Object> body = new HashMap<>();
        body.put("inputs", Map.of());
        body.put("query", prompt);
        body.put("response_mode", "blocking");
        body.put("user", "nl2sql_agent");
        body.put("conversation_id", "");

        String responseBody;
        try {
            responseBody = webClient.post()
                    .uri(baseUrl + "/chat-messages")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            Mono.error(new RuntimeException("NL2SQL LLM request failed: " + response.statusCode()))
                    )
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .block();
        } catch (Exception ex) {
            log.warn("NL2SQL planner is unavailable, fallback to rule-based parser", ex);
            return null;
        }

        String answerText = extractDifyAnswerText(responseBody);
        return parseAgentPlan(answerText, "dify");
    }

    private AgentPlan requestPlanByDeepSeek(String userQuery) {
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            return null;
        }

        String schemaHint = buildSchemaHint();
        String prompt = buildPrompt(userQuery, schemaHint);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", deepseekModel);
        body.put("temperature", 0);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content", "You are a strict NL2SQL planner. Output one JSON object only."
                ),
                Map.of(
                        "role", "user",
                        "content", prompt
                )
        ));

        String responseBody;
        try {
            responseBody = webClient.post()
                    .uri(deepseekBaseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + deepseekApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            Mono.error(new RuntimeException("DeepSeek NL2SQL request failed: " + response.statusCode()))
                    )
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .block();
        } catch (Exception ex) {
            log.warn("DeepSeek NL2SQL planner is unavailable, fallback to rule-based parser", ex);
            return null;
        }

        String answerText = extractDeepSeekAnswerText(responseBody);
        return parseAgentPlan(answerText, "deepseek");
    }

    private String normalizeProvider(String rawProvider) {
        if (rawProvider == null || rawProvider.isBlank()) {
            return "dify";
        }
        String value = rawProvider.trim().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(value) || "dify".equals(value)) {
            return value;
        }
        log.warn("Unknown nl2sql.provider={}, fallback to dify", rawProvider);
        return "dify";
    }

    private AgentPlan parseAgentPlan(String answerText, String providerName) {
        if (answerText == null || answerText.isBlank()) {
            return null;
        }

        String jsonText = extractJsonObject(answerText);
        if (jsonText.isBlank()) {
            log.warn("{} NL2SQL planner output is not JSON. output={}", providerName, abbreviate(answerText, 300));
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(jsonText);
            String sql = root.path("sql").asText("");
            String reason = root.path("reason").asText("");
            String error = root.path("error").asText("");
            List<Object> params = parseParams(root.path("params"));
            return new AgentPlan(sql, params, reason, error);
        } catch (Exception ex) {
            log.warn("Failed to parse {} NL2SQL planner JSON: {}", providerName, abbreviate(jsonText, 300), ex);
            return null;
        }
    }

    private String buildPrompt(String userQuery, String schemaHint) {
        return """
                You are an NL2SQL planner for Microsoft SQL Server.
                Output MUST be a single JSON object and nothing else.

                JSON schema:
                {
                  "sql": "SELECT ... WHERE col = ?",
                  "params": ["value1", 123],
                  "reason": "short explanation",
                  "error": ""
                }

                Rules:
                1) Read-only SQL only. Use SELECT/CTE. Never output INSERT/UPDATE/DELETE/DDL/EXEC.
                2) Use '?' placeholders for all user values. Put values into params in order.
                3) Keep SQL deterministic and concise.
                4) If query is ambiguous or impossible, set sql="" and fill error.
                5) Prefer SQL Server syntax (TOP N, [] identifiers if needed).
                6) Allowed tables are restricted by the backend; avoid unrelated tables.
                7) For aggregate expressions like COUNT/SUM/AVG/MIN/MAX, add explicit alias names (example: COUNT(*) AS total_count).

                """ + schemaHint + """

                User question:
                """ + userQuery;
    }

    private String buildSchemaHint() {
        Set<String> allowed = getAllowedTablesNormalized();
        StringBuilder sb = new StringBuilder();
        sb.append("Known table metadata:\n");
        for (String table : allowed) {
            List<String> cols = readColumnsForTable(table);
            if (cols.isEmpty()) {
                sb.append("- ").append(table).append(": columns unavailable\n");
            } else {
                sb.append("- ").append(table).append(": ").append(String.join(", ", cols)).append("\n");
            }
        }
        try {
            sb.append("Current DB: ").append(inoutRepository.currentDatabaseName()).append("\n");
        } catch (Exception ignore) {
        }
        return sb.toString();
    }

    private List<String> readColumnsForTable(String normalizedTable) {
        String sqlExpr = toSqlIdentifier(normalizedTable);
        String sql = "SELECT TOP 0 * FROM " + sqlExpr;
        try {
            return jdbcTemplate.query(sql, (ResultSet rs) -> {
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

    private List<Map<String, Object>> executeQuery(String sql, List<Object> params) {
        return jdbcTemplate.query(con -> {
            var ps = con.prepareStatement(sql);
            ps.setMaxRows(Math.max(1, maxRows));
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
        Matcher matcher = TABLE_REF_PATTERN.matcher(sql);
        Set<String> referenced = new LinkedHashSet<>();

        while (matcher.find()) {
            String raw = matcher.group(1);
            String normalized = normalizeTableRef(raw);
            if (!normalized.isBlank()) {
                referenced.add(normalized);
            }
        }

        if (referenced.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No table reference found in generated SQL.");
        }

        for (String table : referenced) {
            if (!allowed.contains(table)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Table not allowed: " + table);
            }
        }
    }

    private boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(text)
                .find();
    }

    private Set<String> getAllowedTablesNormalized() {
        Set<String> allowed = new LinkedHashSet<>();
        String[] parts = allowedTablesConfig == null ? new String[0] : allowedTablesConfig.split(",");
        for (String part : parts) {
            String normalized = normalizeTableRef(part);
            if (!normalized.isBlank()) {
                allowed.add(normalized);
            }
        }
        if (allowed.isEmpty()) {
            allowed.add("dbo.inout");
            allowed.add("aisql.dbo.inout");
            allowed.add("inout");
        }
        return allowed;
    }

    private String normalizeTableRef(String raw) {
        if (raw == null) return "";
        String table = raw.trim();
        if (table.isBlank()) return "";

        // remove alias: "dbo.inout i" -> "dbo.inout"
        int space = table.indexOf(' ');
        if (space > 0) {
            table = table.substring(0, space);
        }

        table = table.replace("[", "").replace("]", "").replace("`", "").replace("\"", "");
        return table.toLowerCase(Locale.ROOT);
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

    private String normalizeSql(String sql) {
        if (sql == null) return "";
        String cleaned = sql.trim();
        if (cleaned.isBlank()) return "";
        // strip trailing semicolon just in case
        while (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private String extractDifyAnswerText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String answer = root.path("answer").asText("");
            if (!answer.isBlank()) return answer;
            String content = root.path("content").asText("");
            if (!content.isBlank()) return content;
            JsonNode message = root.path("message");
            if (!message.isMissingNode()) {
                content = message.path("content").asText("");
                if (!content.isBlank()) return content;
            }
            return responseBody;
        } catch (Exception ex) {
            return responseBody;
        }
    }

    private String extractDeepSeekAnswerText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                String content = message.path("content").asText("");
                if (!content.isBlank()) {
                    return content;
                }
            }
            return responseBody;
        } catch (Exception ex) {
            return responseBody;
        }
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

    private String buildAnswer(String sql, List<Object> params, List<Map<String, Object>> rows, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("NL2SQL agent executed successfully.\n");
        if (reason != null && !reason.isBlank()) {
            sb.append("reason: ").append(reason.trim()).append("\n");
        }
        sb.append("rows: ").append(rows.size()).append("\n");
        if (rows.isEmpty()) {
            sb.append("No matching rows.");
            return sb.toString();
        }

        if (rows.size() == 1 && rows.get(0).size() == 1) {
            Object value = rows.get(0).values().iterator().next();
            sb.append("value: ").append(value == null ? "null" : value);
            return sb.toString();
        }

        sb.append("\n");
        sb.append(toMarkdown(rows, 12));
        sb.append("\n");
        sb.append("sql: ").append(sql);
        if (!params.isEmpty()) {
            sb.append("\nparams: ").append(params);
        }
        return sb.toString();
    }

    private String toMarkdown(List<Map<String, Object>> rows, int maxPreviewRows) {
        if (rows.isEmpty()) return "";

        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append("| ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(escapeMd(cols.get(i)));
        }
        sb.append(" |\n| ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append("---");
        }
        sb.append(" |\n");

        int limit = Math.min(rows.size(), Math.max(1, maxPreviewRows));
        for (int r = 0; r < limit; r++) {
            Map<String, Object> row = rows.get(r);
            sb.append("| ");
            for (int c = 0; c < cols.size(); c++) {
                if (c > 0) sb.append(" | ");
                Object value = row.get(cols.get(c));
                sb.append(escapeMd(value == null ? "null" : String.valueOf(value)));
            }
            sb.append(" |\n");
        }
        if (rows.size() > limit) {
            sb.append("\n... ").append(rows.size() - limit).append(" more rows");
        }
        return sb.toString();
    }

    private String toSqlPreview(String sql, List<Object> params) {
        if (params == null || params.isEmpty()) return sql;
        return sql + " /* params=" + params + " */";
    }

    private String escapeMd(String raw) {
        if (raw == null) return "";
        return raw.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private static final class AgentPlan {
        private final String sql;
        private final List<Object> params;
        private final String reason;
        private final String error;

        private AgentPlan(String sql, List<Object> params, String reason, String error) {
            this.sql = sql;
            this.params = params == null ? Collections.emptyList() : params;
            this.reason = reason;
            this.error = error;
        }
    }
}
