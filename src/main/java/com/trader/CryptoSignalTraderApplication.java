package com.trader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan({"com.trader.shared.config", "com.trader.subscription.config"})
@EnableScheduling
public class CryptoSignalTraderApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoSignalTraderApplication.class, args);
    }
}
