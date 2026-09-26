package com.aiinterviewer.infra.mq;

import com.aiinterviewer.rag.RagIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 简历索引消费者（FR-10 迁 MQ，阶段 5）：替代原 @Async("ragIndexExecutor") 链路。
 * 串行保证不在消费者线程数上做（starter 2.3.1 注解无 consumeThreadMin，单设 max 会因 min(20)>max 启动失败），
 * 而是锁在 RagIndexService.indexResume 方法上（synchronized），消费线程并发不影响写安全，且保留 MQ 失败重试语义。
 * 失败语义由 RagIndexService.indexResume 决定：确定性失败（解析为空等）正常 ACK 不重试；瞬时失败抛出走 MQ 退避重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${interview.mq.index-topic}",
        consumerGroup = "resume-index-consumer-group")
public class ResumeIndexListener implements RocketMQListener<String> {

    private final RagIndexService ragIndexService;

    @Override
    public void onMessage(String resumeFileId) {
        long id = Long.parseLong(resumeFileId);
        log.info("[RAG] 收到简历索引消息 [id={}]", id);
        ragIndexService.indexResume(id);
    }
}
