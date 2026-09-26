package com.aiinterviewer.config;

import com.aiinterviewer.rag.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * 向量库（阶段 4）：SimpleVectorStore，启动时加载既有 JSON 落盘（若有）。
 * 写入方（索引服务）负责在增量后调用 save() 持久化；加载失败视为空库重建（向量可再生，不阻断启动）。
 */
@Slf4j
@Configuration
public class RagConfig {

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel, RagProperties properties) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = new File(properties.storePath());
        if (file.exists()) {
            try {
                store.load(file);
                log.info("[RAG] 向量库落盘已加载 [path={}]", file);
            } catch (Exception e) {
                log.warn("[RAG] 向量库落盘加载失败，按空库启动（题库会重新播种，简历可重新索引） [path={}]", file, e);
            }
        }
        return store;
    }
}
