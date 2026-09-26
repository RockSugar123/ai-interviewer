package com.aiinterviewer.dto;

/**
 * SSE 事件 error 的负载：生成失败的可读原因。用户消息已落库，前端提示后可重新发送。
 */
public record StreamError(String message) {
}
