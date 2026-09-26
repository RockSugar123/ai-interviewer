package com.aiinterviewer.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 结构感知分块器（FR-11）：简历按 section（教育/工作/项目/技能等标题行）切分，
 * 超长 section 再按句子边界递归切，块间带 overlap。相比纯 token 切分保留语义边界，
 * 召回的 chunk 自带 section 元数据，可做引用溯源（FR-12）。
 */
@Component
public class ResumeTextSplitter {

    /** 目标块长（字符，中文语料按字符近似 token） */
    static final int TARGET_CHARS = 600;
    /** 超过该长度的 section 强制再切 */
    static final int MAX_CHARS = 800;
    /** 相邻块重叠字符数（约 12%） */
    static final int OVERLAP_CHARS = 80;

    /** 常见简历 section 标题行（兼容"一、"、"1."等前缀与中英文冒号） */
    private static final Pattern SECTION_HEAD = Pattern.compile(
            "^[#\\s]*([一二三四五六七八九十]+[、.．]|\\d+[、.．])?\\s*"
                    + "(教育经历|教育背景|工作经历|工作经验|职业经历|项目经历|项目经验|实习经历|"
                    + "专业技能|技能特长|技术栈|专业技能|自我评价|个人评价|证书|荣誉奖项|获奖经历|"
                    + "校园经历|开源项目|个人项目|核心项目|专业技能|其他)[\\s:：]?.{0,30}$");

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？；;.!?\n])");

    /**
     * @param text      解析后的简历全文
     * @param baseMeta  每块共享的元数据（userId/resumeFileId/source 等）
     * @return 带 source/section/offset 元数据的文档块，id 由调用方生成
     */
    public List<Document> split(String text, Map<String, Object> baseMeta) {
        List<Document> documents = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return documents;
        }
        List<Section> sections = splitSections(text);
        int offset = 0;
        for (Section section : sections) {
            for (String piece : chunkSection(section.body())) {
                if (piece.isBlank()) {
                    continue;
                }
                Map<String, Object> meta = new HashMap<>(baseMeta);
                meta.put("section", section.title());
                meta.put("offset", offset);
                documents.add(new Document(piece, meta));
                offset++;
            }
        }
        return documents;
    }

    /** 按标题行切 section；未识别出任何标题时整体视为一个"全文" section */
    private List<Section> splitSections(String text) {
        String[] lines = text.split("\n");
        List<Section> sections = new ArrayList<>();
        String currentTitle = "全文";
        StringBuilder currentBody = new StringBuilder();
        boolean anySectionHead = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (isSectionHead(trimmed)) {
                if (currentBody.length() > 0) {
                    sections.add(new Section(currentTitle, currentBody.toString()));
                    currentBody = new StringBuilder();
                }
                currentTitle = trimmed;
                anySectionHead = true;
            } else {
                currentBody.append(line).append('\n');
            }
        }
        if (currentBody.length() > 0) {
            sections.add(new Section(currentTitle, currentBody.toString()));
        }
        if (!anySectionHead && sections.size() == 1 && sections.get(0).body().length() > MAX_CHARS) {
            // 无结构的纯文本：按段落近似切，标题统一为"全文"
            return splitByParagraph(text);
        }
        return sections;
    }

    private List<Section> splitByParagraph(String text) {
        List<Section> sections = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (String para : text.split("\n{2,}")) {
            if (body.length() + para.length() > TARGET_CHARS && body.length() > 0) {
                sections.add(new Section("全文", body.toString()));
                body = new StringBuilder();
            }
            body.append(para).append("\n\n");
        }
        if (body.length() > 0) {
            sections.add(new Section("全文", body.toString()));
        }
        return sections;
    }

    private boolean isSectionHead(String line) {
        return !line.isBlank() && line.length() <= 40 && SECTION_HEAD.matcher(line).matches();
    }

    /** 单个 section 内部：不超过 MAX 直接返回；超长按句子边界切到 TARGET 并留 overlap */
    private List<String> chunkSection(String body) {
        String normalized = body.strip();
        if (normalized.length() <= MAX_CHARS) {
            return List.of(normalized);
        }
        List<String> pieces = new ArrayList<>();
        List<String> sentences = new ArrayList<>(List.of(SENTENCE_BOUNDARY.split(normalized)));
        StringBuilder buf = new StringBuilder();
        for (String sentence : sentences) {
            if (buf.length() + sentence.length() > TARGET_CHARS && buf.length() > 0) {
                pieces.add(buf.toString().strip());
                int from = Math.max(0, buf.length() - OVERLAP_CHARS);
                buf = new StringBuilder(buf.substring(from));
            }
            buf.append(sentence);
        }
        if (buf.length() > 0 && !buf.toString().isBlank()) {
            pieces.add(buf.toString().strip());
        }
        return pieces;
    }

    private record Section(String title, String body) {
    }
}
