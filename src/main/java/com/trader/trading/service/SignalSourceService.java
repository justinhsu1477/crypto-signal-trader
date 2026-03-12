package com.trader.trading.service;

import com.trader.trading.dto.signalsource.*;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.entity.UserSignalSource;
import com.trader.trading.grpc.generated.MonitorConfig;
import com.trader.trading.repository.SignalSourceConfigRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.repository.UserSignalSourceRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 訊號來源管理服務 — CRUD + 用戶綁定 + 廣播路由 + 績效查詢
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalSourceService {

    private final SignalSourceConfigRepository sourceRepository;
    private final UserSignalSourceRepository userSourceRepository;
    private final TradeRepository tradeRepository;
    private final UserRepository userRepository;
    private final MonitorConfigStore monitorConfigStore;

    // ======================== 啟動同步 ========================

    /**
     * 啟動時從 DB 同步啟用來源到 MonitorConfigStore
     * 排序保證：MonitorConfigStore 是本 Service 的依賴 → Spring 先初始化它（env var 預設已載入）
     * 若 DB 有啟用 source → 用 DB 資料覆蓋；若 DB 無 source → 維持 env var 預設（向下相容）
     */
    @PostConstruct
    void syncOnStartup() {
        List<SignalSourceConfig> sources = sourceRepository.findByEnabledTrue();
        if (!sources.isEmpty()) {
            syncMonitorConfig("system", "startup_sync");
            log.info("啟動同步: 從 DB 載入 {} 個啟用來源到 MonitorConfigStore", sources.size());
        } else {
            log.info("啟動同步: DB 無啟用來源，維持環境變數預設頻道");
        }
    }

    // ======================== 來源 CRUD ========================

    public SignalSourceConfig createSource(CreateSignalSourceRequest req) {
        // 檢查 channelId + guildId 是否重複
        if (req.getChannelId() != null && req.getGuildId() != null
                && sourceRepository.existsByChannelIdAndGuildId(req.getChannelId(), req.getGuildId())) {
            throw new IllegalArgumentException("此 Channel ID + Guild ID 組合已存在");
        }

        SignalSourceConfig source = SignalSourceConfig.builder()
                .name(req.getName())
                .displayName(req.getDisplayName())
                .channelId(req.getChannelId())
                .guildId(req.getGuildId())
                .description(req.getDescription())
                .routingMode(parseRoutingMode(req.getRoutingMode()))
                .enabled(true)
                .build();

        SignalSourceConfig saved = sourceRepository.save(source);
        log.info("建立訊號來源: id={} name={} channelId={} routingMode={}",
                saved.getId(), saved.getName(), saved.getChannelId(), saved.getRoutingMode());
        syncMonitorConfig("admin", "source_created:" + saved.getName());
        return saved;
    }

    public SignalSourceConfig updateSource(Long id, UpdateSignalSourceRequest req) {
        SignalSourceConfig source = sourceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("訊號來源不存在: id=" + id));

        if (req.getName() != null) source.setName(req.getName());
        if (req.getDisplayName() != null) source.setDisplayName(req.getDisplayName());
        if (req.getDescription() != null) source.setDescription(req.getDescription());
        if (req.getEnabled() != null) source.setEnabled(req.getEnabled());
        if (req.getRoutingMode() != null) source.setRoutingMode(parseRoutingMode(req.getRoutingMode()));

        SignalSourceConfig saved = sourceRepository.save(source);
        log.info("更新訊號來源: id={} name={} routingMode={}", saved.getId(), saved.getName(), saved.getRoutingMode());
        syncMonitorConfig("admin", "source_updated:" + saved.getName());
        return saved;
    }

    @Transactional
    public void deleteSource(Long id) {
        if (!sourceRepository.existsById(id)) {
            throw new IllegalArgumentException("訊號來源不存在: id=" + id);
        }
        sourceRepository.deleteById(id);
        log.info("刪除訊號來源: id={}", id);
        syncMonitorConfig("admin", "source_deleted:" + id);
    }

    public List<SignalSourceResponse> getAllSources() {
        List<SignalSourceConfig> sources = sourceRepository.findAllByOrderByCreatedAtDesc();
        return sources.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public Optional<SignalSourceConfig> getSourceById(Long id) {
        return sourceRepository.findById(id);
    }

    // ======================== 用戶綁定 ========================

    /**
     * 綁定用戶到來源（MVP：一個用戶只能綁定一個來源）
     */
    @Transactional
    public List<UserAssignmentResponse> assignUsers(Long sourceId, List<String> userIds) {
        SignalSourceConfig source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("訊號來源不存在: id=" + sourceId));

        List<UserAssignmentResponse> results = new ArrayList<>();

        for (String userId : userIds) {
            // 檢查用戶是否存在
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("綁定跳過: 用戶不存在 userId={}", userId);
                continue;
            }

            // MVP：一個用戶只能綁定一個來源
            if (userSourceRepository.existsByUserIdAndSourceId(userId, sourceId)) {
                log.debug("綁定跳過: 已存在 userId={} sourceId={}", userId, sourceId);
                // 已綁定同一個來源，加入結果但不重複建立
                UserSignalSource existing = userSourceRepository.findByUserIdAndSourceId(userId, sourceId).get();
                results.add(toAssignmentResponse(existing, user));
                continue;
            }

            // 檢查是否已綁定其他來源
            if (userSourceRepository.existsByUserId(userId)) {
                throw new IllegalStateException(
                        String.format("用戶 %s (%s) 已綁定其他訊號來源，請先解除綁定",
                                user.getName() != null ? user.getName() : user.getEmail(), userId));
            }

            UserSignalSource assignment = UserSignalSource.builder()
                    .userId(userId)
                    .sourceId(sourceId)
                    .enabled(true)
                    .build();

            UserSignalSource saved = userSourceRepository.save(assignment);
            results.add(toAssignmentResponse(saved, user));
            log.info("綁定用戶: userId={} sourceId={} sourceName={}", userId, sourceId, source.getName());
        }

        return results;
    }

    @Transactional
    public void unassignUser(Long sourceId, String userId) {
        userSourceRepository.deleteByUserIdAndSourceId(userId, sourceId);
        log.info("解除綁定: userId={} sourceId={}", userId, sourceId);
    }

    public void toggleUserAssignment(Long sourceId, String userId, boolean enabled) {
        UserSignalSource assignment = userSourceRepository.findByUserIdAndSourceId(userId, sourceId)
                .orElseThrow(() -> new IllegalArgumentException("綁定不存在: userId=" + userId + " sourceId=" + sourceId));

        assignment.setEnabled(enabled);
        userSourceRepository.save(assignment);
        log.info("切換綁定狀態: userId={} sourceId={} enabled={}", userId, sourceId, enabled);
    }

    public List<UserAssignmentResponse> getUsersForSource(Long sourceId) {
        List<UserSignalSource> assignments = userSourceRepository.findBySourceId(sourceId);
        Map<String, User> userMap = userRepository.findAllById(
                assignments.stream().map(UserSignalSource::getUserId).toList()
        ).stream().collect(Collectors.toMap(User::getUserId, u -> u));

        return assignments.stream()
                .map(a -> toAssignmentResponse(a, userMap.get(a.getUserId())))
                .collect(Collectors.toList());
    }

    public List<SignalSourceUserResponse> getSourcesForUser(String userId) {
        List<Long> sourceIds = userSourceRepository.findEnabledSourceIdsByUserId(userId);
        if (sourceIds.isEmpty()) return List.of();

        return sourceRepository.findAllById(sourceIds).stream()
                .map(s -> SignalSourceUserResponse.builder()
                        .id(s.getId())
                        .displayName(s.getDisplayName())
                        .description(s.getDescription())
                        .enabled(s.isEnabled())
                        .build())
                .collect(Collectors.toList());
    }

    // ======================== 廣播路由（hot path） ========================

    /**
     * 解析訊號來源對應的綁定用戶 — BroadcastTradeService 呼叫
     *
     * @return Optional.empty() = 無匹配來源 或 GLOBAL 模式 → 觸發 fallback 全量廣播
     *         Optional.of(Set) = ASSIGNED 模式 → 只廣播給綁定用戶
     */
    public Optional<Set<String>> resolveTargetUserIds(String channelId, String guildId) {
        // 優先用 channelId + guildId 精確匹配
        Optional<SignalSourceConfig> source = sourceRepository.findByChannelIdAndGuildId(channelId, guildId);

        // fallback: 只用 channelId 匹配（guildId 可能為 null）
        if (source.isEmpty()) {
            source = sourceRepository.findByChannelId(channelId);
        }

        if (source.isEmpty() || !source.get().isEnabled()) {
            return Optional.empty();
        }

        // GLOBAL 模式 → 全員廣播（與「找不到 source」一樣的語意）
        if (source.get().getRoutingMode() == SignalSourceConfig.RoutingMode.GLOBAL) {
            return Optional.empty();
        }

        // ASSIGNED 模式 → 只回傳綁定用戶
        List<String> userIds = userSourceRepository.findEnabledUserIdsBySourceId(source.get().getId());
        return Optional.of(new HashSet<>(userIds));
    }

    /**
     * 根據 channelId 查找對應的 SignalSourceConfig ID（供 BroadcastLog 記錄用）
     */
    public Optional<Long> resolveSourceId(String channelId, String guildId) {
        Optional<SignalSourceConfig> source = sourceRepository.findByChannelIdAndGuildId(channelId, guildId);
        if (source.isEmpty()) {
            source = sourceRepository.findByChannelId(channelId);
        }
        return source.map(SignalSourceConfig::getId);
    }

    // ======================== 績效查詢 ========================

    public List<SignalSourcePerformanceDto> getAllSourcePerformances() {
        List<SignalSourceConfig> sources = sourceRepository.findByEnabledTrue();
        return sources.stream()
                .map(this::buildPerformance)
                .collect(Collectors.toList());
    }

    public SignalSourcePerformanceDto getSourcePerformance(Long sourceId) {
        SignalSourceConfig source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("訊號來源不存在: id=" + sourceId));
        return buildPerformance(source);
    }

    // ======================== 內部方法 ========================

    private SignalSourcePerformanceDto buildPerformance(SignalSourceConfig source) {
        if (source.getChannelId() == null) {
            return SignalSourcePerformanceDto.builder()
                    .sourceId(source.getId())
                    .name(source.getName())
                    .displayName(source.getDisplayName())
                    .build();
        }

        Object[] stats = tradeRepository.getSourcePerformanceStats(
                source.getChannelId(), source.getGuildId());

        if (stats == null || stats.length == 0 || stats[0] == null) {
            return SignalSourcePerformanceDto.builder()
                    .sourceId(source.getId())
                    .name(source.getName())
                    .displayName(source.getDisplayName())
                    .build();
        }

        long tradeCount = ((Number) stats[0]).longValue();
        long winCount = ((Number) stats[1]).longValue();
        double totalPnl = ((Number) stats[2]).doubleValue();
        double avgPnl = ((Number) stats[3]).doubleValue();
        double winRate = tradeCount > 0 ? winCount * 100.0 / tradeCount : 0;

        return SignalSourcePerformanceDto.builder()
                .sourceId(source.getId())
                .name(source.getName())
                .displayName(source.getDisplayName())
                .tradeCount(tradeCount)
                .winCount(winCount)
                .winRate(Math.round(winRate * 10.0) / 10.0)
                .totalPnl(totalPnl)
                .avgPnl(avgPnl)
                .build();
    }

    private SignalSourceResponse toResponse(SignalSourceConfig source) {
        int assignedCount = userSourceRepository.findBySourceId(source.getId()).size();
        return SignalSourceResponse.builder()
                .id(source.getId())
                .name(source.getName())
                .displayName(source.getDisplayName())
                .channelId(source.getChannelId())
                .guildId(source.getGuildId())
                .description(source.getDescription())
                .routingMode(source.getRoutingMode().name())
                .enabled(source.isEnabled())
                .assignedUserCount(assignedCount)
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }

    /**
     * 從 DB 啟用來源同步到 MonitorConfigStore → gRPC 推送給 Python
     * 保留既有的全局設定（authorIds、ignoreKeywords）
     */
    private void syncMonitorConfig(String updatedBy, String reason) {
        List<SignalSourceConfig> enabledSources = sourceRepository.findByEnabledTrue();

        List<String> channelIds = enabledSources.stream()
                .map(SignalSourceConfig::getChannelId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<String> guildIds = enabledSources.stream()
                .map(SignalSourceConfig::getGuildId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 保留既有的全局設定（authorIds、ignoreKeywords 不由 source 管理）
        MonitorConfig current = monitorConfigStore.getCurrentConfig();

        monitorConfigStore.updateConfig(
                channelIds,
                guildIds,
                current.getAuthorIdsList(),
                current.getIgnoreKeywordsList(),
                updatedBy,
                reason
        );

        log.info("Monitor config 已從 DB 同步: {} channels, {} guilds", channelIds.size(), guildIds.size());
    }

    private SignalSourceConfig.RoutingMode parseRoutingMode(String mode) {
        if (mode == null) return SignalSourceConfig.RoutingMode.ASSIGNED;
        try {
            return SignalSourceConfig.RoutingMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SignalSourceConfig.RoutingMode.ASSIGNED;
        }
    }

    private UserAssignmentResponse toAssignmentResponse(UserSignalSource assignment, User user) {
        return UserAssignmentResponse.builder()
                .id(assignment.getId())
                .userId(assignment.getUserId())
                .email(user != null ? user.getEmail() : null)
                .name(user != null ? user.getName() : null)
                .enabled(assignment.isEnabled())
                .assignedAt(assignment.getAssignedAt())
                .build();
    }
}
