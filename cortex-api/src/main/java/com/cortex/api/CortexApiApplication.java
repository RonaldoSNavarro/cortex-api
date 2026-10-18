package com.cortex.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada da aplicação Spring Boot do servidor HTTP/REST e MCP do Cortex.
 */
@SpringBootApplication(scanBasePackages = "com.cortex")
public class CortexApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CortexApiApplication.class, args);
    }
}
