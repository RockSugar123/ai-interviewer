package com.aiinterviewer.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逐题复盘素材（阶段 7）：后台复盘 agent 每题一条，报告生成时汇总为「逐题复盘」章节。
 * answer_text 为空 = 边界占位行（续场前历史无法逐题切分），汇总时跳过；主链路不读此表，
 * 素材缺失仅使报告缺该题章节。
 */
@Data
@TableName("interview_note")
public class InterviewNote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long userId;

    /** 题目对话起始消息 seq（含） */
    private Long seqStart;

    /** 题目对话结束消息 seq（含，不含面试官的下一条出题/收尾消息） */
    private Long seqEnd;

    private String questionText;

    private String answerText;

    private Integer depth;

    private Integer structure;

    private Integer correctness;

    private String brief;

    private String missed;

    private String referenceAnswer;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
