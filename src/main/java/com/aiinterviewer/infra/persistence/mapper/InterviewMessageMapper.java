package com.aiinterviewer.infra.persistence.mapper;

import com.aiinterviewer.dto.SessionUsage;
import com.aiinterviewer.infra.persistence.entity.InterviewMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface InterviewMessageMapper extends BaseMapper<InterviewMessage> {

    /**
     * 会话内最大序号。并发 append 的序号竞争由 uk_session_seq 唯一键兜底，
     * 单用户单会话场景下冲突概率可忽略。
     */
    @Select("SELECT IFNULL(MAX(seq), 0) FROM interview_message WHERE session_id = #{sessionId}")
    long selectMaxSeq(@Param("sessionId") Long sessionId);

    /** FR-18：按会话聚合 token 消耗（消息 + 报告），成本报表数据源 */
    @Select("""
            SELECT s.id AS sessionId, s.title,
                   IFNULL(SUM(m.token_count), 0) AS messageTokens,
                   IFNULL(r.token_used, 0) AS reportTokens,
                   s.updated_at AS lastActive
            FROM interview_session s
            LEFT JOIN interview_message m ON m.session_id = s.id
            LEFT JOIN interview_report r ON r.session_id = s.id
            WHERE s.user_id = #{userId} AND s.deleted = 0
            GROUP BY s.id, s.title, r.token_used, s.updated_at
            ORDER BY s.updated_at DESC
            """)
    List<SessionUsage> selectSessionUsage(@Param("userId") long userId);
}
