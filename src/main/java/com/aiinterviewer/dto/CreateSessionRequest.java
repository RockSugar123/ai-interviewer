package com.aiinterviewer.dto;

import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @Size(max = 128, message = "标题最长 128 字")
        String title,
        @Size(max = 20000, message = "JD 过长")
        String jdText,
        /** 阶段 4：挂载已上传的简历（需归属当前用户，可空） */
        Long resumeFileId) {
}
