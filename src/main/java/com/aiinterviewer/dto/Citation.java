package com.aiinterviewer.dto;

/**
 * 引用溯源（FR-12）：助手消息生成时实际注入的资料块。
 * label 为 S1/S2…；source 为 简历/题库 展示文案；section 为块所属章节或难度；snippet 为原文节选。
 * 以 JSON 存 interview_message.citations（V3）。
 */
public record Citation(String label, String source, String section, String snippet) {
}
