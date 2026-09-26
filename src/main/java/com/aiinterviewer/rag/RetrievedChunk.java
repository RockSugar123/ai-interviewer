package com.aiinterviewer.rag;

/**
 * 召回结果：向量库 chunk + 展示用元信息。
 * label/display 面向引用溯源（FR-12）：citations 里展示 "简历-项目经历" / "题库-深挖"。
 */
public record RetrievedChunk(String id, String text, String source, String section, String topic,
                             double score) {

    public static final String SOURCE_RESUME = "RESUME";
    public static final String SOURCE_BANK = "QUESTION_BANK";

    public String display() {
        String label = SOURCE_BANK.equals(source) ? "题库" : "简历";
        if (section == null || section.isBlank()) {
            return label;
        }
        return label + "-" + section;
    }
}
