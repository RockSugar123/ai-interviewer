package com.aiinterviewer.agent;

import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("interview.agent")
public record InterviewAgentProperties(
        @DefaultValue("2") int maxProbesPerQuestion,
        @DefaultValue("10") int maxQuestions,
        @DefaultValue("6000") int maxContextChars,
        @DefaultValue("0.2") double decisionTemperature,
        @DefaultValue("0.7") double generationTemperature,
        /** W3 流式总开关：false 时回退阶段 2 的同步整段路径（LLM 流式异常时的整体兜底） */
        @DefaultValue("true") boolean streamEnabled,
        /** 流式生成两个信号间的最大间隔（思维链阶段也有分片流动，超隔视为卡死） */
        @DefaultValue("180") int generationTimeoutSeconds,
        /** FR-17 降级链备用模型：主模型调用失败（熔断/重试耗尽）后切换，同 key 同端点 */
        @DefaultValue("qwen-flash") String fallbackModel) {
}
