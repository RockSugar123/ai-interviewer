-- V5: 阶段 7 后台复盘 agent
-- 逐题复盘素材：面试进行中由后台 agent 每题异步生成（评分/点评/遗漏/参考答案），
-- 报告生成时汇总为「逐题复盘」章节。主链路零依赖：素材缺失仅使报告缺该题章节，不影响对话与报告生成。
-- 设计要点：
--   * (session_id, seq_end) 唯一键：同一题目区间幂等，重复触发/补偿重跑不会重复入库
--   * answer_text 为空 = 边界占位行（如续场前的历史对话无法逐题切分），报告汇总时跳过

CREATE TABLE `interview_note` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`       BIGINT        NOT NULL COMMENT '归属会话',
    `user_id`          BIGINT        NOT NULL COMMENT '归属用户',
    `seq_start`        BIGINT        NOT NULL COMMENT '题目对话起始消息 seq（含）',
    `seq_end`          BIGINT        NOT NULL COMMENT '题目对话结束消息 seq（含，不含面试官的下一条出题/收尾消息）',
    `question_text`    VARCHAR(1000) NOT NULL COMMENT '面试官的问题（复盘模型从对话中提炼）',
    `answer_text`      MEDIUMTEXT    NOT NULL COMMENT '候选人回答原文（区间内 USER 消息拼接，空 = 边界占位行）',
    `depth`            INT           NOT NULL DEFAULT 0 COMMENT '技术深度 1-10（占位行为 0）',
    `structure`        INT           NOT NULL DEFAULT 0 COMMENT '表达结构 1-10（占位行为 0）',
    `correctness`      INT           NOT NULL DEFAULT 0 COMMENT '正确性 1-10（占位行为 0）',
    `brief`            VARCHAR(255)  NULL COMMENT '一句话点评',
    `missed`           VARCHAR(255)  NULL COMMENT '关键遗漏点',
    `reference_answer` TEXT          NULL COMMENT '参考答案',
    `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_seq_end` (`session_id`, `seq_end`),
    KEY `idx_session` (`session_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '逐题复盘素材（阶段 7 后台 agent）';
