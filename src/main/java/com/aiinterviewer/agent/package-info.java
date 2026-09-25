/**
 * Agent 编排层（阶段 2 启用）。
 * 自研核心：面试流程状态机（出题/追问/切换/结束决策）、上下文裁剪与摘要策略、
 * 工具超时与失败兜底。工具调用循环由 Spring AI 托管（模型原生 Function Calling）。
 * 分层边界见 docs/需求文档.md 6.2。
 */
package com.aiinterviewer.agent;
