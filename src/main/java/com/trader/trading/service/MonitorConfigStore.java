package com.trader.trading.service;

import com.trader.trading.grpc.generated.ConfigUpdate;
import com.trader.trading.grpc.generated.MonitorConfig;
import com.trader.trading.grpc.generated.SourceConfig;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
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
    @Getter
    private volatile MonitorConfig currentConfig;
    private final AtomicLong version = new AtomicLong(0);

    private final String defaultChannelIds;
    @Getter
    private List<String> defaultChannelIdList = List.of();

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
                this.defaultChannelIdList = channelIds;
                this.currentConfig = MonitorConfig.newBuilder()
                        .addAllChannelIds(channelIds)
                        .setVersion(version.incrementAndGet())
                        .build();
                log.info("Monitor 預設頻道已載入: {}", channelIds);
            }
        }
    }

    /**
     * 更新頻道設定並即時推送到所有已連線的 Python Monitor
     * 保留既有的 activePrompt / activePromptVersion（避免 source CRUD 操作意外清空）
     */
    public void updateConfig(List<String> channelIds, List<String> guildIds,
                             List<String> authorIds, List<String> ignoreKeywords,
                             List<SourceConfig> sources,
                             String updatedBy, String reason) {
        long newVersion = version.incrementAndGet();

        MonitorConfig.Builder builder = MonitorConfig.newBuilder()
                .addAllChannelIds(channelIds != null ? channelIds : List.of())
                .addAllGuildIds(guildIds != null ? guildIds : List.of())
                .addAllAuthorIds(authorIds != null ? authorIds : List.of())
                .addAllIgnoreKeywords(ignoreKeywords != null ? ignoreKeywords : List.of())
                .addAllSources(sources != null ? sources : List.of())
                .setVersion(newVersion);

        // 保留 prompt 設定（source CRUD 不應清空 prompt）
        if (!currentConfig.getActivePrompt().isEmpty()) {
            builder.setActivePrompt(currentConfig.getActivePrompt());
            builder.setActivePromptVersion(currentConfig.getActivePromptVersion());
        }

        this.currentConfig = builder.build();

        ConfigUpdate update = ConfigUpdate.newBuilder()
                .setConfig(currentConfig)
                .setUpdatedBy(updatedBy)
                .setUpdateReason(reason)
                .setTimestamp(System.currentTimeMillis())
                .build();

        pushToObservers(update);
        log.info("Monitor 設定已更新 (v{}): channels={}, by={}", newVersion, channelIds, updatedBy);
    }

    /**
     * 更新 AI Prompt 並即時推送到所有已連線的 Python Monitor
     * 在 currentConfig 基礎上只換 prompt，其他欄位保留
     */
    public void updatePrompt(String promptContent, int promptVersion) {
        long newVersion = version.incrementAndGet();

        this.currentConfig = currentConfig.toBuilder()
                .setActivePrompt(promptContent)
                .setActivePromptVersion(promptVersion)
                .setVersion(newVersion)
                .build();

        ConfigUpdate update = ConfigUpdate.newBuilder()
                .setConfig(currentConfig)
                .setUpdatedBy("admin")
                .setUpdateReason("prompt_activated:v" + promptVersion)
                .setTimestamp(System.currentTimeMillis())
                .build();

        pushToObservers(update);
        log.info("Prompt 已推送 (v{}): config_version={}, prompt_chars={}",
                promptVersion, newVersion, promptContent.length());
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

    /**
     * 推送設定更新到所有已連線的 Python Monitor
     *
     * deadObservers：收集推送失敗的連線（Python 已斷線或網路異常），
     * 迭代結束後統一移除，避免 ConcurrentModificationException
     */
    private void pushToObservers(ConfigUpdate update) {
        List<StreamObserver<ConfigUpdate>> deadObservers = new ArrayList<>();
        for (StreamObserver<ConfigUpdate> observer : observers) {
            try {
                // 透過 gRPC Server Streaming 推送一筆 ConfigUpdate 給該 Python client
                // stream 保持開啟，下次設定變更時再推下一筆
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
