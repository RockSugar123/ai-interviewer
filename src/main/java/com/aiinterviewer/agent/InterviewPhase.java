package com.aiinterviewer.agent;

/**
 * 面试流程状态机的驻留状态。
 * SWITCH_TOPIC 是“动作”而非驻留状态（触发后进入 QUESTIONING），见 docs/实现方案.md 阶段 2。
 */
public enum InterviewPhase {
    OPENING,      // 开场（首条消息触发，产出开场白 + 第一题）
    QUESTIONING,  // 出题/换话题后等待并评估回答
    PROBING,      // 追问中（受 probe_count 守卫约束）
    CLOSING,      // 收尾过渡
    DONE;         // 面试结束（会话 status 同时置 FINISHED）

    public static InterviewPhase from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
