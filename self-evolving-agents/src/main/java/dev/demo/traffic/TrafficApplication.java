package dev.demo.traffic;

import dev.demo.traffic.config.DemoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(DemoProperties.class)
public class TrafficApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrafficApplication.class, args);
    }
}
