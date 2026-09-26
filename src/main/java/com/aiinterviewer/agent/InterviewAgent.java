package com.aiinterviewer.agent;

import com.aiinterviewer.agent.tools.InterviewTools;
import com.aiinterviewer.common.BusinessException;
import com.aiinterviewer.common.ErrorCode;
import com.aiinterviewer.dto.Citation;
import com.aiinterviewer.dto.MessageResponse;
import com.aiinterviewer.infra.persistence.entity.InterviewMessage;
import com.aiinterviewer.infra.persistence.entity.InterviewSession;
import com.aiinterviewer.infra.persistence.mapper.InterviewSessionMapper;
import com.aiinterviewer.rag.RagProperties;
import com.aiinterviewer.rag.RagRetrievalService;
import com.aiinterviewer.rag.RetrievedChunk;
import com.aiinterviewer.service.ChatContextService;
import com.aiinterviewer.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 面试编排器（FR-4 核心，自研决策层）：
 * 上下文组装（FR-3 预算裁剪）→ 决策（LLM 结构化输出）→ 状态机守卫 → 生成（工具调用循环由 Spring AI 托管，FR-5）→ 状态推进与落库。
 * 决策调用失败时安全降级为 NEXT_QUESTION，主链路不中断（失败兜底原则）。
 * W3 起生成侧支持流式（replyStreaming，逐 token 回调），守卫与状态推进逻辑经 PlannedAction 与同步路径共用。
 * 阶段 4 起生成前做 RAG 检索（简历资料注入 + 引用溯源），检索失败静默降级为无资料生成。
 */
@Slf4j
@Service
public class InterviewAgent {

    /** 开场不走决策层，直接以出题动作生成开场白 + 第一题 */
    private static final PlannedAction OPENING_PLAN = new PlannedAction(
            AgentAction.NEXT_QUESTION, AgentPrompts.OPENING_RULE, InterviewPhase.QUESTIONING, 0, 1, false);

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final ChatContextService chatContextService;
    private final InterviewSessionMapper sessionMapper;
    private final RagRetrievalService ragRetrievalService;
    private final RagProperties ragProps;
    private final InterviewAgentProperties props;

    public InterviewAgent(ChatClient.Builder chatClientBuilder, SessionService sessionService,
                          ChatContextService chatContextService, InterviewSessionMapper sessionMapper,
                          RagRetrievalService ragRetrievalService, RagProperties ragProps,
                          InterviewAgentProperties props) {
        this.chatClient = chatClientBuilder.build();
        this.sessionService = sessionService;
        this.chatContextService = chatContextService;
        this.sessionMapper = sessionMapper;
        this.ragRetrievalService = ragRetrievalService;
        this.ragProps = ragProps;
        this.props = props;
    }

    /** 生成面试官回复（同步整段版，stream-enabled=false 时保留）。调用前用户消息必须已入库。 */
    public MessageResponse reply(long sessionId, long userId) {
        InterviewSession session = sessionService.getOwned(sessionId, userId);
        if (InterviewSession.STATUS_FINISHED.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试已结束，不能再对话");
        }
        InterviewPhase phase = InterviewPhase.from(session.getAgentState());
        String jdText = session.getJdText();
        String transcript = buildTranscript(sessionId, userId);
        InterviewTools tools = new InterviewTools(ragRetrievalService, ragProps,
                userId, session.getResumeFileId());

        if (phase == null) {
            log.info("[Agent] 开场 session={}", sessionId);
            return finishPlan(sessionId, userId, jdText, transcript, null,
                    session.getResumeFileId(), OPENING_PLAN, tools);
        }
        AgentDecision decision = decide(phase, session, jdText, transcript);
        PlannedAction plan = planAction(sessionId, phase, decision,
                nz(session.getProbeCount()), nz(session.getQuestionCount()));
        return finishPlan(sessionId, userId, jdText, transcript, decision.topic(),
                session.getResumeFileId(), plan, tools);
    }

