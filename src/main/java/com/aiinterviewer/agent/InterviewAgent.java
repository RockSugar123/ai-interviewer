package com.aiinterviewer.agent;

import com.aiinterviewer.agent.tools.InterviewTools;
import com.aiinterviewer.common.BusinessException;
import com.aiinterviewer.common.ErrorCode;
import com.aiinterviewer.dto.MessageResponse;
import com.aiinterviewer.infra.persistence.entity.InterviewMessage;
import com.aiinterviewer.infra.persistence.entity.InterviewSession;
import com.aiinterviewer.infra.persistence.mapper.InterviewSessionMapper;
import com.aiinterviewer.service.ChatContextService;
import com.aiinterviewer.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;

/**
 * 面试编排器（FR-4 核心，自研决策层）：
 * 上下文组装（FR-3 预算裁剪）→ 决策（LLM 结构化输出）→ 状态机守卫 → 生成（工具调用循环由 Spring AI 托管，FR-5）→ 状态推进与落库。
 * 决策调用失败时安全降级为 NEXT_QUESTION，主链路不中断（失败兜底原则）。
 */
@Slf4j
@Service
public class InterviewAgent {

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final ChatContextService chatContextService;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewTools tools;
    private final InterviewAgentProperties props;

    public InterviewAgent(ChatClient.Builder chatClientBuilder, SessionService sessionService,
                          ChatContextService chatContextService, InterviewSessionMapper sessionMapper,
                          InterviewTools tools, InterviewAgentProperties props) {
        this.chatClient = chatClientBuilder.build();
        this.sessionService = sessionService;
        this.chatContextService = chatContextService;
        this.sessionMapper = sessionMapper;
        this.tools = tools;
        this.props = props;
    }

    /** 生成面试官回复。调用前用户消息必须已入库（controller 已 append），本方法从上下文读取。 */
    public MessageResponse reply(long sessionId, long userId) {
        InterviewSession session = sessionService.getOwned(sessionId, userId);
        if (InterviewSession.STATUS_FINISHED.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试已结束，不能再对话");
        }
        InterviewPhase phase = InterviewPhase.from(session.getAgentState());
        String jdText = session.getJdText();
        String transcript = buildTranscript(sessionId, userId);

        if (phase == null) {
            log.info("[Agent] 开场 session={}", sessionId);
            return runOpening(session, jdText, transcript);
        }
        AgentDecision decision = decide(phase, session, jdText, transcript);
        return runAction(session, phase, decision, jdText, transcript);
    }

    // ---------- 开场 ----------

    private MessageResponse runOpening(InterviewSession session, String jdText, String transcript) {
        String input = AgentPrompts.generationInput(AgentPrompts.OPENING_RULE, jdText, transcript);
        MessageResponse reply = generateAndPersist(session.getId(), AgentPrompts.INTERVIEWER_SYSTEM,
                input, "OPENING");
        updatePhase(session.getId(), InterviewPhase.QUESTIONING, 0, 1, false);
        return reply;
    }

    // ---------- 决策 ----------

    private AgentDecision decide(InterviewPhase phase, InterviewSession session, String jdText, String transcript) {
        String input = AgentPrompts.decisionInput(phase.name(),
                nz(session.getProbeCount()), props.maxProbesPerQuestion(),
                nz(session.getQuestionCount()), props.maxQuestions(), jdText, transcript);
        try {
            AgentDecision decision = chatClient.prompt()
                    .system(AgentPrompts.DECISION_SYSTEM)
                    .user(input)
                    .options(OpenAiChatOptions.builder().temperature(props.decisionTemperature()).build())
                    .call()
                    .entity(AgentDecision.class);
            if (decision == null) {
                throw new IllegalStateException("决策输出为空");
            }
            log.info("[Agent] 决策 session={} phase={} -> {} (topic={}, reason={})",
                    session.getId(), phase, decision.normalizedAction(), decision.topic(), decision.reason());
            return decision;
        } catch (Exception e) {
            log.error("[Agent] 决策调用失败，安全降级为 NEXT_QUESTION session={}", session.getId(), e);
            return new AgentDecision(AgentAction.NEXT_QUESTION.name(), 1, null, "决策调用失败，安全默认");
        }
    }

    // ---------- 守卫 + 生成 + 状态推进 ----------

