// PATH: src/main/java/com/taskmanager/core/Application.java
package com.taskmanager.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}