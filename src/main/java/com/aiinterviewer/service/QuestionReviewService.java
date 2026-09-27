package com.aiinterviewer.service;

import com.aiinterviewer.agent.InterviewAgentProperties;
import com.aiinterviewer.agent.LlmResilience;
import com.aiinterviewer.dto.MessageResponse;
import com.aiinterviewer.infra.limiter.LlmRateLimiter;
import com.aiinterviewer.infra.persistence.entity.InterviewMessage;
import com.aiinterviewer.infra.persistence.entity.InterviewNote;
import com.aiinterviewer.infra.persistence.mapper.InterviewMessageMapper;
import com.aiinterviewer.infra.persistence.mapper.InterviewNoteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 后台复盘 agent（阶段 7）：把原先生成循环内的阻塞评分工具（AnswerScoreService.scoreAnswer）挪到后台，
 * 候选人回答后主链路立即流式出回复，评分在后台异步完成并加深——每题一次低温结构化调用，
 * 产出三维评分 + 点评 + 遗漏点 + 参考答案，落 interview_note 供报告汇总「逐题复盘」章节。
 *
 * 触发时机：面试官动作到达题目边界（NEXT_QUESTION / SWITCH_TOPIC / WRAP_UP / finish 接口）时，
 * 复盘上一题区间 [上一条素材 seq_end + 1, 边界消息 seq - 1]。
 * 失败兜底：后台失败只记日志丢弃（报告缺该题章节），绝不影响主链路；(session_id, seq_end) 唯一键保证幂等。
 */
@Slf4j
@Service
public class QuestionReviewService {

    private static final String REVIEW_SYSTEM = """
            你是技术面试复盘器。输入是围绕一道面试题的完整对话（面试官可能多次追问），请对这道题输出 JSON 复盘，
            只输出 JSON，不要输出其他内容：
            {"question":"面试官提出的主问题（提炼为一句话）",
             "depth":1-10 的技术深度, "structure":1-10 的表达结构, "correctness":1-10 的正确性,
             "brief":"一句话点评（不超过60字）", "missed":"关键遗漏点（不超过60字，没有则空字符串）",
             "referenceAnswer":"该题参考答案（200字以内要点式，针对候选人回答暴露的薄弱点给出）"}
            """;

    private final ChatClient chatClient;
    /** 降级链备用模型客户端（同 key 同端点，model 经 options 覆盖为 fallbackModel） */
    private final ChatClient fallbackChatClient;
    private final InterviewAgentProperties props;
    private final LlmResilience llmResilience;
    private final LlmRateLimiter rateLimiter;
    private final InterviewNoteMapper noteMapper;
    private final InterviewMessageMapper messageMapper;
    private final ChatContextService chatContextService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor reviewExecutor;

    public QuestionReviewService(ChatClient.Builder chatClientBuilder, InterviewAgentProperties props,
                                 LlmResilience llmResilience, LlmRateLimiter rateLimiter,
                                 InterviewNoteMapper noteMapper, InterviewMessageMapper messageMapper,
                                 ChatContextService chatContextService, ObjectMapper objectMapper,
                                 @Qualifier("reviewExecutor") ThreadPoolTaskExecutor reviewExecutor) {
        this.chatClient = chatClientBuilder.build();
        this.fallbackChatClient = chatClientBuilder.build();
        this.props = props;
        this.llmResilience = llmResilience;
        this.rateLimiter = rateLimiter;
        this.noteMapper = noteMapper;
        this.messageMapper = messageMapper;
        this.chatContextService = chatContextService;
        this.objectMapper = objectMapper;
        this.reviewExecutor = reviewExecutor;
    }

    /** 题目边界触发：异步复盘 [上一条素材 seq_end + 1, boundarySeq - 1] 区间；boundarySeq 为边界消息 seq（不含） */
    public void submitReview(long sessionId, long userId, long boundarySeq) {
        reviewExecutor.execute(() -> reviewSpan(sessionId, userId, boundarySeq));
    }

    /** finish 接口触发：以会话最后一条消息为复盘边界（显式结束时最后一题往往还没到决策边界） */
    public void submitFinalReview(long sessionId, long userId) {
        submitReview(sessionId, userId, messageMapper.selectMaxSeq(sessionId) + 1);
    }

    /** 续场边界：本功能上线前的历史对话无法逐题切分，落一条空素材占位封住区间，复盘从续场消息起算 */
    public void markResumeBoundary(long sessionId, long userId, long boundarySeq) {
        try {
            if (latestNote(sessionId) != null || boundarySeq - 1 < 1) {
                return;
            }
            InterviewNote marker = new InterviewNote();
            marker.setSessionId(sessionId);
            marker.setUserId(userId);
            marker.setSeqStart(1L);
            marker.setSeqEnd(boundarySeq - 1);
            marker.setQuestionText("（续场前的历史对话，未逐题复盘）");
            marker.setAnswerText("");
            marker.setDepth(0);
            marker.setStructure(0);
            marker.setCorrectness(0);
            try {
                noteMapper.insert(marker);
                log.info("[Review] 续场边界已标记 session={} seqEnd={}", sessionId, boundarySeq - 1);
            } catch (DuplicateKeyException e) {
                log.info("[Review] 续场边界已存在，跳过 session={} seqEnd={}", sessionId, boundarySeq - 1);
            }
        } catch (Exception e) {
            log.warn("[Review] 续场边界标记失败（不影响主链路） session={}", sessionId, e);
        }
    }