    private MessageResponse runAction(InterviewSession session, InterviewPhase phase,
                                      AgentDecision decision, String jdText, String transcript) {
        long sessionId = session.getId();
        int probeCount = nz(session.getProbeCount());
        int questionCount = nz(session.getQuestionCount());
        AgentAction action = decision.normalizedAction();

        // 状态机守卫：非法或越限的决策被钳制到安全动作
        if (action == AgentAction.PROBE && probeCount >= props.maxProbesPerQuestion()) {
            log.warn("[Agent] 守卫：追问达上限({})，强制切话题 session={}", props.maxProbesPerQuestion(), sessionId);
            action = AgentAction.SWITCH_TOPIC;
        }
        if ((action == AgentAction.NEXT_QUESTION || action == AgentAction.SWITCH_TOPIC)
                && questionCount >= props.maxQuestions()) {
            log.warn("[Agent] 守卫：提问达上限({})，强制收尾 session={}", props.maxQuestions(), sessionId);
            action = AgentAction.WRAP_UP;
        }
        if (phase == InterviewPhase.CLOSING && action != AgentAction.WRAP_UP) {
            log.warn("[Agent] 守卫：CLOSING 阶段仅允许收尾 session={}", sessionId);
            action = AgentAction.WRAP_UP;
        }

        String instruction;
        InterviewPhase nextPhase;
        int nextProbe = probeCount;
        int nextQuestion = questionCount;
        boolean finish = false;

        switch (action) {
            case PROBE -> {
                instruction = AgentPrompts.INSTR_PROBE;
                nextPhase = InterviewPhase.PROBING;
                nextProbe = probeCount + 1;
            }
            case SWITCH_TOPIC -> {
                instruction = AgentPrompts.INSTR_SWITCH_TOPIC;
                nextPhase = InterviewPhase.QUESTIONING;
                nextProbe = 0;
                nextQuestion = questionCount + 1;
            }
            case WRAP_UP -> {
                instruction = AgentPrompts.INSTR_WRAP_UP;
                nextPhase = InterviewPhase.DONE;
                finish = true;
            }
            default -> { // NEXT_QUESTION
                instruction = AgentPrompts.INSTR_NEXT_QUESTION;
                nextPhase = InterviewPhase.QUESTIONING;
                nextProbe = 0;
                nextQuestion = questionCount + 1;
            }
        }

        String input = AgentPrompts.generationInput(instruction, jdText, transcript);
        MessageResponse reply = generateAndPersist(sessionId, AgentPrompts.INTERVIEWER_SYSTEM,
                input, action.name());
        updatePhase(sessionId, nextPhase, nextProbe, nextQuestion, finish);
        if (finish) {
            log.info("[Agent] 面试结束 session={}", sessionId);
        }
        return reply;
    }

    // ---------- LLM 调用（生成侧，工具循环由 Spring AI 托管） ----------

    private MessageResponse generateAndPersist(long sessionId, String system, String user, String tag) {
        ChatClientResponse ccr = chatClient.prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder().temperature(props.generationTemperature()).build())
                .tools(tools)
                .call()
                .chatClientResponse();
        ChatResponse chatResponse = ccr.chatResponse();
        String text = chatResponse.getResult().getOutput().getText();
        int totalTokens = 0;
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            totalTokens = chatResponse.getMetadata().getUsage().getTotalTokens().intValue();
        }
        log.info("[Agent] 生成完成 session={} tag={} tokens={} chars={}", sessionId, tag, totalTokens,
                text == null ? 0 : text.length());
        return chatContextService.writeAssistant(sessionId, text, totalTokens);
    }

    // ---------- 状态与上下文 ----------

    private void updatePhase(long sessionId, InterviewPhase phase, int probeCount, int questionCount, boolean finish) {
        InterviewSession update = new InterviewSession();
        update.setId(sessionId);
        update.setAgentState(phase.name());
        update.setProbeCount(probeCount);
        update.setQuestionCount(questionCount);
        if (finish) {
            update.setStatus(InterviewSession.STATUS_FINISHED);
        }
        sessionMapper.updateById(update);
    }

    /** 从热会话上下文组装对话记录，按字符预算从最新往回裁剪（FR-3 滑动窗口，摘要压缩留待后续砍法评估） */
    private String buildTranscript(long sessionId, long userId) {
        List<MessageResponse> messages = chatContextService.list(sessionId, userId, null, null);
        LinkedList<MessageResponse> kept = new LinkedList<>();
        int used = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageResponse m = messages.get(i);
            int cost = (m.content() == null ? 0 : m.content().length()) + 10;
            if (!kept.isEmpty() && used + cost > props.maxContextChars()) {
                break;
            }
            kept.addFirst(m);
            used += cost;
        }
        StringBuilder sb = new StringBuilder();
        for (MessageResponse m : kept) {
            sb.append("seq").append(m.seq()).append(' ')
                    .append(InterviewMessage.ROLE_ASSISTANT.equals(m.role()) ? "面试官" : "候选人")
                    .append(": ").append(m.content()).append('\n');
        }
        return sb.toString();
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
