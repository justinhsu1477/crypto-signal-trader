package com.trader.trading.grpc;

import com.trader.trading.grpc.generated.ConfigUpdate;
import com.trader.trading.grpc.generated.GetConfigRequest;
import com.trader.trading.grpc.generated.GetConfigResponse;
import com.trader.trading.grpc.generated.MonitorConfigServiceGrpc;
import com.trader.trading.grpc.generated.WatchConfigRequest;
import com.trader.trading.service.MonitorConfigStore;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC 服務實作 — Python Monitor 頻道設定推送
 *
 * 提供兩個 RPC：
 * 1. GetConfig（Unary）— Python 啟動時取得初始頻道設定
 * 2. WatchConfig（Server Streaming）— Python 開啟長連線，設定變更時即時推送
 */
@Slf4j
@GrpcService
public class MonitorConfigGrpcService extends MonitorConfigServiceGrpc.MonitorConfigServiceImplBase {

    private final MonitorConfigStore configStore;

    public MonitorConfigGrpcService(MonitorConfigStore configStore) {
        this.configStore = configStore;
    }

    /**
     * Python 啟動時呼叫，取得當前頻道設定
     */
    @Override
    public void getConfig(GetConfigRequest request, StreamObserver<GetConfigResponse> responseObserver) {
        log.debug("gRPC GetConfig 請求");
        GetConfigResponse response = GetConfigResponse.newBuilder()
                .setConfig(configStore.getCurrentConfig())
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * Python 開啟 Server Streaming 長連線
     * 註冊 observer 後立即推送當前設定，後續設定變更時自動推送
     */
    @Override
    public void watchConfig(WatchConfigRequest request, StreamObserver<ConfigUpdate> responseObserver) {
        log.info("Python Monitor 已連線到 gRPC config stream");
        configStore.addObserver(responseObserver);

        // 立即推送當前設定作為初始同步
        ConfigUpdate initial = ConfigUpdate.newBuilder()
                .setConfig(configStore.getCurrentConfig())
                .setUpdatedBy("system")
                .setUpdateReason("initial_sync")
                .setTimestamp(System.currentTimeMillis())
                .build();

        try {
            responseObserver.onNext(initial);
        } catch (Exception e) {
            log.warn("初始推送失敗，移除 observer: {}", e.getMessage());
            configStore.removeObserver(responseObserver);
        }
    }
}
