package com.aiinterviewer.agent.tools;

import com.aiinterviewer.rag.RagProperties;
import com.aiinterviewer.rag.RagRetrievalService;
import com.aiinterviewer.rag.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 面试官工具（FR-5）。阶段 4 起：题库检索/简历要点提取接入 RAG 真实现（阶段 2 的 stub 契约兑现，
 * @Tool 描述与调用方语义不变）；回答评分仍为 stub，阶段 5 报告链路启用。
 *
 * 不再是单例 Bean：每次生成按候选人实例化（工具需要 userId 做归属检索）。
 * 工具永不抛异常（失败兜底原则），异常就地降级为可读的失败返回值。
 */
@Slf4j
public class InterviewTools {

    private final RagRetrievalService retrieval;
    private final RagProperties ragProps;
    /** 本轮生成对应的候选人（RAG 归属键）与其会话挂载的简历（会话级隔离） */
    private final long userId;
    private final Long resumeFileId;

    public InterviewTools(RagRetrievalService retrieval, RagProperties ragProps,
                          long userId, Long resumeFileId) {
        this.retrieval = retrieval;
        this.ragProps = ragProps;
        this.userId = userId;
        this.resumeFileId = resumeFileId;
    }

    @Tool(description = "检索面试题库，返回针对指定主题与难度的候选面试题列表。" +
            "topic 为考察主题（如 Redis、Spring Boot、JVM），difficulty 为难度（基础/进阶/深挖）。")
    public List<String> searchQuestionBank(
            @ToolParam(description = "考察主题") String topic,
            @ToolParam(description = "难度：基础/进阶/深挖") String difficulty) {
        log.info("[Tool] searchQuestionBank topic={} difficulty={} userId={}", topic, difficulty, userId);
        if (!ragProps.enabled()) {
            return List.of("（题库检索未开启，请基于你的经验直接出题）");
        }
        try {
            List<RetrievedChunk> chunks = retrieval.retrieveQuestionBank(topic, difficulty, 5);
            if (chunks.isEmpty()) {
                return List.of("（题库中未找到该主题的高相关问题，请基于你的经验直接出题）");
            }
            return chunks.stream()
                    .map(c -> "[" + (c.topic() == null ? topic : c.topic())
                            + "·" + (c.section() == null ? "题库" : c.section()) + "] " + c.text())
                    .toList();
        } catch (Exception e) {
            log.error("[Tool] searchQuestionBank 失败", e);
            return List.of("（题库检索暂不可用，请基于你的经验直接出题）");
        }
    }

    @Tool(description = "提取当前候选人的简历要点（项目经历、技能栈、亮点、可深挖点）。" +
            "topic 为可选的聚焦主题（如 Redis、秒杀项目），传入时优先返回与主题相关的简历内容。")
    public List<String> extractResumePoints(
            @ToolParam(description = "聚焦主题，可为空", required = false) String topic) {
        log.info("[Tool] extractResumePoints topic={} userId={}", topic, userId);
        if (!ragProps.enabled()) {
            return List.of("（简历要点提取未开启，请基于对话内容直接提问）");
        }
        try {
            if (resumeFileId == null) {
                return List.of("（本场面试未挂载简历，请基于对话内容与 JD 直接提问）");
            }
            List<RetrievedChunk> chunks = retrieval.retrieveResumePoints(resumeFileId, topic, 8);
            if (chunks.isEmpty()) {
                return List.of("（候选人尚未上传简历，请基于对话内容与 JD 直接提问）");
            }
            return chunks.stream()
                    .map(c -> "[" + c.display() + "] " + c.text())
                    .toList();
        } catch (Exception e) {
            log.error("[Tool] extractResumePoints 失败", e);
            return List.of("（简历要点提取暂不可用，请基于对话内容直接提问）");
        }
    }

    @Tool(description = "对候选人的回答进行结构化评分（技术深度、表达结构、正确性）。" +
            "当前为 stub，返回未接入标记，收到此标记时应基于对话内容自行评估。")
    public String scoreAnswer(
            @ToolParam(description = "向候选人提出的问题") String question,
            @ToolParam(description = "候选人的回答") String answer) {
        log.info("[Tool] scoreAnswer questionLen={} answerLen={} (stub)",
                nullSafeLen(question), nullSafeLen(answer));
        return "{\"available\":false,\"note\":\"评分能力尚未接入，请基于对话内容自行判断回答质量\"}";
    }

    private int nullSafeLen(String s) {
        return s == null ? 0 : s.length();
    }
}
