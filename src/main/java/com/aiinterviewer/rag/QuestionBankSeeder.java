package com.aiinterviewer.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库种子（启动时）：读取 resources/rag/question-bank.json（开发期 LLM 生成、人工校对后入库），
 * 每题一块写入向量库（元数据 source/topic/difficulty）。幂等：已有题库则跳过。
 * 播种只发生一次（首启或落盘损坏后），embedding 耗时分钟级以内可接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionBankSeeder implements ApplicationRunner {

    private final RagIndexService indexService;
    private final RagProperties props;
    private final ObjectMapper objectMapper;

    record QuestionSeed(String topic, String difficulty, String question) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.enabled()) {
            log.info("[RAG] 功能未开启，跳过题库播种");
            return;
        }
        try {
            List<QuestionSeed> seeds = objectMapper.readValue(
                    new ClassPathResource("rag/question-bank.json").getInputStream(),
                    new TypeReference<List<QuestionSeed>>() {
                    });
            if (seeds.isEmpty()) {
                log.warn("[RAG] 题库种子文件为空");
                return;
            }
            List<Document> docs = new ArrayList<>(seeds.size());
            for (int i = 0; i < seeds.size(); i++) {
                QuestionSeed seed = seeds.get(i);
                if (seed.question() == null || seed.question().isBlank()) {
                    continue;
                }
                Map<String, Object> meta = new HashMap<>();
                meta.put("source", RetrievedChunk.SOURCE_BANK);
                meta.put("topic", seed.topic());
                meta.put("section", seed.difficulty());
                meta.put("difficulty", seed.difficulty());
                Document doc = new Document("qb:" + i, seed.question(), meta);
                docs.add(doc);
            }
            long start = System.currentTimeMillis();
            indexService.seedQuestionBankIfEmpty(docs);
            log.info("[RAG] 题库种子检查/播种耗时 {}ms [questions={}]", System.currentTimeMillis() - start, docs.size());
        } catch (Exception e) {
            // 种子失败不阻断启动：searchQuestionBank 工具会走降级提示
            log.error("[RAG] 题库播种失败（工具将降级为提示语）", e);
        }
    }
}
