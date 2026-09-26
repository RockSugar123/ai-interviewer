package com.aiinterviewer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回答评分（FR-5 首批工具之一，阶段 5 真实现）：单次低温结构化调用，对单条问答输出三维评分。
 * 供面试官模型在对话中按需调用（scoreAnswer 工具），失败返回 unavailable 标记（工具永不抛异常原则）。
 */
@Slf4j
@Service
public class AnswerScoreService {

    private static final String SCORE_SYSTEM = """
            你是技术面试评分器。对候选人的回答输出 JSON 评分，只输出 JSON，不要输出其他内容：
            {"depth": 1-10 的技术深度, "structure": 1-10 的表达结构, "correctness": 1-10 的正确性,
             "brief": "一句话点评（不超过60字）", "missed": "关键遗漏点（不超过60字，没有则空字符串）"}
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public AnswerScoreService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public String score(String question, String answer) {
        try {
            AnswerScore s = chatClient.prompt()
                    .system(SCORE_SYSTEM)
                    .user("【问题】\n" + question + "\n\n【候选人回答】\n" + answer)
                    .options(OpenAiChatOptions.builder().temperature(0.1).build())
                    .call()
                    .entity(AnswerScore.class);
            if (s == null) {
                throw new IllegalStateException("评分输出为空");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", true);
            out.put("depth", s.depth());
            out.put("structure", s.structure());
            out.put("correctness", s.correctness());
            out.put("brief", s.brief());
            out.put("missed", s.missed());
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.error("[Score] 回答评分失败，返回 unavailable [qLen={} aLen={}]",
                    nullSafeLen(question), nullSafeLen(answer), e);
            return "{\"available\":false,\"note\":\"评分暂不可用，请基于对话内容自行判断回答质量\"}";
        }
    }

    /** 评分结果（模型结构化输出） */
    public record AnswerScore(int depth, int structure, int correctness, String brief, String missed) {
    }

    private int nullSafeLen(String s) {
        return s == null ? 0 : s.length();
    }
}
