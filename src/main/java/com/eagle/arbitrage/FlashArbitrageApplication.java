package com.eagle.arbitrage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class FlashArbitrageApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashArbitrageApplication.class, args);
    }

}
