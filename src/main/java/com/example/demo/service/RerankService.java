package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-encoder rerank over an HTTP API.
 *
 * Speaks the de-facto standard /rerank contract shared by SiliconFlow,
 * Jina and Cohere: request {model, query, documents[], top_n} and response
 * {results: [{index, relevance_score}]}. Any failure returns an empty list
 * so retrieval can fall back to the fusion ordering.
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final boolean enabled;
    private final String model;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RerankService(@Value("${rag.retrieval.rerank.api.enabled:false}") boolean enabled,
                         @Value("${rag.retrieval.rerank.api.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
                         @Value("${rag.retrieval.rerank.api.api-key:}") String apiKey,
                         @Value("${rag.retrieval.rerank.api.model:BAAI/bge-reranker-v2-m3}") String model,
                         @Value("${rag.retrieval.rerank.api.timeout-ms:4000}") int timeoutMs,
                         ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(500, timeoutMs)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(500, timeoutMs)));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl == null ? "" : baseUrl.strip())
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isEnabled() {
        return enabled && !apiKey.isBlank();
    }

    public record RerankHit(int index, double relevanceScore) {
    }

    /**
     * @return hits ordered by relevance descending; empty when disabled,
     *         on invalid input, or on any API failure.
     */
    public List<RerankHit> rerank(String query, List<String> documents) {
        if (!isEnabled() || query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", documents.size());
            body.put("return_documents", false);

            String response = restClient.post()
                    .uri("/rerank")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response == null ? "{}" : response).path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.warn("Rerank API returned no results; falling back to fusion order.");
                return List.of();
            }
            List<RerankHit> hits = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= documents.size()) {
                    continue;
                }
                hits.add(new RerankHit(index, item.path("relevance_score").asDouble(0.0)));
            }
            return hits;
        } catch (Exception ex) {
            log.warn("Rerank API call failed; falling back to fusion order. reason={}", ex.getMessage());
            return List.of();
        }
    }
}
