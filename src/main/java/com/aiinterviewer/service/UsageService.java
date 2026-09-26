package com.aiinterviewer.service;

import com.aiinterviewer.dto.SessionUsage;
import com.aiinterviewer.infra.persistence.mapper.InterviewMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 成本报表（FR-18，阶段 6）：token 消耗从阶段 2 起积累于 interview_message.token_count，
 * 报告生成消耗在 interview_report.token_used，此处按会话聚合输出。
 */
@Service
@RequiredArgsConstructor
public class UsageService {

    private final InterviewMessageMapper messageMapper;

    public List<SessionUsage> byUser(long userId) {
        return messageMapper.selectSessionUsage(userId);
    }
}
