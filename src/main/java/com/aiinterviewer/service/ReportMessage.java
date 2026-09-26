package com.aiinterviewer.service;

/**
 * 报告消息体（FR-13）：MQ 载荷。
 * finishedAt 是幂等键成分——同一结束事件重复投递按 sessionId+finishedAt 去重；
 * 续场后重新结束产生新 finishedAt → 允许重新生成并覆盖旧报告（uk_session 冲突走 UPDATE）。
 */
public record ReportMessage(long sessionId, long userId, long finishedAt) {
}
