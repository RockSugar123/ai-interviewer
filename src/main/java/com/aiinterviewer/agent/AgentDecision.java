package com.aiinterviewer.agent;

/**
 * 决策层的结构化输出（LLM structured output → 本 record）。
 */
public record AgentDecision(String action, Integer probeDepth, String topic, String reason) {

    public AgentAction normalizedAction() {
        return AgentAction.normalize(action);
    }

    public int depthOrOne() {
        return probeDepth == null || probeDepth < 1 ? 1 : Math.min(probeDepth, 3);
    }
}
