package com.evebot.structuremonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StructureMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(StructureMonitorApplication.class, args);
    }
}
