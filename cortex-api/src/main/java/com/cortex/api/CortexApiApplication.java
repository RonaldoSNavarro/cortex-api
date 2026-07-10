package com.cortex.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.cortex")
public class CortexApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CortexApiApplication.class, args);
    }
}
