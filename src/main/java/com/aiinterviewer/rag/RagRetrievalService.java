package com.aiinterviewer.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 召回 + 重排（FR-12）。三条检索路径共用同一套：向量召回（阈值过滤）→ gte-rerank 重排 → 截断。
 * 重排失败自动降级为向量序；任何异常向上抛给调用方各自的兜底（工具永不抛异常原则在工具层落地）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    private static final FilterExpressionTextParser FILTER_PARSER = new FilterExpressionTextParser();

    private final SimpleVectorStore vectorStore;
    private final RerankClient rerankClient;
    private final RagProperties props;

    /** 生成链路：仅当会话挂载简历时检索其简历块（会话级隔离，引用溯源用） */
    public List<RetrievedChunk> retrieveForGeneration(Long resumeFileId, String query) {
        if (resumeFileId == null) {
            return List.of();
        }
        return recall(null, query, "resumeFileId == " + resumeFileId, props.finalTopK());
    }

    /** 简历要点提取工具：topic 为空时用通用要点查询；未挂载简历返回空 */
    public List<RetrievedChunk> retrieveResumePoints(Long resumeFileId, String topic, int topK) {
        if (resumeFileId == null) {
            return List.of();
        }
        String query = topic == null || topic.isBlank()
                ? "项目经历 工作经历 专业技能 核心项目 亮点"
                : topic;
        return recall(null, query, "resumeFileId == " + resumeFileId, topK);
    }

    /** 题库检索工具：topic 语义召回 + difficulty 元数据过滤 */
    public List<RetrievedChunk> retrieveQuestionBank(String topic, String difficulty, int topK) {
        String expr = normalizeDifficulty(difficulty) == null
                ? "source == '" + RetrievedChunk.SOURCE_BANK + "'"
                : "source == '" + RetrievedChunk.SOURCE_BANK + "' && difficulty == '"
                        + normalizeDifficulty(difficulty) + "'";
        return recall(null, topic, expr, topK);
    }

    /** 是否存在题库（种子幂等判断） */
    public boolean questionBankSeeded() {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("探针")
                .topK(1)
                .filterExpression(FILTER_PARSER.parse(
                        "source == '" + RetrievedChunk.SOURCE_BANK + "'"))
                .build());
        return hits != null && !hits.isEmpty();
    }

    /** 默认路径：召回 recallTopK → 重排 → finalTopK */
    private List<RetrievedChunk> recall(Long userId, String query, String filterExpr) {
        return recall(userId, query, filterExpr, props.finalTopK());
    }

    private List<RetrievedChunk> recall(Long userId, String query, String filterExpr, int topK) {
        String expr = filterExpr;
        if (userId != null) {
            String userCond = "userId == " + userId;
            expr = expr == null ? userCond : expr + " && " + userCond;
        }
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(props.recallTopK())
                .similarityThreshold(props.minScore());
        if (expr != null) {
            builder.filterExpression(FILTER_PARSER.parse(expr));
        }
        List<Document> hits = vectorStore.similaritySearch(builder.build());
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> recalled = hits.stream().map(this::toChunk).toList();
        List<RetrievedChunk> ordered = rerank(query, recalled);
        return ordered.size() > topK ? ordered.subList(0, topK) : ordered;
    }

    /** 重排（若可用），失败返回向量序 */
    private List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks) {
        List<Integer> indices = rerankClient.rerank(query,
                chunks.stream().map(RetrievedChunk::text).toList(), chunks.size());
        if (indices == null) {
            return new ArrayList<>(chunks);
        }
        List<RetrievedChunk> ordered = new ArrayList<>(chunks.size());
        for (Integer i : indices) {
            if (i >= 0 && i < chunks.size()) {
                ordered.add(chunks.get(i));
            }
        }
        // 防御：rerank 返回下标不完整时补上遗漏项
        for (int i = 0; i < chunks.size(); i++) {
            if (!ordered.contains(chunks.get(i))) {
                ordered.add(chunks.get(i));
            }
        }
        return ordered;
    }

    private RetrievedChunk toChunk(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        double score = doc.getScore() == null ? 0 : doc.getScore();
        return new RetrievedChunk(
                doc.getId(),
                doc.getText() == null ? "" : doc.getText(),
                String.valueOf(meta.getOrDefault("source", "")),
                meta.get("section") == null ? null : String.valueOf(meta.get("section")),
                meta.get("topic") == null ? null : String.valueOf(meta.get("topic")),
                score);
    }

    private String normalizeDifficulty(String difficulty) {
        if (difficulty == null) {
            return null;
        }
        if (difficulty.contains("基础")) {
            return "基础";
        }
        if (difficulty.contains("深挖")) {
            return "深挖";
        }
        if (difficulty.contains("进阶") || difficulty.contains("中等") || difficulty.contains("常规")) {
            return "进阶";
        }
        return null;
    }
}
