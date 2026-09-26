package com.aiinterviewer.infra.mq;

import com.aiinterviewer.service.ReportMessage;
import com.aiinterviewer.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 报告消费者（FR-13）。业务逻辑在 ReportService.handleReportMessage（幂等/重试/死信语义见该类）。
 * 消费失败抛异常 → RocketMQ 自动退避重试 → 耗尽进 %DLQ%report-consumer-group。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${interview.mq.report-topic}",
        consumerGroup = "report-consumer-group")
public class ReportMessageListener implements RocketMQListener<ReportMessage> {

    private final ReportService reportService;

    @Override
    public void onMessage(ReportMessage message) {
        log.info("[Report] 收到报告消息 {}", message);
        reportService.handleReportMessage(message);
    }
}
