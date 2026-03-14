package com.trader.trading.service;

import com.trader.trading.dto.signalsource.*;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.entity.UserSignalSource;
import com.trader.trading.grpc.generated.MonitorConfig;
import com.trader.trading.grpc.generated.SourceConfig;
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

import java.time.LocalDateTime;
import java.time.ZoneId;
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

        // 限制只能有一個 GLOBAL 來源
        SignalSourceConfig.RoutingMode routingMode = parseRoutingMode(req.getRoutingMode());
        if (routingMode == SignalSourceConfig.RoutingMode.GLOBAL
                && sourceRepository.existsByRoutingMode(SignalSourceConfig.RoutingMode.GLOBAL)) {
            throw new IllegalArgumentException("系統只能有一個全員廣播（GLOBAL）來源，請先將現有 GLOBAL 來源改為 ASSIGNED");
        }

        SignalSourceConfig source = SignalSourceConfig.builder()
                .name(req.getName())
                .displayName(req.getDisplayName())
                .channelId(req.getChannelId())
                .guildId(req.getGuildId())
                .description(req.getDescription())
                .routingMode(routingMode)
                .tradeMode(parseTradeMode(req.getTradeMode()))
                .riskMultiplier(req.getRiskMultiplier() != null ? req.getRiskMultiplier() : 1.0)
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
        if (req.getRoutingMode() != null) {
            SignalSourceConfig.RoutingMode newMode = parseRoutingMode(req.getRoutingMode());
            // 限制只能有一個 GLOBAL（排除自己）
            if (newMode == SignalSourceConfig.RoutingMode.GLOBAL
                    && source.getRoutingMode() != SignalSourceConfig.RoutingMode.GLOBAL
                    && sourceRepository.existsByRoutingModeAndIdNot(SignalSourceConfig.RoutingMode.GLOBAL, id)) {
                throw new IllegalArgumentException("系統只能有一個全員廣播（GLOBAL）來源，請先將現有 GLOBAL 來源改為 ASSIGNED");
            }
            source.setRoutingMode(newMode);
        }
        if (req.getTradeMode() != null) source.setTradeMode(parseTradeMode(req.getTradeMode()));
        if (req.getRiskMultiplier() != null) source.setRiskMultiplier(req.getRiskMultiplier());
        if (req.getPaperTradingEnabled() != null) source.setPaperTradingEnabled(req.getPaperTradingEnabled());

        // 模擬交易僅 SHADOW 模式有效 — 非 SHADOW 時自動關閉（防止設定矛盾）
        if (source.isPaperTradingEnabled() && source.getTradeMode() != SignalSourceConfig.TradeMode.SHADOW) {
            log.warn("paperTradingEnabled 僅 SHADOW 模式有效，自動關閉: sourceId={} tradeMode={}",
                    source.getId(), source.getTradeMode());
            source.setPaperTradingEnabled(false);
        }

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
     * @return Optional.empty() = 無匹配來源 或 GLOBAL 模式
     *         → 呼叫端需區分：resolvedSourceId 有值 = GLOBAL（排除已綁定 ASSIGNED 用戶），
     *           resolvedSourceId 無值 = 無匹配來源（全量廣播，向下相容）
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
     * 取得所有綁定到啟用 ASSIGNED 來源的用戶 ID
     * GLOBAL 來源廣播時用於排除已有專屬來源的用戶（一人一源原則）
     */
    public Set<String> getUserIdsBoundToAssignedSources() {
        return new HashSet<>(userSourceRepository.findUserIdsBoundToEnabledAssignedSources());
    }

    /**
     * 根據 channelId 查找對應的 SignalSourceConfig ID（供 BroadcastLog 記錄用）
     */
    public Optional<Long> resolveSourceId(String channelId, String guildId) {
        return resolveSource(channelId, guildId).map(SignalSourceConfig::getId);
    }

    /**
     * 根據 channelId + guildId 查找完整 SignalSourceConfig entity
     * BroadcastTradeService 用此方法取得 tradeMode 等設定
     */
    public Optional<SignalSourceConfig> resolveSource(String channelId, String guildId) {
        Optional<SignalSourceConfig> source = sourceRepository.findByChannelIdAndGuildId(channelId, guildId);
        if (source.isEmpty()) {
            source = sourceRepository.findByChannelId(channelId);
        }
        return source;
    }

    // ======================== 績效查詢 ========================

    public List<SignalSourcePerformanceDto> getAllSourcePerformances(String period) {
        LocalDateTime since = parsePeriod(period);
        List<SignalSourceConfig> sources = sourceRepository.findByEnabledTrue();
        return sources.stream()
                .map(s -> buildPerformance(s, since))
                .collect(Collectors.toList());
    }

    public SignalSourcePerformanceDto getSourcePerformance(Long sourceId, String period) {
        LocalDateTime since = parsePeriod(period);
        SignalSourceConfig source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("訊號來源不存在: id=" + sourceId));
        return buildPerformance(source, since);
    }

    // ======================== 內部方法 ========================

    private SignalSourcePerformanceDto buildPerformance(SignalSourceConfig source, LocalDateTime since) {
        SignalSourcePerformanceDto.SignalSourcePerformanceDtoBuilder builder =
                SignalSourcePerformanceDto.builder()
                        .sourceId(source.getId())
                        .name(source.getName())
                        .displayName(source.getDisplayName())
                        .tradeMode(source.getTradeMode().name());

        if (source.getChannelId() == null) {
            return builder.build();
        }

        // 真實交易績效
        parseTradeStats(builder, source, false, since);

        // 模擬交易績效（SHADOW 頻道）
        parseTradeStats(builder, source, true, since);

        return builder.build();
    }

    private void parseTradeStats(SignalSourcePerformanceDto.SignalSourcePerformanceDtoBuilder builder,
                                 SignalSourceConfig source, boolean simulated, LocalDateTime since) {
        Object[] stats = simulated
                ? tradeRepository.getSourcePaperTradeStats(source.getChannelId(), source.getGuildId(), since)
                : tradeRepository.getSourcePerformanceStats(source.getChannelId(), source.getGuildId(), since);
        Object[] row = extractRow(stats);
        if (row == null) return;

        long tradeCount = ((Number) row[0]).longValue();
        long winCount = ((Number) row[1]).longValue();
        double totalPnl = ((Number) row[2]).doubleValue();
        double avgPnl = ((Number) row[3]).doubleValue();
        double maxWin = ((Number) row[4]).doubleValue();
        double maxLoss = ((Number) row[5]).doubleValue();
        double grossWins = ((Number) row[6]).doubleValue();
        double grossLosses = ((Number) row[7]).doubleValue();
        double winRate = tradeCount > 0 ? winCount * 100.0 / tradeCount : 0;
        double profitFactor = grossLosses > 0 ? grossWins / grossLosses : (grossWins > 0 ? Double.MAX_VALUE : 0);

        // 連勝/連虧計算
        int[] streaks = calculateStreaks(source.getChannelId(), source.getGuildId(), simulated, since);

        if (simulated) {
            builder.paperTradeCount(tradeCount)
                    .paperWinCount(winCount)
                    .paperWinRate(round1(winRate))
                    .paperTotalPnl(round2(totalPnl))
                    .paperAvgPnl(round2(avgPnl))
                    .paperMaxWin(round2(maxWin))
                    .paperMaxLoss(round2(maxLoss))
                    .paperProfitFactor(round2(profitFactor))
                    .paperMaxConsecutiveWins(streaks[0])
                    .paperMaxConsecutiveLosses(streaks[1]);
        } else {
            builder.tradeCount(tradeCount)
                    .winCount(winCount)
                    .winRate(round1(winRate))
                    .totalPnl(round2(totalPnl))
                    .avgPnl(round2(avgPnl))
                    .maxWin(round2(maxWin))
                    .maxLoss(round2(maxLoss))
                    .profitFactor(round2(profitFactor))
                    .maxConsecutiveWins(streaks[0])
                    .maxConsecutiveLosses(streaks[1]);
        }
    }

    /**
     * 計算最大連勝/連虧 — 從有序交易序列遍歷
     *
     * @return int[]{maxConsecutiveWins, maxConsecutiveLosses}
     */
    int[] calculateStreaks(String channelId, String guildId, boolean simulated, LocalDateTime since) {
        List<Object> sequence = tradeRepository.getSourceTradeSequence(channelId, guildId, simulated, since);
        int maxWins = 0, maxLosses = 0, curWins = 0, curLosses = 0;
        for (Object obj : sequence) {
            double pnl = ((Number) obj).doubleValue();
            if (pnl > 0) {
                curWins++;
                curLosses = 0;
                maxWins = Math.max(maxWins, curWins);
            } else {
                curLosses++;
                curWins = 0;
                maxLosses = Math.max(maxLosses, curLosses);
            }
        }
        return new int[]{maxWins, maxLosses};
    }

    /**
     * 將 period 字串轉為 LocalDateTime（null = 全部）
     */
    LocalDateTime parsePeriod(String period) {
        if (period == null || "all".equalsIgnoreCase(period)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
        return switch (period.toLowerCase()) {
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            case "90d" -> now.minusDays(90);
            default -> null;
        };
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 從 native query 結果提取資料列（處理 JPA 包裝的 Object[][] 情況）
     */
    private Object[] extractRow(Object[] stats) {
        if (stats == null || stats.length == 0 || stats[0] == null) return null;
        Object[] row = (stats[0] instanceof Object[]) ? (Object[]) stats[0] : stats;
        if (row[0] == null || ((Number) row[0]).longValue() == 0) return null;
        return row;
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
                .tradeMode(source.getTradeMode().name())
                .riskMultiplier(source.getRiskMultiplier())
                .paperTradingEnabled(source.isPaperTradingEnabled())
                .enabled(source.isEnabled())
                .assignedUserCount(assignedCount)
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }

    /**
     * 從 DB 啟用來源同步到 MonitorConfigStore → gRPC 推送給 Python
     *
     * 合併策略：
     * - DB 有 GLOBAL 來源 → 只用 DB 頻道（GLOBAL 取代 env var 預設）
     * - DB 無 GLOBAL 來源 → DB 頻道 + env var 預設頻道（預設頻道服務未綁定用戶）
     * - 保留既有的全局設定（authorIds、ignoreKeywords 不由 source 管理）
     */
    private void syncMonitorConfig(String updatedBy, String reason) {
        List<SignalSourceConfig> enabledSources = sourceRepository.findByEnabledTrue();

        boolean hasGlobal = enabledSources.stream()
                .anyMatch(s -> s.getRoutingMode() == SignalSourceConfig.RoutingMode.GLOBAL);

        List<String> dbChannelIds = enabledSources.stream()
                .map(SignalSourceConfig::getChannelId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // 無 GLOBAL → 保留 env var 預設頻道（服務未綁定用戶）
        if (!hasGlobal) {
            List<String> defaults = monitorConfigStore.getDefaultChannelIdList();
            for (String defaultId : defaults) {
                if (!dbChannelIds.contains(defaultId)) {
                    dbChannelIds.add(defaultId);
                }
            }
            log.info("無 GLOBAL 來源，合併 env var 預設頻道: DB={} + defaults={}", dbChannelIds.size() - defaults.size(), defaults.size());
        }

        List<String> guildIds = enabledSources.stream()
                .map(SignalSourceConfig::getGuildId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 建構 proto SourceConfig 清單（per-source metadata 供 Python 使用）
        List<SourceConfig> protoSources = enabledSources.stream()
                .map(this::toProtoSource)
                .toList();

        MonitorConfig current = monitorConfigStore.getCurrentConfig();

        monitorConfigStore.updateConfig(
                dbChannelIds,
                guildIds,
                current.getAuthorIdsList(),
                current.getIgnoreKeywordsList(),
                protoSources,
                updatedBy,
                reason
        );

        log.info("Monitor config 已從 DB 同步: {} channels (hasGlobal={}), {} guilds, {} sources",
                dbChannelIds.size(), hasGlobal, guildIds.size(), protoSources.size());
    }

    /**
     * Entity → proto SourceConfig 轉換（gRPC 推送給 Python）
     */
    private SourceConfig toProtoSource(SignalSourceConfig entity) {
        return SourceConfig.newBuilder()
                .setId(entity.getId())
                .setChannelId(entity.getChannelId() != null ? entity.getChannelId() : "")
                .setGuildId(entity.getGuildId() != null ? entity.getGuildId() : "")
                .setName(entity.getName() != null ? entity.getName() : "")
                .setDisplayName(entity.getDisplayName() != null ? entity.getDisplayName() : "")
                .setRoutingMode(entity.getRoutingMode().name())
                .setTradeMode(entity.getTradeMode().name())
                .setRiskMultiplier(entity.getRiskMultiplier())
                .setCustomPrompt(entity.getCustomPrompt() != null ? entity.getCustomPrompt() : "")
                .build();
    }

    private SignalSourceConfig.RoutingMode parseRoutingMode(String mode) {
        if (mode == null) return SignalSourceConfig.RoutingMode.ASSIGNED;
        try {
            return SignalSourceConfig.RoutingMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SignalSourceConfig.RoutingMode.ASSIGNED;
        }
    }

    private SignalSourceConfig.TradeMode parseTradeMode(String mode) {
        if (mode == null) return SignalSourceConfig.TradeMode.AUTO;
        try {
            return SignalSourceConfig.TradeMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SignalSourceConfig.TradeMode.AUTO;
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
