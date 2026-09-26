package com.aiinterviewer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MQ 配置（FR-13/15，阶段 5）：报告链路 + 简历索引任务。
 * enabled=false 降级语义：报告链路整体停用（finish 不发消息）、简历索引退回同步执行（阶段 4 行为）。
 */
@ConfigurationProperties("interview.mq")
public record MqProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("interview-report") String reportTopic,
        @DefaultValue("resume-index") String indexTopic) {
}
