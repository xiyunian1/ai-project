package com.example.myai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    @Value("${dify.api-key:}")
    private String apiKey;

    @Value("${dify.base-url:http://localhost:8080/v1}")
    private String baseUrl;

    @Value("${dify.mock-enabled:true}")
    private boolean mockEnabled;

    @Value("${dify.mock-delay-ms:500}")
    private long mockDelayMs;

    @Value("${dify.request-timeout-seconds:20}")
    private long requestTimeoutSeconds;

    private final WebClient webClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ChatService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!mockEnabled && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("dify.api-key must be configured when dify.mock-enabled=false");
        }
        if (mockEnabled) {
            log.warn("Dify mock mode is enabled. Disable it in production by setting DIFY_MOCK_ENABLED=false.");
        }
    }

    public Flux<String> chatStream(String query, String userId) {
        Map<String, Object> body = buildChatBody(query, true, userId);

        return webClient.post()
                .uri(baseUrl + "/chat-messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        Mono.error(new RuntimeException("Dify API stream request failed: " + response.statusCode()))
                )
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .filter(chunk -> chunk != null && !chunk.trim().isEmpty())
                .map(this::normalizeStreamChunk)
                .onErrorResume(ex -> fallbackStream(query, userId, ex));
    }

    public Mono<String> chat(String query, String userId) {
        Map<String, Object> body = buildChatBody(query, false, userId);

        return webClient.post()
                .uri(baseUrl + "/chat-messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        Mono.error(new RuntimeException("Dify API blocking request failed: " + response.statusCode()))
                )
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .map(this::extractAnswer)
                .onErrorResume(ex -> fallbackBlocking(query, userId, ex));
    }

    public Mono<String> chatWithImage(MultipartFile image, String query, String userId) {
        return Mono.fromCallable(() -> {
            String safeUser = resolveUserId(userId);
            String fileId = uploadFileToDify(image, safeUser);

            Map<String, Object> body = buildChatBody(
                    query == null || query.isBlank() ? "请分析这张图片" : query,
                    false,
                    safeUser
            );

            List<Map<String, Object>> files = new ArrayList<>();
            Map<String, Object> fileInfo = new HashMap<>();
            fileInfo.put("type", "image");
            fileInfo.put("transfer_method", "local_file");
            fileInfo.put("upload_file_id", fileId);
            files.add(fileInfo);
            body.put("files", files);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> responseEntity = restTemplate.postForEntity(
                    baseUrl + "/chat-messages",
                    new org.springframework.http.HttpEntity<>(body, headers),
                    String.class
            );

            String responseBody = responseEntity.getBody() == null ? "" : responseEntity.getBody();
            return extractAnswer(responseBody);
        }).timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .onErrorResume(ex -> fallbackImage(query, userId, ex));
    }

    private Flux<String> fallbackStream(String query, String userId, Throwable ex) {
        String safeUser = resolveUserId(userId);
        log.error("Dify stream failed, user={}, query={}", safeUser, abbreviate(query), ex);

        if (mockEnabled) {
            return getMockStreamResponse(query);
        }

        return Flux.error(new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务暂不可用，请稍后重试"));
    }

    private Mono<String> fallbackBlocking(String query, String userId, Throwable ex) {
        String safeUser = resolveUserId(userId);
        log.error("Dify blocking failed, user={}, query={}", safeUser, abbreviate(query), ex);

        if (mockEnabled) {
            return Mono.just(
                    "Dify API 当前不可用，已切换到本地模拟回复。\n"
                            + "你的问题是：" + query
            );
        }

        return Mono.error(new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务暂不可用，请稍后重试"));
    }

    private Mono<String> fallbackImage(String query, String userId, Throwable ex) {
        String safeUser = resolveUserId(userId);
        log.error("Dify image failed, user={}, query={}", safeUser, abbreviate(query), ex);

        if (mockEnabled) {
            return Mono.just("图片处理失败（模拟模式）：请稍后重试或检查 Dify 配置");
        }

        return Mono.error(new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务暂不可用，请稍后重试"));
    }

    private Map<String, Object> buildChatBody(String query, boolean streaming, String userId) {
        Map<String, Object> body = new HashMap<>();
        body.put("inputs", new HashMap<>());
        body.put("query", query == null ? "" : query.trim());
        body.put("response_mode", streaming ? "streaming" : "blocking");
        body.put("user", resolveUserId(userId));
        body.put("conversation_id", "");
        return body;
    }

    private String normalizeStreamChunk(String chunk) {
        String trimmed = chunk.trim();
        if (trimmed.startsWith("data:")) {
            return trimmed.substring(5).trim();
        }
        return chunk;
    }

    private Flux<String> getMockStreamResponse(String query) {
        String[] responses = {
                "你好，我是 WMS 智能助手。",
                "你刚刚的问题是：" + query,
                "当前 Dify 服务不可用，下面是模拟流式响应。",
                "你可以继续联调前端流式渲染逻辑。",
                "待 Dify 恢复后会自动返回真实内容。",
                "[DONE]"
        };

        long delay = Math.max(mockDelayMs, 10);
        return Flux.interval(Duration.ofMillis(delay))
                .take(responses.length)
                .map(i -> responses[i.intValue()]);
    }

    private String uploadFileToDify(MultipartFile image, String userId) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(image.getBytes()) {
            @Override
            public String getFilename() {
                return image.getOriginalFilename();
            }
        });
        body.add("user", userId);

        ResponseEntity<String> responseEntity = restTemplate.postForEntity(
                baseUrl + "/files/upload",
                new org.springframework.http.HttpEntity<>(body, headers),
                String.class
        );

        String responseBody = responseEntity.getBody() == null ? "" : responseEntity.getBody();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String fileId = root.path("id").asText("");
            if (fileId.isEmpty()) {
                fileId = root.path("file_id").asText("");
            }
            if (fileId.isEmpty()) {
                throw new IOException("File upload returned empty id: " + responseBody);
            }
            return fileId;
        } catch (Exception e) {
            throw new IOException("Failed to parse file upload response", e);
        }
    }

    private String extractAnswer(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            String content = root.path("content").asText("");
            if (!content.isEmpty()) {
                return content;
            }

            JsonNode workflow = root.path("workflow");
            if (!workflow.isMissingNode()) {
                content = workflow.path("content").asText("");
                if (!content.isEmpty()) {
                    return content;
                }

                JsonNode outputs = workflow.path("outputs");
                if (!outputs.isMissingNode() && !outputs.isNull()) {
                    return outputs.toString();
                }
            }

            JsonNode message = root.path("message");
            if (!message.isMissingNode()) {
                content = message.path("content").asText("");
                if (!content.isEmpty()) {
                    return content;
                }
            }

            return responseBody;
        } catch (Exception e) {
            return responseBody;
        }
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
