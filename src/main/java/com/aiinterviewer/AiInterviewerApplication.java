package com.aiinterviewer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@MapperScan("com.aiinterviewer.infra.persistence.mapper")
@ConfigurationPropertiesScan
public class AiInterviewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiInterviewerApplication.class, args);
    }
}
