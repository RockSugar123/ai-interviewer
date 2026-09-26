package com.aiinterviewer.infra.persistence.mapper;

import com.aiinterviewer.infra.persistence.entity.InterviewReport;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InterviewReportMapper extends BaseMapper<InterviewReport> {
}
