package com.aiinterviewer.rag;

import com.aiinterviewer.infra.persistence.entity.ResumeFile;
import com.aiinterviewer.infra.persistence.mapper.ResumeFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引写入侧（FR-10/11）：简历索引（Tika 解析 → 结构感知分块 → embedding → 向量库）与题库播种。
 * 阶段 5 起简历索引由 MQ 驱动（ResumeIndexListener，单线程串行消费），MQ 停用时由 ResumeService 同步直调。
 * 失败一律落 resume_file.error_msg，不静默（吞异常=欠债）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIndexService {

    private static final FilterExpressionTextParser FILTER_PARSER = new FilterExpressionTextParser();

    private final SimpleVectorStore vectorStore;
    private final ResumeTextSplitter splitter;
    private final ResumeFileMapper resumeFileMapper;
    private final RagProperties props;

    /**
     * 简历索引：PENDING → RUNNING → DONE/FAILED。
     * 失败分类（FR-15 配套）：确定性失败（文件不存在/解析为空等 IllegalStateException）落 FAILED 后正常返回
     * （消费者 ACK，MQ 不重试——重试无意义）；其余异常（embedding 网络超时/限流等瞬时失败）落 FAILED 后抛出
     * （消费者异常 → RocketMQ 退避重试 → 耗尽进死信，用户可重新上传或经手动触发恢复）。
     *
     * synchronized：消费线程默认 20，SimpleVectorStore 并发写/落盘必须串行（与 seedQuestionBankIfEmpty 同风格，
     * 单机部署进程内锁足够；starter 2.3.1 注解不支持设置 consumeThreadMin，不能走"单线程消费"方案）。
     */
    public synchronized void indexResume(Long resumeFileId) {
        ResumeFile file = resumeFileMapper.selectById(resumeFileId);
        if (file == null) {
            log.warn("[RAG] 简历记录不存在，跳过索引 [id={}]", resumeFileId);
            return;
        }
        updateStatus(file, ResumeFile.STATUS_RUNNING, null);
        try {
            Path path = Path.of(file.getFilePath());
            if (!Files.exists(path)) {
                throw new IllegalStateException("上传文件不存在: " + path);
            }
            // Tika 统一解析 PDF/DOCX → 纯文本（读出的文档取首篇全文）
            TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(path.toFile()));
            List<Document> parsed = reader.get();
            if (parsed.isEmpty() || parsed.get(0).getText() == null
                    || parsed.get(0).getText().isBlank()) {
                throw new IllegalStateException("未解析出文本内容（可能是扫描件或不支持的排版）");
            }
            String text = parsed.get(0).getText();

            Map<String, Object> baseMeta = new HashMap<>();
            baseMeta.put("userId", file.getUserId());
            baseMeta.put("resumeFileId", file.getId());
            baseMeta.put("source", RetrievedChunk.SOURCE_RESUME);
            List<Document> chunks = splitter.split(text, baseMeta);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("分块结果为空");
            }
            // 幂等：先清同简历旧块（重复上传场景），再写入
            deleteResumeVectors(file.getUserId(), file.getId());
            vectorStore.add(chunks);
            saveStore();
            updateStatus(file, ResumeFile.STATUS_DONE, null);
            log.info("[RAG] 简历索引完成 [id={} chunks={} chars={}]", file.getId(), chunks.size(), text.length());
        } catch (IllegalStateException e) {
            // 确定性失败：重试无意义，落 FAILED 即 ACK
            updateStatus(file, ResumeFile.STATUS_FAILED, abbreviate(e.getMessage()));
            log.error("[RAG] 简历索引确定性失败（不重试） [id={} path={}]", file.getId(), file.getFilePath(), e);
        } catch (Exception e) {
            // 瞬时失败：落 FAILED 后抛出 → MQ 退避重试
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            updateStatus(file, ResumeFile.STATUS_FAILED, abbreviate(reason));
            log.error("[RAG] 简历索引瞬时失败（MQ 将退避重试） [id={} path={}]", file.getId(), file.getFilePath(), e);
            throw new IllegalStateException("简历索引失败（将由 MQ 退避重试）: " + reason, e);
        }
    }

    /** 删除简历对应向量块（尽力而为；物理文件与记录由 ResumeService 处理） */
    public void deleteResumeVectors(Long userId, Long resumeFileId) {
        try {
            String expr = "resumeFileId == " + resumeFileId + " && userId == " + userId;
            var filter = FILTER_PARSER.parse(expr);
            int deleted = 0;
            while (true) {
                // topK=200 只是单页上限：块数超一页时分批删净（每轮删完下一轮必然更少，保证终止）
                List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                        .query("resume")
                        .topK(200)
                        .filterExpression(filter)
                        .build());
                if (hits == null || hits.isEmpty()) {
                    break;
                }
                vectorStore.delete(hits.stream().map(Document::getId).toList());
                deleted += hits.size();
                if (hits.size() < 200) {
                    break;
                }
            }
            if (deleted > 0) {
                saveStore();
                log.info("[RAG] 简历向量已清理 [userId={} resumeFileId={} count={}]", userId, resumeFileId, deleted);
            }
        } catch (Exception e) {
            log.warn("[RAG] 简历向量清理失败（不影响删除流程） [resumeFileId={}]", resumeFileId, e);
        }
    }

    /** 题库播种（幂等：已有题库块则跳过），由 QuestionBankSeeder 启动时调用 */
    public synchronized void seedQuestionBankIfEmpty(List<Document> bankDocs) {
        if (bankDocs.isEmpty()) {
            return;
        }
        if (hasBankChunk()) {
            log.info("[RAG] 题库已存在，跳过播种");
            return;
        }
        vectorStore.add(bankDocs);
        saveStore();
        log.info("[RAG] 题库播种完成 [count={}]", bankDocs.size());
    }

    private boolean hasBankChunk() {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("探针")
                .topK(1)
                .filterExpression(FILTER_PARSER.parse(
                        "source == '" + RetrievedChunk.SOURCE_BANK + "'"))
                .build());
        return hits != null && !hits.isEmpty();
    }

    private void updateStatus(ResumeFile file, String status, String errorMsg) {
        ResumeFile update = new ResumeFile();
        update.setId(file.getId());
        update.setParseStatus(status);
        update.setErrorMsg(errorMsg);
        resumeFileMapper.updateById(update);
    }

    private void saveStore() {
        try {
            File target = new File(props.storePath());
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("[RAG] 向量库目录创建失败 [path={}]", parent);
            }
            vectorStore.save(target);
        } catch (Exception e) {
            log.warn("[RAG] 向量库落盘失败（重启后会重播种/需重传简历）", e);
        }
    }

    private String abbreviate(String s) {
        return s.length() <= 480 ? s : s.substring(0, 480) + "…";
    }
}
