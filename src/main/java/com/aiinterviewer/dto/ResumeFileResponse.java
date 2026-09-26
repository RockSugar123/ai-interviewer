package com.aiinterviewer.dto;

import java.time.LocalDateTime;

/** 简历文件列表/上传返回 */
public record ResumeFileResponse(Long id, String fileName, Long fileSize, String parseStatus,
                                 String errorMsg, LocalDateTime createdAt) {
}
