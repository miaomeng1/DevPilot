package com.devpilot.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class DevPilotServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevPilotServerApplication.class, args);
    }
}
