package com.aiinterviewer.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话 token 消耗聚合（FR-18）：消息 token + 报告 token，成本报表数据源。
 */
@Data
public class SessionUsage {
    private Long sessionId;
    private String title;
    private Long messageTokens;
    private Long reportTokens;
    private LocalDateTime lastActive;
}
