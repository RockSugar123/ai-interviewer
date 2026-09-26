package com.aiinterviewer.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息不做逻辑删除：评估报告依赖完整对话记录，且 uk_session_seq 依赖序号连续。
 */
@Data
@TableName("interview_message")
public class InterviewMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_SYSTEM = "SYSTEM";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 会话内递增序号，W3 SSE Last-Event-ID 的偏移量 */
    private Long seq;

    private String role;

    private String content;

    /** token 估算值；阶段 2 起以 LLM 响应 usage 为准 */
    private Integer tokenCount;

    /** 引用资料 JSON（阶段 4 RAG 溯源），ASSISTANT 消息可有 */
    private String citations;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
