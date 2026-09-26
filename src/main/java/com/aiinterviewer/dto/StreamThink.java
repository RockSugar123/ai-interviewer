package com.aiinterviewer.dto;

/**
 * SSE 事件 think / full-think 的负载：思维链增量 / 断线重连时的思考快照（替换语义，同 delta 家族）。
 * seq 为触发本轮生成的用户消息 seq。
 */
public record StreamThink(long seq, String text) {
}
