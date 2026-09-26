package com.aiinterviewer.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同时作为 Redis 热会话缓存的存储单元（JSON 序列化），字段变更需兼容旧缓存：
 * citations 为阶段 4 新增可空字段，旧缓存无该键时反序列化为 null。
 */
public record MessageResponse(Long seq, String role, String content, int tokenCount,
                              LocalDateTime createdAt, List<Citation> citations) {
}