    /** 流式版主链路（W3）：决策与守卫同同步路径，生成改为逐 token 回调；结束后落库 + 推进状态机。 */
    public MessageResponse replyStreaming(long sessionId, long userId, Consumer<String> onDelta) {
        InterviewSession session = sessionService.getOwned(sessionId, userId);
        if (InterviewSession.STATUS_FINISHED.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试已结束，不能再对话");
        }
        InterviewPhase phase = InterviewPhase.from(session.getAgentState());
        String jdText = session.getJdText();
        String transcript = buildTranscript(sessionId, userId);
        InterviewTools tools = new InterviewTools(ragRetrievalService, ragProps,
                userId, session.getResumeFileId());

        PlannedAction plan;
        String decisionTopic = null;
        if (phase == null) {
            log.info("[Agent] 开场（流式） session={}", sessionId);
            plan = OPENING_PLAN;
        } else {
            AgentDecision decision = decide(phase, session, jdText, transcript);
            decisionTopic = decision.topic();
            plan = planAction(sessionId, phase, decision,
                    nz(session.getProbeCount()), nz(session.getQuestionCount()));
        }
        GenerateInput input = assembleInput(session.getResumeFileId(), jdText, transcript, plan, decisionTopic);
        MessageResponse reply = generateStreaming(sessionId, AgentPrompts.INTERVIEWER_SYSTEM,
                input.promptText(), plan, tools, input.citations(), onDelta);
        updatePhase(sessionId, plan.nextPhase(), plan.nextProbe(), plan.nextQuestion(), plan.finish());
        if (plan.finish()) {
            log.info("[Agent] 面试结束 session={}", sessionId);
        }
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

    /** 动作规划结果：守卫钳制后的动作 + 生成指令 + 状态推进参数（同步/流式共用） */
    private record PlannedAction(AgentAction action, String instruction, InterviewPhase nextPhase,
                                 int nextProbe, int nextQuestion, boolean finish) {
    }

    /** 生成输入：prompt 文本 + 本轮引用资料（随消息落库，供前端溯源展示） */
    private record GenerateInput(String promptText, List<Citation> citations) {
    }

    /** 状态机守卫：非法或越限的决策被钳制到安全动作 */
    private PlannedAction planAction(long sessionId, InterviewPhase phase, AgentDecision decision,
                                     int probeCount, int questionCount) {
        AgentAction action = decision.normalizedAction();
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
        return switch (action) {
            case PROBE -> new PlannedAction(action, AgentPrompts.INSTR_PROBE,
                    InterviewPhase.PROBING, probeCount + 1, questionCount, false);
            case SWITCH_TOPIC -> new PlannedAction(action, AgentPrompts.INSTR_SWITCH_TOPIC,
                    InterviewPhase.QUESTIONING, 0, questionCount + 1, false);
            case WRAP_UP -> new PlannedAction(action, AgentPrompts.INSTR_WRAP_UP,
                    InterviewPhase.DONE, probeCount, questionCount, true);
            case NEXT_QUESTION -> new PlannedAction(action, AgentPrompts.INSTR_NEXT_QUESTION,
                    InterviewPhase.QUESTIONING, 0, questionCount + 1, false);
        };
    }

    /** 同步路径：按规划组装输入、生成并落库，推进状态 */
    private MessageResponse finishPlan(long sessionId, long userId, String jdText, String transcript,
                                       String decisionTopic, Long resumeFileId, PlannedAction plan,
                                       InterviewTools tools) {
        GenerateInput input = assembleInput(resumeFileId, jdText, transcript, plan, decisionTopic);
        MessageResponse reply = generateAndPersist(sessionId, AgentPrompts.INTERVIEWER_SYSTEM,
                input.promptText(), plan.action().name(), tools, input.citations());
        updatePhase(sessionId, plan.nextPhase(), plan.nextProbe(), plan.nextQuestion(), plan.finish());
        if (plan.finish()) {
            log.info("[Agent] 面试结束 session={}", sessionId);
        }
        return reply;
    }

    /** 生成输入组装（阶段 4）：会话挂载简历时检索相关块注入 prompt，citations 随回复落库；失败静默降级 */
    private GenerateInput assembleInput(Long resumeFileId, String jdText, String transcript,
                                        PlannedAction plan, String decisionTopic) {
        String reference = "";
        List<Citation> citations = null;
        if (ragProps.enabled() && resumeFileId != null) {
            try {
                String query = buildRagQuery(decisionTopic, transcript);
                List<RetrievedChunk> chunks = ragRetrievalService.retrieveForGeneration(resumeFileId, query);
                if (!chunks.isEmpty()) {
                    citations = toCitations(chunks);
                    reference = AgentPrompts.referenceBlock(citations);
                    log.info("[Agent] RAG 注入 {} 块资料 [resume={} topic={}]", chunks.size(), resumeFileId, decisionTopic);
                }
            } catch (Exception e) {
                log.warn("[Agent] RAG 检索失败，按无资料生成 [resume={}]", resumeFileId, e);
            }
        }
        String prompt = AgentPrompts.generationInput(plan.instruction(), jdText, transcript, reference);
        return new GenerateInput(prompt, citations);
    }

    /** 检索 query：决策主题 + 对话尾部（含候选人最新发言） */
    private String buildRagQuery(String topic, String transcript) {
        String tail = transcript.length() > 500 ? transcript.substring(transcript.length() - 500) : transcript;
        return topic == null || topic.isBlank() ? tail : topic + "\n" + tail;
    }

    private List<Citation> toCitations(List<RetrievedChunk> chunks) {
        List<Citation> citations = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            String snippet = c.text().length() > 120 ? c.text().substring(0, 120) + "…" : c.text();
            citations.add(new Citation("S" + (i + 1), c.display(), c.section(), snippet));
        }
        return citations;
    }

