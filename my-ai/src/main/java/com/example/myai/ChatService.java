package com.example.myai;

import com.example.myai.rag.KnowledgeIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_KNOWLEDGE_CONTEXT_CHARS = 18_000;
    private static final int MAX_SINGLE_DOCUMENT_CONTEXT_CHARS = 6_000;
    private static final ThreadLocal<SqlAnalysisService.ModelConnectionConfig> MODEL_CONTEXT = new ThreadLocal<>();
    /** 评测用：覆盖资料上下文方式，"stuff" 为整篇拼接，其余取 KnowledgeIndexService.MODES 中的检索模式。 */
    private static final ThreadLocal<String> KNOWLEDGE_MODE = new ThreadLocal<>();
    private static final ThreadLocal<KnowledgeContext> LAST_KNOWLEDGE_CONTEXT = new ThreadLocal<>();

    @Value("${llm.api-key:${deepseek.api-key:}}")
    private String apiKey;

    @Value("${llm.base-url:${deepseek.base-url:https://api.deepseek.com}}")
    private String baseUrl;

    @Value("${llm.model:${deepseek.model:deepseek-chat}}")
    private String model;

    @Value("${llm.request-timeout-seconds:20}")
    private long requestTimeoutSeconds;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeIndexService knowledgeIndexService;

    public ChatService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeIndexService knowledgeIndexService
    ) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    /** 资料上下文：mode 为 stuff 或检索模式；chunks 为检索命中的分块（stuff 时为空）。 */
    public record KnowledgeContext(String mode, String text, List<KnowledgeIndexService.RetrievedChunk> chunks) {
    }

    /**
     * 非流式问答并返回可评测的轨迹：使用的资料上下文方式、命中的分块、上下文长度、token 用量和耗时。
     */
    public Map<String, Object> answerWithTrace(
            String query,
            String userId,
            SqlAnalysisService.ModelConnectionConfig modelConfig,
            String knowledgeMode
    ) {
        long started = System.nanoTime();
        KNOWLEDGE_MODE.set(knowledgeMode);
        try {
            return withModelConfig(modelConfig, () -> {
                Map<String, Object> result = new LinkedHashMap<>();
                String effectiveApiKey = resolveApiKey();
                if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "API Key 不能为空，请先填写模型 API Key");
                }
                Map<String, Object> body = buildChatBody(query, userId, false);
                KnowledgeContext context = LAST_KNOWLEDGE_CONTEXT.get();
                String responseBody = webClient.post()
                        .uri(resolveBaseUrl() + "/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + effectiveApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(Math.max(requestTimeoutSeconds, 60)))
                        .block();
                result.put("answer", extractAnswer(responseBody));
                result.put("knowledgeMode", context == null ? "" : context.mode());
                result.put("contextChars", context == null ? 0 : context.text().length());
                result.put("chunks", context == null ? List.of() : context.chunks().stream().map(c -> {
                    Map<String, Object> chunk = new LinkedHashMap<>();
                    chunk.put("fileName", c.fileName());
                    chunk.put("heading", c.heading());
                    chunk.put("score", c.score());
                    chunk.put("content", c.content());
                    return chunk;
                }).toList());
                try {
                    JsonNode usage = objectMapper.readTree(responseBody).path("usage");
                    result.put("promptTokens", usage.path("prompt_tokens").asLong(0));
                    result.put("completionTokens", usage.path("completion_tokens").asLong(0));
                } catch (Exception ignored) {
                    // 用量缺失不影响结果
                }
                result.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
                return result;
            });
        } finally {
            KNOWLEDGE_MODE.remove();
            LAST_KNOWLEDGE_CONTEXT.remove();
        }
    }

    public Flux<String> chatStream(String query, String userId) {
        String effectiveApiKey = resolveApiKey();
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            return Flux.error(new ApiException(HttpStatus.BAD_REQUEST, "API Key 不能为空，请先填写模型 API Key"));
        }
        Map<String, Object> body = buildChatBody(query, userId, true);
        LAST_KNOWLEDGE_CONTEXT.remove();

        return webClient.post()
                .uri(resolveBaseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + effectiveApiKey)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        Mono.error(new RuntimeException("LLM API stream request failed: " + response.statusCode()))
                )
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .filter(chunk -> chunk != null && !chunk.trim().isEmpty())
                .map(this::normalizeStreamChunk)
                .onErrorResume(ex -> handleStreamFailure(query, userId, ex));
    }

    public Flux<String> chatStream(String query, String userId, SqlAnalysisService.ModelConnectionConfig modelConfig) {
        return withModelConfig(modelConfig, () -> chatStream(query, userId));
    }

    public Mono<String> chat(String query, String userId) {
        String effectiveApiKey = resolveApiKey();
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "API Key 不能为空，请先填写模型 API Key"));
        }
        Map<String, Object> body = buildChatBody(query, userId, false);
        LAST_KNOWLEDGE_CONTEXT.remove();

        return webClient.post()
                .uri(resolveBaseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + effectiveApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        Mono.error(new RuntimeException("LLM API blocking request failed: " + response.statusCode()))
                )
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .map(this::extractAnswer)
                .onErrorResume(ex -> handleBlockingFailure(query, userId, ex));
    }

    public Mono<String> chatWithImage(MultipartFile image, String query, String userId) {
        resolveUserId(userId);
        return Mono.just("当前 OpenAI 兼容文本模型演示路径暂不支持图片分析，请使用文字描述图片内容或切换到文本问答。");
    }

    public List<String> listModels(String requestBaseUrl, String requestApiKey) {
        String effectiveApiKey = firstNonBlank(requestApiKey, apiKey);
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "API Key 不能为空，请先填写模型 API Key");
        }

        String effectiveBaseUrl = firstNonBlank(requestBaseUrl, baseUrl, "https://api.deepseek.com");
        try {
            String responseBody = webClient.get()
                    .uri(trimTrailingSlash(effectiveBaseUrl) + "/models")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + effectiveApiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            Mono.error(new RuntimeException("Model list request failed: " + response.statusCode()))
                    )
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .block();
            return extractModelIds(responseBody);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Failed to load model list from configured LLM service", ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "获取模型列表失败，请检查 Base URL 和 API Key");
        }
    }

    private Flux<String> handleStreamFailure(String query, String userId, Throwable ex) {
        String safeUser = resolveUserId(userId);
        log.error("LLM stream failed, user={}, query={}", safeUser, abbreviate(query), ex);

        return Flux.error(new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务暂不可用，请稍后重试"));
    }

    private Mono<String> handleBlockingFailure(String query, String userId, Throwable ex) {
        String safeUser = resolveUserId(userId);
        log.error("LLM blocking failed, user={}, query={}", safeUser, abbreviate(query), ex);

        return Mono.error(new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务暂不可用，请稍后重试"));
    }

    private Map<String, Object> buildChatBody(String query, String userId, boolean streaming) {
        Map<String, Object> body = new HashMap<>();
        String effectiveModel = resolveModel();
        body.put("model", effectiveModel == null || effectiveModel.isBlank() ? "deepseek-chat" : effectiveModel.trim());
        body.put("stream", streaming);
        body.put("messages", List.of(
                Map.of("role", "system", "content", buildSystemPrompt(userId, query)),
                Map.of("role", "user", "content", query == null ? "" : query.trim())
        ));
        return body;
    }

    private String buildSystemPrompt(String userId, String query) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 WMS 智能仓储辅助决策系统的中文助手。\n");
        prompt.append("优先依据用户在系统配置中上传的知识文档回答。\n");
        prompt.append("如果知识文档中没有相关依据，请明确说明：配置文档中没有找到相关信息。不要编造文档不存在的内容。\n");

        KnowledgeContext context = buildKnowledgeContext(userId, query);
        LAST_KNOWLEDGE_CONTEXT.set(context);
        if (!context.text().isBlank()) {
            if (!context.chunks().isEmpty()) {
                prompt.append("下面是从知识文档中检索到的相关片段。回答时在对应句子后用 [来源n] 标注依据。\n");
            }
            prompt.append("\n<knowledge_documents>\n");
            prompt.append(context.text());
            prompt.append("\n</knowledge_documents>");
        }
        return prompt.toString();
    }

    private KnowledgeContext buildKnowledgeContext(String userId, String query) {
        String username = Optional.ofNullable(userId).orElse("").trim();
        String requested = KNOWLEDGE_MODE.get();
        boolean useRetrieval = !"stuff".equals(requested) && knowledgeIndexService.isReady() && !username.isBlank();
        if (!useRetrieval) {
            return new KnowledgeContext("stuff", buildFullDocumentContext(userId), List.of());
        }
        String mode = requested != null && KnowledgeIndexService.MODES.contains(requested) ? requested : knowledgeIndexService.defaultMode();
        List<KnowledgeIndexService.RetrievedChunk> chunks;
        try {
            chunks = knowledgeIndexService.search(username, query, mode, knowledgeIndexService.defaultTopK());
        } catch (Exception ex) {
            log.warn("Knowledge retrieval failed, falling back to full-document context", ex);
            return new KnowledgeContext("stuff", buildFullDocumentContext(userId), List.of());
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            text.append("[来源").append(i + 1).append("] ").append(chunks.get(i).content()).append("\n\n");
        }
        return new KnowledgeContext(mode, text.toString().trim(), chunks);
    }

    /** 原实现：按更新时间把整篇文档依次拼进上下文，单篇和总长度截断。向量库不可用时作为回退。 */
    private String buildFullDocumentContext(String userId) {
        String username = Optional.ofNullable(userId).orElse("").trim();
        if (username.isBlank()) {
            return "";
        }

        List<KnowledgeDocument> documents = knowledgeDocumentRepository.findByUsernameOrderByUpdatedAtDesc(username);
        StringBuilder context = new StringBuilder();
        for (KnowledgeDocument document : documents) {
            if (context.length() >= MAX_KNOWLEDGE_CONTEXT_CHARS) {
                break;
            }
            String content = Optional.ofNullable(document.getTextContent()).orElse("").trim();
            if (content.isBlank()) {
                continue;
            }
            if (content.length() > MAX_SINGLE_DOCUMENT_CONTEXT_CHARS) {
                content = content.substring(0, MAX_SINGLE_DOCUMENT_CONTEXT_CHARS) + "\n...(document truncated)";
            }

            String block = "[文档] " + document.getFileName() + "\n" + content + "\n\n";
            int remaining = MAX_KNOWLEDGE_CONTEXT_CHARS - context.length();
            if (block.length() > remaining) {
                context.append(block, 0, remaining);
                break;
            }
            context.append(block);
        }
        return context.toString().trim();
    }

    private String normalizeStreamChunk(String chunk) {
        String trimmed = chunk.trim();
        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.substring(5).trim();
        }
        if ("[DONE]".equals(trimmed)) {
            return trimmed;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                String content = choices.get(0).path("delta").path("content").asText("");
                if (!content.isEmpty()) {
                    return content;
                }
            }
        } catch (Exception ex) {
            return trimmed;
        }
        return "";
    }

    private String extractAnswer(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                String content = choices.get(0).path("message").path("content").asText("");
                if (!content.isEmpty()) {
                    return content;
                }
            }

            return responseBody;
        } catch (Exception e) {
            return responseBody;
        }
    }

    private String resolveBaseUrl() {
        SqlAnalysisService.ModelConnectionConfig config = currentModelConfig();
        return trimTrailingSlash(firstNonBlank(
                config == null ? null : config.baseUrl(),
                config == null ? null : config.deepseekBaseUrl(),
                baseUrl,
                "https://api.deepseek.com"
        ));
    }

    private String resolveApiKey() {
        SqlAnalysisService.ModelConnectionConfig config = currentModelConfig();
        return firstNonBlank(
                config == null ? null : config.apiKey(),
                config == null ? null : config.deepseekApiKey(),
                apiKey
        );
    }

    private String resolveModel() {
        SqlAnalysisService.ModelConnectionConfig config = currentModelConfig();
        return firstNonBlank(
                config == null ? null : config.model(),
                config == null ? null : config.deepseekModel(),
                model
        );
    }

    private SqlAnalysisService.ModelConnectionConfig currentModelConfig() {
        SqlAnalysisService.ModelConnectionConfig config = MODEL_CONTEXT.get();
        if (config == null || !Boolean.TRUE.equals(config.enabled())) {
            return null;
        }
        return config;
    }

    private <T> T withModelConfig(SqlAnalysisService.ModelConnectionConfig modelConfig, java.util.function.Supplier<T> action) {
        if (modelConfig != null) {
            MODEL_CONTEXT.set(modelConfig);
        }
        try {
            return action.get();
        } finally {
            MODEL_CONTEXT.remove();
        }
    }

    private List<String> extractModelIds(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<String> models = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("").trim();
                if (!id.isBlank() && !models.contains(id)) {
                    models.add(id);
                }
            }
            return models;
        } catch (Exception ex) {
            log.warn("Failed to parse model list response", ex);
            return List.of();
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com";
        }
        return value.trim().replaceAll("/+$", "");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String resolveUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "anonymous_user";
        }
        return userId.trim();
    }

    private String abbreviate(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.length() <= 64) {
            return trimmed;
        }
        return trimmed.substring(0, 64) + "...";
    }
}
