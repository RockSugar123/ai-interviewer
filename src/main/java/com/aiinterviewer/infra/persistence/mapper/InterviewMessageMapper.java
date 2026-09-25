package com.aiinterviewer.infra.persistence.mapper;

import com.aiinterviewer.infra.persistence.entity.InterviewMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface InterviewMessageMapper extends BaseMapper<InterviewMessage> {

    /**
     * 会话内最大序号。并发 append 的序号竞争由 uk_session_seq 唯一键兜底，
     * 单用户单会话场景下冲突概率可忽略。
     */
    @Select("SELECT IFNULL(MAX(seq), 0) FROM interview_message WHERE session_id = #{sessionId}")
    long selectMaxSeq(@Param("sessionId") Long sessionId);
}
