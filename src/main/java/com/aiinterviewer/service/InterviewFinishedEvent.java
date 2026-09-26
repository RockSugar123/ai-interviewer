package com.aiinterviewer.service;

/**
 * 会话结束事件（FR-13 触发点）：显式 /finish 接口与 Agent 语义收尾（WRAP_UP）两个来源统一走此事件。
 * 发布方：SessionService.finish（事务内，AFTER_COMMIT 后发 MQ）；InterviewAgent.updatePhase（无事务，立即发 MQ）。
 * 监听方：ReportService（@TransactionalEventListener AFTER_COMMIT + fallbackExecution）。
 *
 * finishedAt 是幂等键的一部分：同一会话重复投递按 sessionId+finishedAt 去重；续场后重新结束产生新 finishedAt → 允许重新生成并覆盖旧报告。
 */
public record InterviewFinishedEvent(long sessionId, long userId, long finishedAt) {
}
