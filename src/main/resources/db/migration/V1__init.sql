-- V1: 初版表结构（阶段 1）
-- 涵盖：用户、会话、消息（本期启用）；报告、简历文件（预留，阶段 4/5 启用）
-- 设计要点：
--   * interview_message.uk_session_seq：会话内递增序号唯一，W3 SSE 的 Last-Event-ID 即该 seq
--   * interview_session.idx_user_updated：会话列表按“最近活跃”排序
--   * 用户/会话逻辑删除；消息物理保留（评估报告依赖完整对话记录）

CREATE TABLE `user` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`   VARCHAR(64)  NOT NULL COMMENT '用户名，登录唯一标识',
    `password`   VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户';

CREATE TABLE `interview_session` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        BIGINT       NOT NULL COMMENT '归属用户',
    `title`          VARCHAR(128) NOT NULL DEFAULT '未命名面试' COMMENT '会话标题',
    `jd_text`        TEXT         NULL COMMENT '目标 JD 原文',
    `resume_file_id` BIGINT       NULL COMMENT '简历文件 id（阶段 4 RAG 启用）',
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/IN_PROGRESS/FINISHED',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_updated` (`user_id`, `updated_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '面试会话';

CREATE TABLE `interview_message` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  BIGINT      NOT NULL COMMENT '归属会话',
    `seq`         BIGINT      NOT NULL COMMENT '会话内递增序号（W3 SSE Last-Event-ID 偏移量）',
    `role`        VARCHAR(10) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM',
    `content`     MEDIUMTEXT  NOT NULL COMMENT '消息内容',
    `token_count` INT         NOT NULL DEFAULT 0 COMMENT 'token 估算值（阶段 2 起取 LLM usage 回填）',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_seq` (`session_id`, `seq`),
    KEY `idx_session_created` (`session_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '面试消息';

CREATE TABLE `interview_report` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id` BIGINT      NOT NULL COMMENT '归属会话，一份',
    `user_id`    BIGINT      NOT NULL COMMENT '归属用户',
    `status`     VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/FAILED（阶段 5 MQ 消费状态机）',
    `content`    MEDIUMTEXT  NULL COMMENT '报告正文（Markdown），四维：技术深度/表达结构/知识盲区/改进建议',
    `model`      VARCHAR(64) NULL COMMENT '生成模型',
    `token_used` INT         NOT NULL DEFAULT 0 COMMENT '生成消耗 token',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session` (`session_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '评估报告（阶段 5 启用）';

CREATE TABLE `resume_file` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT       NOT NULL COMMENT '归属用户',
    `file_name`    VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_path`    VARCHAR(512) NOT NULL COMMENT '存储路径',
    `file_size`    BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    `parse_status` VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/FAILED（阶段 4 解析任务）',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '简历文件（阶段 4 启用）';
