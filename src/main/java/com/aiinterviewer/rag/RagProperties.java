package com.aiinterviewer.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG 管线配置（FR-10/11/12）。
 * 向量库：SimpleVectorStore（内存 + JSON 落盘，千级 chunk 足够；Spring AI VectorStore 接口统一，
 * 数据量上来可平移 RedisVectorStore/pgvector，业务代码不动）。
 * Embedding：DashScope text-embedding-v4，1024 维（与聊天模型同 base-url/key，OpenAI 兼容 /v1/embeddings）。
 */
@ConfigurationProperties("interview.rag")
public record RagProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("./data/rag-store.json") String storePath,
        @DefaultValue("./data/uploads") String uploadDir,
        /** 向量召回条数（重排前） */
        @DefaultValue("20") int recallTopK,
        /** 重排/注入最终条数 */
        @DefaultValue("5") int finalTopK,
        /** 相似度阈值，低于该值的结果丢弃 */
        @DefaultValue("0.4") double minScore,
        /** 是否调用 gte-rerank-v2 重排（失败自动降级为向量序） */
        @DefaultValue("true") boolean rerankEnabled,
        @DefaultValue("https://dashscope.aliyuncs.com") String rerankBaseUrl,
        /** 注入生成 prompt 的资料字符预算 */
        @DefaultValue("2400") int maxInjectChars) {
}
