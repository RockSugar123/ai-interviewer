package com.aiinterviewer.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interview_session")
public class InterviewSession {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_FINISHED = "FINISHED";

    /** Agent 状态机的终态值（与 agent.InterviewPhase.DONE 对应；置于实体供 service 层使用，避免反向依赖 agent 包） */
    public static final String AGENT_DONE = "DONE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String jdText;

    private Long resumeFileId;

    private String status;

    /** Agent 状态机：OPENING/QUESTIONING/PROBING/CLOSING/DONE，NULL=未开始 */
    private String agentState;

    /** 当前问题下的追问次数 */
    private Integer probeCount;

    /** 已提问总数 */
    private Integer questionCount;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