    public InterviewNote latestNote(long sessionId) {
        return noteMapper.selectOne(new LambdaQueryWrapper<InterviewNote>()
                .eq(InterviewNote::getSessionId, sessionId)
                .orderByDesc(InterviewNote::getSeqEnd)
                .last("LIMIT 1"));
    }

    public List<InterviewNote> listNotes(long sessionId) {
        return noteMapper.selectList(new LambdaQueryWrapper<InterviewNote>()
                .eq(InterviewNote::getSessionId, sessionId)
                .orderByAsc(InterviewNote::getId));
    }

    // ---------- 复盘执行（后台线程） ----------

    private void reviewSpan(long sessionId, long userId, long boundarySeq) {
        try {
            InterviewNote last = latestNote(sessionId);
            long seqStart = last == null ? 1 : last.getSeqEnd() + 1;
            long seqEnd = boundarySeq - 1;
            if (seqEnd < seqStart) {
                return;
            }
            List<MessageResponse> span = chatContextService.list(sessionId, userId, 200, seqStart - 1).stream()
                    .filter(m -> m.seq() != null && m.seq() <= seqEnd)
                    .toList();
            if (span.stream().noneMatch(m -> InterviewMessage.ROLE_USER.equals(m.role()))) {
                return;
            }
            StringBuilder dialogue = new StringBuilder();
            StringBuilder answer = new StringBuilder();
            for (MessageResponse m : span) {
                boolean assistant = InterviewMessage.ROLE_ASSISTANT.equals(m.role());
                dialogue.append(assistant ? "面试官: " : "候选人: ").append(m.content()).append('\n');
                if (!assistant) {
                    answer.append(m.content()).append('\n');
                }
            }
            ReviewOutcome out = callReview(dialogue.toString());
            rateLimiter.recordTokens(out.tokens());
            QuestionReview r = out.review();
            InterviewNote note = new InterviewNote();
            note.setSessionId(sessionId);
            note.setUserId(userId);
            note.setSeqStart(seqStart);
            note.setSeqEnd(seqEnd);
            note.setQuestionText(abbreviate(r.question(), 1000));
            note.setAnswerText(answer.toString().trim());
            note.setDepth(r.depth());
            note.setStructure(r.structure());
            note.setCorrectness(r.correctness());
            note.setBrief(abbreviate(r.brief(), 255));
            note.setMissed(abbreviate(r.missed(), 255));
            note.setReferenceAnswer(abbreviate(r.referenceAnswer(), 2000));
            try {
                noteMapper.insert(note);
                log.info("[Review] 逐题复盘完成 session={} span=[{},{}] tokens={} refLen={}",
                        sessionId, seqStart, seqEnd, out.tokens(),
                        r.referenceAnswer() == null ? 0 : r.referenceAnswer().length());
            } catch (DuplicateKeyException e) {
                log.info("[Review] 复盘素材已存在，跳过 session={} seqEnd={}", sessionId, seqEnd);
            }
        } catch (Exception e) {
            // 后台复盘失败只记日志：主链路与报告生成均不依赖单条素材（报告缺该题章节）
            log.error("[Review] 逐题复盘失败 session={} boundary={}", sessionId, boundarySeq, e);
        }
    }

    /** 降级链：主模型（熔断保护）失败 → 备用模型；备用也失败抛出，由 reviewSpan 记日志丢弃 */
    private ReviewOutcome callReview(String dialogue) {
        String input = "【围绕同一道题的完整对话（可能含追问）】\n" + dialogue;
        try {
            return llmResilience.withMain(() -> once(chatClient, null, input));
        } catch (Exception primary) {
            log.warn("[Review] 主模型复盘失败，切换备用模型 [fallback={}]", props.fallbackModel(), primary);
            llmResilience.recordFallback();
            return once(fallbackChatClient, props.fallbackModel(), input);
        }
    }

    private ReviewOutcome once(ChatClient client, String modelOverride, String input) {
        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder().temperature(0.2);
        if (modelOverride != null) {
            opts.model(modelOverride);
        }
        ChatClientResponse ccr = client.prompt().system(REVIEW_SYSTEM).user(input)
                .options(opts.build()).call().chatClientResponse();
        String text = ccr.chatResponse().getResult().getOutput().getText();
        QuestionReview r = parse(text);
        int tokens = 0;
        if (ccr.chatResponse().getMetadata() != null && ccr.chatResponse().getMetadata().getUsage() != null) {
            tokens = ccr.chatResponse().getMetadata().getUsage().getTotalTokens().intValue();
        }
        return new ReviewOutcome(r, tokens);
    }

    private QuestionReview parse(String text) {
        try {
            QuestionReview r = objectMapper.readValue(stripJsonFence(text), QuestionReview.class);
            if (r == null || r.question() == null || r.question().isBlank()) {
                throw new IllegalStateException("复盘输出缺少 question");
            }
            return r;
        } catch (Exception e) {
            throw new IllegalStateException("复盘输出解析失败: " + (text == null ? "null" : text.length() + " chars"), e);
        }
    }

    /** 容错剥离 Markdown 代码围栏（决策/报告链路靠 prompt 约束即可，此处多一层解析容错） */
    private static String stripJsonFence(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.startsWith("```json") ? t.substring(7) : t.substring(3);
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 复盘结果（模型结构化输出） */
    public record QuestionReview(String question, int depth, int structure, int correctness,
                                 String brief, String missed, String referenceAnswer) {
    }

    private record ReviewOutcome(QuestionReview review, int tokens) {
    }
}
