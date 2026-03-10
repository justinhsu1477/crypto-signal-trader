package com.trader.shared.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)   // 總超時保護：webhook 同步呼叫最多等 15 秒
                .retryOnConnectionFailure(false)      // 讓 Spring retry 統一管理重試
                .build();
    }
}
