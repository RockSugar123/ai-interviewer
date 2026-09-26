package com.aiinterviewer.dto;

/**
 * SSE 事件 delta / full-delta 的负载。
 * delta 为增量追加；full-delta 为断线重连时回放的累计快照，前端以整段替换语义处理（天然去重）。
 * seq 为触发本轮生成的用户消息 seq（流式期所有事件 id 均用它，助手消息真实 seq 在 done 事件才可知）。
 */
public record StreamDelta(long seq, String text) {
}
