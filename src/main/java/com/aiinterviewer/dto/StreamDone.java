package com.aiinterviewer.dto;

/**
 * SSE 事件 done 的负载：终稿消息（此时才有真实 seq）+ 生成后的状态机状态。
 * message 为 null 表示"无新内容，正常收尾"（断线重连时生成早已完成的场景由消息回放代替；
 * 此 null 形态仅用于无任何可回放内容时让前端干净地关闭连接，避免 EventSource 无限重连）。
 */
public record StreamDone(MessageResponse message, String agentState, String status) {
}
