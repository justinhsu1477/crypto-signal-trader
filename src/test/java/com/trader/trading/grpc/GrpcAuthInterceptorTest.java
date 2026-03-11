package com.trader.trading.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GrpcAuthInterceptor 單元測試
 *
 * 覆蓋：空 key 跳過、正確 key 放行、錯誤 key 拒絕、null key 拒絕
 */
class GrpcAuthInterceptorTest {

    private static final Metadata.Key<String> API_KEY_META =
            Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);

    @SuppressWarnings("unchecked")
    private final ServerCall<Object, Object> call = mock(ServerCall.class);
    @SuppressWarnings("unchecked")
    private final ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);

    @BeforeEach
    void setUp() {
        when(call.getMethodDescriptor()).thenReturn(
                io.grpc.MethodDescriptor.<Object, Object>newBuilder()
                        .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName("test/Method")
                        .setRequestMarshaller(mock(io.grpc.MethodDescriptor.Marshaller.class))
                        .setResponseMarshaller(mock(io.grpc.MethodDescriptor.Marshaller.class))
                        .build()
        );
    }

    @Test
    @DisplayName("API Key 未設定 — 跳過驗證")
    void emptyKeySkipsAuth() {
        var interceptor = new GrpcAuthInterceptor("");
        Metadata headers = new Metadata();

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(call, headers);
        verify(call, never()).close(any(), any());
    }

    @Test
    @DisplayName("正確 API Key — 放行")
    void validKeyAllowed() {
        var interceptor = new GrpcAuthInterceptor("my-secret-key");
        Metadata headers = new Metadata();
        headers.put(API_KEY_META, "my-secret-key");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(call, headers);
        verify(call, never()).close(any(), any());
    }

    @Test
    @DisplayName("錯誤 API Key — 拒絕 UNAUTHENTICATED")
    void invalidKeyRejected() {
        var interceptor = new GrpcAuthInterceptor("my-secret-key");
        Metadata headers = new Metadata();
        headers.put(API_KEY_META, "wrong-key");

        interceptor.interceptCall(call, headers, next);

        verify(next, never()).startCall(any(), any());
        verify(call).close(argThat(status -> status.getCode() == Status.Code.UNAUTHENTICATED), any());
    }

    @Test
    @DisplayName("未帶 API Key — 拒絕 UNAUTHENTICATED")
    void missingKeyRejected() {
        var interceptor = new GrpcAuthInterceptor("my-secret-key");
        Metadata headers = new Metadata();

        interceptor.interceptCall(call, headers, next);

        verify(next, never()).startCall(any(), any());
        verify(call).close(argThat(status -> status.getCode() == Status.Code.UNAUTHENTICATED), any());
    }
}
