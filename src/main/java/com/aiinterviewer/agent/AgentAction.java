package com.aiinterviewer.agent;

/**
 * 决策动作（FR-4/FR-6）：出题、追问、切换话题、结束。
 * 模型给出原始 action 字符串，{@link #normalize(String)} 兜底为安全默认值。
 */
public enum AgentAction {
    PROBE,
    NEXT_QUESTION,
    SWITCH_TOPIC,
    WRAP_UP;

    public static AgentAction normalize(String raw) {
        if (raw == null) {
            return NEXT_QUESTION;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NEXT_QUESTION;
        }
    }
}
