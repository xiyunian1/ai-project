package com.example.myai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按「baseUrl + 模型 + Key」创建并缓存 Spring AI ChatModel。
 * 前端可以按请求传入不同的 OpenAI 兼容模型配置，所以不能用 starter 的单例 Bean。
 */
@Component
public class AgentChatModelFactory {

    private static final int MAX_CACHED_MODELS = 32;

    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ChatModel get(String baseUrl, String apiKey, String model, long timeoutSeconds) {
        String key = baseUrl + "|" + model + "|" + timeoutSeconds + "|" + sha256(apiKey);
        if (cache.size() > MAX_CACHED_MODELS) {
            cache.clear();
        }
        return cache.computeIfAbsent(key, k -> create(baseUrl, apiKey, model, timeoutSeconds));
    }

    private ChatModel create(String baseUrl, String apiKey, String model, long timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)));

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .apiKey(apiKey)
                // 与原有实现一致：baseUrl 已包含版本路径（如 https://api.openai.com/v1），这里只追加 /chat/completions
                .completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.1).build())
                // 默认重试 10 次会把单次故障放大成分钟级等待，这里只重试一次
                .retryTemplate(RetryTemplate.builder().maxAttempts(2).fixedBackoff(1000).build())
                .build();
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return String.valueOf(value == null ? 0 : value.hashCode());
        }
    }
}
