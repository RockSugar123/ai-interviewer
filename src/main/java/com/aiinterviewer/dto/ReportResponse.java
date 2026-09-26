package com.aiinterviewer.dto;

import java.time.LocalDateTime;

/**
 * 评估报告响应（FR-13/14）。status: PENDING/RUNNING/DONE/FAILED；前端按状态轮询。
 */
public record ReportResponse(
        Long id,
        long sessionId,
        String status,
        String content,
        String model,
        Integer tokenUsed,
        String errorMsg,
        LocalDateTime updatedAt) {
}
