package com.aiinterviewer.agent;

import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("interview.agent")
public record InterviewAgentProperties(
        @DefaultValue("2") int maxProbesPerQuestion,
        @DefaultValue("10") int maxQuestions,
        @DefaultValue("6000") int maxContextChars,
        @DefaultValue("0.2") double decisionTemperature,
        @DefaultValue("0.7") double generationTemperature) {
}
