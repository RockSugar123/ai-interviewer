-- 阶段 5：报告链路启用——FAILED 原因可见（对齐 resume_file.error_msg 惯例）
ALTER TABLE `interview_report`
    ADD COLUMN `error_msg` VARCHAR(500) NULL COMMENT '生成失败原因' AFTER `token_used`;
