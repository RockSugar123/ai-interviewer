-- V2: 阶段 2 Agent 状态机字段
-- agent_state 存放面试流程状态机的当前状态（状态驻留：OPENING/QUESTIONING/PROBING/CLOSING/DONE）；
-- SWITCH_TOPIC 在设计上是一个“动作”而非驻留状态，触发后进入 QUESTIONING。
-- probe_count/question_count 供追问与收尾的守卫规则使用。

ALTER TABLE `interview_session`
    ADD COLUMN `agent_state` VARCHAR(20) NULL COMMENT 'Agent 状态机：OPENING/QUESTIONING/PROBING/CLOSING/DONE（NULL=未开始）' AFTER `status`,
    ADD COLUMN `probe_count` INT NOT NULL DEFAULT 0 COMMENT '当前问题下的追问次数' AFTER `agent_state`,
    ADD COLUMN `question_count` INT NOT NULL DEFAULT 0 COMMENT '已提问总数' AFTER `probe_count`;
