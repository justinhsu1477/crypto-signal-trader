package com.trader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.trader.config")
public class CryptoSignalTraderApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoSignalTraderApplication.class, args);
    }
}
