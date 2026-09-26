-- V3: 阶段 4 RAG 管线
--   * resume_file.error_msg：解析失败原因（前端可见，替代静默失败）
--   * interview_message.citations：助手消息的引用溯源（生成时检索到的资料，JSON 数组字符串）

ALTER TABLE `resume_file`
    ADD COLUMN `error_msg` VARCHAR(500) NULL COMMENT '解析/索引失败原因' AFTER `parse_status`;

ALTER TABLE `interview_message`
    ADD COLUMN `citations` TEXT NULL COMMENT '引用资料 JSON：[{label,source,section,snippet}]（阶段 4 RAG）' AFTER `token_count`;
