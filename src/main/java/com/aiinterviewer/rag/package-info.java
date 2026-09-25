/**
 * RAG 管线（阶段 4 启用）。
 * 文档解析（Tika）→ 结构感知分块 → embedding → 向量入库（RedisStack/pgvector 待定）→
 * 召回 + 重排 + 引用溯源。届时以真实现替换阶段 2 中题库检索/简历要点提取两个 stub 工具。
 */
package com.aiinterviewer.rag;
