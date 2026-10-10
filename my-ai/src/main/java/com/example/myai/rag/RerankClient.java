package com.example.myai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序（rerank）接口客户端，兼容 SiliconFlow / Jina / Cohere 风格的 POST /v1/rerank。
 * Spring AI 1.1 没有通用的 rerank 抽象，这里直接调用 HTTP 接口。
 */
public class RerankClient {

    public record Ranked(int index, double score) {
    }

    private final RestClient restClient;
    private final String model;

    public RerankClient(String baseUrl, String apiKey, String model, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();
        this.model = model;
    }

    public List<Ranked> rerank(String query, List<String> documents, int topN) {
        if (documents.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", Math.min(topN, documents.size()));
        body.put("return_documents", false);
        JsonNode response = restClient.post()
                .uri("/v1/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        List<Ranked> ranked = new ArrayList<>();
        if (response != null) {
            for (JsonNode item : response.path("results")) {
                ranked.add(new Ranked(item.path("index").asInt(), item.path("relevance_score").asDouble()));
            }
        }
        return ranked;
    }
}
