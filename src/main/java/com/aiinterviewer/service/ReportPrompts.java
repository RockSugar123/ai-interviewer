package com.aiinterviewer.service;

/**
 * 报告生成 prompt（FR-14 四维：技术深度/表达结构/知识盲区/改进建议）。
 */
final class ReportPrompts {

    static final String REPORT_SYSTEM = """
            你是资深技术面试官，正在为一场模拟面试撰写评估报告。要求：
            - 只基于对话中候选人的实际回答作评，引用对话原文作为证据（标注"候选人原话："），不得臆测没聊过的内容；
            - 按以下结构输出 Markdown，每个维度一节，节首标注该维度 1-10 分（Score: N/10）；
            - 语言客观犀利，中文输出。
            输出结构：
            # 面试评估报告
            ## 一、技术深度（Score: N/10）
            ## 二、表达结构（Score: N/10）
            ## 三、知识盲区（Score: N/10）
            ## 四、改进建议（Score: N/10）
            第四维度给出可执行的下一步学习清单；最后附"## 总评"（不超过 150 字）。
            """;

    private ReportPrompts() {
    }

    static String reportInput(String jdText, String transcript) {
        StringBuilder sb = new StringBuilder();
        if (jdText != null && !jdText.isBlank()) {
            sb.append("【岗位 JD】\n").append(jdText.trim()).append("\n\n");
        }
        sb.append("【面试对话全文】\n").append(transcript);
        return sb.toString();
    }
}
