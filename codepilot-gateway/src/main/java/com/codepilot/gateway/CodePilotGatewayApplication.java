package com.codepilot.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.codepilot")
public class CodePilotGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodePilotGatewayApplication.class, args);
    }
}
