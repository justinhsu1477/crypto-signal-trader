package com.trader.trading.service;

import com.trader.trading.grpc.generated.ConfigUpdate;
import com.trader.trading.grpc.generated.MonitorConfig;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Discord Monitor 頻道設定的 in-memory 狀態管理
 *
 * 職責：
 * 1. 儲存當前監聽的頻道設定（channel_ids, guild_ids 等）
 * 2. 管理 gRPC StreamObserver 連線
 * 3. 設定變更時推送到所有已連線的 Python Monitor
 */
@Slf4j
@Service
public class MonitorConfigStore {

    private final List<StreamObserver<ConfigUpdate>> observers = new CopyOnWriteArrayList<>();
    private volatile MonitorConfig currentConfig;
    private final AtomicLong version = new AtomicLong(0);

    private final String defaultChannelIds;

    public MonitorConfigStore(
            @Value("${monitor.default-channel-ids:}") String defaultChannelIds) {
        this.defaultChannelIds = defaultChannelIds;
        this.currentConfig = MonitorConfig.newBuilder().build();
    }

    /**
     * 啟動時從環境變數載入預設頻道（DISCORD_CHANNEL_IDS）
     */
    @PostConstruct
    void initFromDefaults() {
        if (defaultChannelIds != null && !defaultChannelIds.isBlank()) {
            List<String> channelIds = Arrays.stream(defaultChannelIds.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            if (!channelIds.isEmpty()) {
                this.currentConfig = MonitorConfig.newBuilder()
                        .addAllChannelIds(channelIds)
                        .setVersion(version.incrementAndGet())
                        .build();
                log.info("Monitor 預設頻道已載入: {}", channelIds);
            }
        }
    }

    public MonitorConfig getCurrentConfig() {
        return currentConfig;
    }

    /**
     * 更新頻道設定並即時推送到所有已連線的 Python Monitor
     */
    public void updateConfig(List<String> channelIds, List<String> guildIds,
                             List<String> authorIds, List<String> ignoreKeywords,
                             String updatedBy, String reason) {
        long newVersion = version.incrementAndGet();

        this.currentConfig = MonitorConfig.newBuilder()
                .addAllChannelIds(channelIds != null ? channelIds : List.of())
                .addAllGuildIds(guildIds != null ? guildIds : List.of())
                .addAllAuthorIds(authorIds != null ? authorIds : List.of())
                .addAllIgnoreKeywords(ignoreKeywords != null ? ignoreKeywords : List.of())
                .setVersion(newVersion)
                .build();

        ConfigUpdate update = ConfigUpdate.newBuilder()
                .setConfig(currentConfig)
                .setUpdatedBy(updatedBy)
                .setUpdateReason(reason)
                .setTimestamp(System.currentTimeMillis())
                .build();

        pushToObservers(update);
        log.info("Monitor 設定已更新 (v{}): channels={}, by={}", newVersion, channelIds, updatedBy);
    }

    public void addObserver(StreamObserver<ConfigUpdate> observer) {
        observers.add(observer);
        log.info("gRPC observer 已連線, 當前連線數={}", observers.size());
    }

    public void removeObserver(StreamObserver<ConfigUpdate> observer) {
        observers.remove(observer);
        log.info("gRPC observer 已斷線, 當前連線數={}", observers.size());
    }

    public int getConnectedObservers() {
        return observers.size();
    }

    private void pushToObservers(ConfigUpdate update) {
        List<StreamObserver<ConfigUpdate>> deadObservers = new ArrayList<>();
        for (StreamObserver<ConfigUpdate> observer : observers) {
            try {
                observer.onNext(update);
            } catch (Exception e) {
                log.warn("推送 gRPC observer 失敗，移除: {}", e.getMessage());
                deadObservers.add(observer);
            }
        }
        if (!deadObservers.isEmpty()) {
            observers.removeAll(deadObservers);
            log.info("已清理 {} 個失效 observer, 剩餘={}", deadObservers.size(), observers.size());
        }
    }
}
