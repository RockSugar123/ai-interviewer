package com.aiinterviewer.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 首批三个工具（FR-5）。阶段 2 只定契约 + stub 实现：
 * 题库检索 / 简历要点提取在阶段 4 由 RAG 管线替换真实现（接口签名不变）；
 * 回答评分 stub 返回“未接入”标记，模型会基于对话自行评估，阶段 5 报告链路再启用真评分。
 * 工具永不抛异常（失败兜底原则），异常就地降级为可读的失败返回值。
 */
@Slf4j
@Component
public class InterviewTools {

    private static final String STUB_TAG = "[STUB 阶段4替换为RAG真实现]";

    @Tool(description = "检索面试题库，返回针对指定主题与难度的候选面试题列表。" +
            "topic 为考察主题（如 Redis、Spring Boot、JVM），difficulty 为难度（基础/进阶/深挖）。")
    public List<String> searchQuestionBank(
            @ToolParam(description = "考察主题") String topic,
            @ToolParam(description = "难度：基础/进阶/深挖") String difficulty) {
        log.info("[Tool] searchQuestionBank topic={} difficulty={} {}", topic, difficulty, STUB_TAG);
        try {
            return switch (difficulty == null ? "" : difficulty) {
                case "深挖" -> List.of(
                        topic + "：请讲一个你排查过的最棘手的线上问题，从现象到根因到修复完整复盘",
                        topic + "：如果把这个组件的量级放大 100 倍，当前方案会在哪里先崩？怎么改？");
                case "基础" -> List.of(
                        topic + "：它的核心要解决什么问题？一句话概括",
                        topic + "：说说你实际用过它的哪个特性");
                default -> List.of(
                        topic + "：结合你的项目，说说这个技术在其中的角色与取舍",
                        topic + "：它和同类方案的对比，你为什么选它",
                        topic + "：讲讲你踩过的一个坑以及如何避免");
            };
        } catch (Exception e) {
            log.error("[Tool] searchQuestionBank 失败", e);
            return List.of("（题库检索暂不可用，请基于你的经验直接出题）");
        }
    }

    @Tool(description = "提取当前候选人的简历要点（项目经历、技能栈、亮点、可深挖点）。" +
            "阶段 4 将替换为从候选人上传简历的 RAG 检索真实现。")
    public List<String> extractResumePoints() {
        log.info("[Tool] extractResumePoints {} ", STUB_TAG);
        try {
            return List.of(
                    "三年 Java 后端经验，主力语言 Java，熟悉 Spring Boot、MySQL、Redis",
                    "做过电商秒杀系统：涉及 RocketMQ 异步削峰、库存超卖防护",
                    "近期在做 AI 模拟面试官项目：SSE 流式、Agent 编排、RAG（在学）",
                    "可深挖点：秒杀的库存一致性方案、MQ 消息可靠性、Redis 缓存设计");
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
        log.info("[Tool] scoreAnswer questionLen={} answerLen={} {}", nullSafeLen(question), nullSafeLen(answer), STUB_TAG);
        return "{\"available\":false,\"note\":\"评分能力尚未接入，请基于对话内容自行判断回答质量\"}";
    }

    private int nullSafeLen(String s) {
        return s == null ? 0 : s.length();
    }
}
