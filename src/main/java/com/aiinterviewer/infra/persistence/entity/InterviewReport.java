package com.aiinterviewer.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评估报告（FR-13/14，阶段 5 启用；V1 预留表）。
 * 一会话一份（uk_session）；续场重新结束后覆盖更新。
 */
@Data
@TableName("interview_report")
public class InterviewReport {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long userId;
    private String status;
    /** 报告正文（Markdown），四维：技术深度/表达结构/知识盲区/改进建议 */
    private String content;
    private String model;
    private Integer tokenUsed;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
