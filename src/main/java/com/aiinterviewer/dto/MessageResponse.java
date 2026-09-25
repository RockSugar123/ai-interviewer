package com.aiinterviewer.dto;

import java.time.LocalDateTime;

/**
 * 同时作为 Redis 热会话缓存的存储单元（JSON 序列化），字段变更需兼容旧缓存。
 */
public record MessageResponse(Long seq, String role, String content, int tokenCount, LocalDateTime createdAt) {
}