    // ---------- LLM 调用（生成侧，工具循环由 Spring AI 托管） ----------

    private MessageResponse generateAndPersist(long sessionId, String system, String user, String tag,
                                               InterviewTools tools, List<Citation> citations) {
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
        return chatContextService.writeAssistant(sessionId, text, totalTokens, citations);
    }

    /** 流式生成：逐 token 回调（失败/超时抛异常，由上层 error 事件兜底），完成后落库并返回终稿 */
    private MessageResponse generateStreaming(long sessionId, String system, String user,
                                              PlannedAction plan, InterviewTools tools,
                                              List<Citation> citations, Consumer<String> onDelta) {
        StringBuilder text = new StringBuilder();
        AtomicReference<Number> usageTokens = new AtomicReference<>();
        chatClient.prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder().temperature(props.generationTemperature()).build())
                .tools(tools)
                .stream()
                .chatClientResponse()
                .doOnNext(ccr -> {
                    captureUsage(ccr, usageTokens);
                    String delta = extractDelta(ccr);
                    if (!delta.isEmpty()) {
                        text.append(delta);
                        onDelta.accept(delta);
                    }
                })
                .timeout(Duration.ofSeconds(props.generationTimeoutSeconds()))
                .blockLast();
        String content = text.toString();
        if (content.isBlank()) {
            throw new IllegalStateException("流式生成结果为空");
        }
        int totalTokens = usageTokens.get() != null
                ? usageTokens.get().intValue()
                : (int) Math.round(content.length() / 2.0); // 流式 usage 缺失时按中文密度粗估
        log.info("[Agent] 流式生成完成 session={} tag={} tokens={} chars={}",
                sessionId, plan.action(), totalTokens, content.length());
        return chatContextService.writeAssistant(sessionId, content, totalTokens, citations);
    }

    /** 流式各分片可能缺 usage；取首个非空 totalTokens（通常在最后一个分片） */
    private static void captureUsage(ChatClientResponse ccr, AtomicReference<Number> sink) {
        if (sink.get() != null || ccr == null || ccr.chatResponse() == null
                || ccr.chatResponse().getMetadata() == null
                || ccr.chatResponse().getMetadata().getUsage() == null) {
            return;
        }
        Number total = ccr.chatResponse().getMetadata().getUsage().getTotalTokens();
        if (total != null && total.intValue() > 0) {
            sink.set(total);
        }
    }

    private static String extractDelta(ChatClientResponse ccr) {
        if (ccr == null || ccr.chatResponse() == null || ccr.chatResponse().getResult() == null
                || ccr.chatResponse().getResult().getOutput() == null) {
            return "";
        }
        String t = ccr.chatResponse().getResult().getOutput().getText();
        return t == null ? "" : t;
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
