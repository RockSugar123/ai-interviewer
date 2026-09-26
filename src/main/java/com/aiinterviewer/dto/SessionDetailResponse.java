package com.aiinterviewer.dto;

import java.time.LocalDateTime;

public record SessionDetailResponse(Long id, String title, String jdText, String status, String agentState,
                                    Integer probeCount, Integer questionCount, Long resumeFileId,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
}
