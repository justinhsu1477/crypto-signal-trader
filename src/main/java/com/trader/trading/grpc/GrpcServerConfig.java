package com.trader.trading.grpc;

import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC Server 全域設定 — 註冊認證 Interceptor
 */
@Configuration
public class GrpcServerConfig {

    @Bean
    @GrpcGlobalServerInterceptor
    public GrpcAuthInterceptor grpcAuthInterceptor(GrpcAuthInterceptor interceptor) {
        return interceptor;
    }
}
