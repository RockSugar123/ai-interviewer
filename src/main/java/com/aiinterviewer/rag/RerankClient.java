package com.aiinterviewer.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DashScope gte-rerank-v2 重排客户端（FR-12）。原生 rerank API（非 OpenAI 协议），
 * 与聊天/Embedding 共用同一 API key。任何失败返回 null，调用方降级为向量序——
 * 重排是增强项不是依赖项（失败兜底原则）。
 */
@Slf4j
@Component
public class RerankClient {

    private static final String MODEL = "gte-rerank-v2";

    private final RestClient restClient;
    private final boolean enabled;

    public RerankClient(@Value("${interview.rag.rerank-base-url}") String baseUrl,
                        @Value("${spring.ai.openai.api-key}") String apiKey,
                        @Value("${interview.rag.rerank-enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    /** 重排结果：按相关性降序的文档下标列表；不可用/失败返回 null */
    @SuppressWarnings("unchecked")
    public List<Integer> rerank(String query, List<String> documents, int topN) {
        if (!enabled) {
            return null;
        }
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/api/v1/services/rerank/text-rerank/text-rerank")
                    .body(Map.of(
                            "model", MODEL,
                            "input", Map.of("query", query, "documents", documents),
                            "parameters", Map.of("return_documents", false, "top_n", topN)))
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("output") == null) {
                return null;
            }
            Map<String, Object> output = (Map<String, Object>) response.get("output");
            List<Map<String, Object>> results =
                    (List<Map<String, Object>>) output.getOrDefault("results", List.of());
            List<Integer> indices = new ArrayList<>();
            for (Map<String, Object> r : results) {
                Object index = r.get("index");
                if (index instanceof Number n) {
                    indices.add(n.intValue());
                }
            }
            return indices;
        } catch (Exception e) {
            // 超时/配额/网络：快速降级，不重试（重排只在召回增强链路上）
            log.warn("[RAG] rerank 调用失败，降级为向量序: {}", e.getMessage());
            return null;
        }
    }
}
