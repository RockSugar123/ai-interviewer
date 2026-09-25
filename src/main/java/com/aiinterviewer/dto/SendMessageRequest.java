package com.aiinterviewer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发言即用户消息：role 由服务端固定为 USER，ASSISTANT 消息自阶段 2 起由 Agent 写入。
 */
public record SendMessageRequest(
        @NotBlank
        @Size(max = 8000, message = "单条消息最长 8000 字")
        String content) {
}
