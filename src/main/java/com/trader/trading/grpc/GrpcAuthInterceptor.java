package com.trader.trading.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * gRPC 認證 Interceptor — 驗證 Python Monitor 的 API Key
 *
 * 複用現有 MONITOR_API_KEY，透過 gRPC metadata 的 x-api-key 傳遞。
 * 使用 MessageDigest.isEqual 比對，防止時序攻擊。
 */
@Slf4j
@GrpcGlobalServerInterceptor
public class GrpcAuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> API_KEY_META =
            Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);

    private final String monitorApiKey;

    public GrpcAuthInterceptor(@Value("${monitor.api-key:}") String monitorApiKey) {
        this.monitorApiKey = monitorApiKey;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        // 未設定 API Key 時跳過驗證（開發環境）
        if (monitorApiKey == null || monitorApiKey.isBlank()) {
            return next.startCall(call, headers);
        }

        String apiKey = headers.get(API_KEY_META);
        if (apiKey != null && MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                monitorApiKey.getBytes(StandardCharsets.UTF_8))) {
            return next.startCall(call, headers);
        }

        log.warn("gRPC 認證失敗: method={}", call.getMethodDescriptor().getFullMethodName());
        call.close(Status.UNAUTHENTICATED.withDescription("Invalid API key"), new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
