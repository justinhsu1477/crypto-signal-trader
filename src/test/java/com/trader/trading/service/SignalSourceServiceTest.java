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
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SignalSourceService 單元測試
 *
 * 覆蓋：
 * - 來源 CRUD（建立/更新/刪除/查詢）
 * - 用戶綁定（綁定/解綁/切換/MVP 一對一限制）
 * - 廣播路由（resolveTargetUserIds）— 含 GLOBAL / ASSIGNED 路由模式
 * - 績效查詢（buildPerformance）
 * - 啟動同步（@PostConstruct syncOnStartup）
 * - CRUD 自動同步 MonitorConfigStore
 */
class SignalSourceServiceTest {

    private SignalSourceConfigRepository sourceRepository;
    private UserSignalSourceRepository userSourceRepository;
    private TradeRepository tradeRepository;
    private UserRepository userRepository;
    private MonitorConfigStore monitorConfigStore;
    private SignalSourceService service;

    @BeforeEach
    void setUp() {
        sourceRepository = mock(SignalSourceConfigRepository.class);
        userSourceRepository = mock(UserSignalSourceRepository.class);
        tradeRepository = mock(TradeRepository.class);
        userRepository = mock(UserRepository.class);
        monitorConfigStore = mock(MonitorConfigStore.class);

        // 預設 getCurrentConfig 回傳空 config（syncMonitorConfig 呼叫時需要）
        when(monitorConfigStore.getCurrentConfig()).thenReturn(
                MonitorConfig.newBuilder().build());
        // 預設 env var 預設頻道為空（測試可個別覆蓋）
        when(monitorConfigStore.getDefaultChannelIdList()).thenReturn(List.of());

        service = new SignalSourceService(sourceRepository, userSourceRepository,
                tradeRepository, userRepository, monitorConfigStore);
    }

    // ==================== 來源 CRUD ====================

    @Nested
    @DisplayName("來源 CRUD")
    class CrudTests {

        @Test
        @DisplayName("建立來源 — 成功儲存並回傳")
        void createSource_success() {
            CreateSignalSourceRequest req = CreateSignalSourceRequest.builder()
                    .name("陳哥VIP群").displayName("訊號源 A")
                    .channelId("ch-1").guildId("g-1").description("備註").build();

            when(sourceRepository.existsByChannelIdAndGuildId("ch-1", "g-1")).thenReturn(false);
            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> {
                SignalSourceConfig s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });

            SignalSourceConfig result = service.createSource(req);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getName()).isEqualTo("陳哥VIP群");
            assertThat(result.isEnabled()).isTrue();
            verify(sourceRepository).save(any(SignalSourceConfig.class));
        }

        @Test
        @DisplayName("建立來源 — channelId + guildId 重複時拋出例外")
        void createSource_duplicateThrows() {
            CreateSignalSourceRequest req = CreateSignalSourceRequest.builder()
                    .name("重複").displayName("X")
                    .channelId("ch-1").guildId("g-1").build();

            when(sourceRepository.existsByChannelIdAndGuildId("ch-1", "g-1")).thenReturn(true);

            assertThatThrownBy(() -> service.createSource(req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已存在");

            verify(sourceRepository, never()).save(any());
        }

        @Test
        @DisplayName("建立來源 — channelId 或 guildId 為 null 時跳過重複檢查")
        void createSource_nullChannelSkipsDuplicateCheck() {
            CreateSignalSourceRequest req = CreateSignalSourceRequest.builder()
                    .name("手動").displayName("手動源").build();

            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> {
                SignalSourceConfig s = inv.getArgument(0);
                s.setId(2L);
                return s;
            });

            SignalSourceConfig result = service.createSource(req);

            assertThat(result.getId()).isEqualTo(2L);
            verify(sourceRepository, never()).existsByChannelIdAndGuildId(any(), any());
        }

        @Test
        @DisplayName("更新來源 — 部分欄位更新")
        void updateSource_partialUpdate() {
            SignalSourceConfig existing = SignalSourceConfig.builder()
                    .id(1L).name("舊名").displayName("舊顯示").enabled(true).build();
            UpdateSignalSourceRequest req = UpdateSignalSourceRequest.builder()
                    .name("新名").enabled(false).build();

            when(sourceRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            SignalSourceConfig result = service.updateSource(1L, req);

            assertThat(result.getName()).isEqualTo("新名");
            assertThat(result.getDisplayName()).isEqualTo("舊顯示"); // 未傳入，不變
            assertThat(result.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("更新來源 — 不存在時拋出例外")
        void updateSource_notFoundThrows() {
            when(sourceRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateSource(99L, new UpdateSignalSourceRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在");
        }

        @Test
        @DisplayName("刪除來源 — 成功刪除")
        void deleteSource_success() {
            when(sourceRepository.existsById(1L)).thenReturn(true);

            service.deleteSource(1L);

            verify(sourceRepository).deleteById(1L);
        }

        @Test
        @DisplayName("刪除來源 — 不存在時拋出例外")
        void deleteSource_notFoundThrows() {
            when(sourceRepository.existsById(99L)).thenReturn(false);

            assertThatThrownBy(() -> service.deleteSource(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在");

            verify(sourceRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("取得所有來源 — 轉換為 SignalSourceResponse 列表")
        void getAllSources_returnsResponseList() {
            SignalSourceConfig s1 = SignalSourceConfig.builder()
                    .id(1L).name("A").displayName("來源A").channelId("ch-1")
                    .guildId("g-1").enabled(true).build();
            SignalSourceConfig s2 = SignalSourceConfig.builder()
                    .id(2L).name("B").displayName("來源B").enabled(false).build();

            when(sourceRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(s1, s2));
            when(userSourceRepository.findBySourceId(1L)).thenReturn(List.of(
                    UserSignalSource.builder().id(1L).userId("u1").sourceId(1L).build()));
            when(userSourceRepository.findBySourceId(2L)).thenReturn(List.of());

            List<SignalSourceResponse> results = service.getAllSources();

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getAssignedUserCount()).isEqualTo(1);
            assertThat(results.get(1).getAssignedUserCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("建立 GLOBAL 來源 — 已存在 GLOBAL 時拋出例外")
        void createSource_duplicateGlobalThrows() {
            CreateSignalSourceRequest req = CreateSignalSourceRequest.builder()
                    .name("新GLOBAL").displayName("X").channelId("ch-2").guildId("g-2")
                    .routingMode("GLOBAL").build();

            when(sourceRepository.existsByChannelIdAndGuildId("ch-2", "g-2")).thenReturn(false);
            when(sourceRepository.existsByRoutingMode(SignalSourceConfig.RoutingMode.GLOBAL)).thenReturn(true);

            assertThatThrownBy(() -> service.createSource(req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("只能有一個");

            verify(sourceRepository, never()).save(any());
        }

        @Test
        @DisplayName("建立 GLOBAL 來源 — 無既有 GLOBAL 時成功")
        void createSource_firstGlobalSucceeds() {
            CreateSignalSourceRequest req = CreateSignalSourceRequest.builder()
                    .name("陳哥").displayName("陳哥VIP").channelId("ch-1").guildId("g-1")
                    .routingMode("GLOBAL").build();

            when(sourceRepository.existsByChannelIdAndGuildId("ch-1", "g-1")).thenReturn(false);
            when(sourceRepository.existsByRoutingMode(SignalSourceConfig.RoutingMode.GLOBAL)).thenReturn(false);
            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> {
                SignalSourceConfig s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });

            SignalSourceConfig result = service.createSource(req);

            assertThat(result.getRoutingMode()).isEqualTo(SignalSourceConfig.RoutingMode.GLOBAL);
            verify(sourceRepository).save(any());
        }

        @Test
        @DisplayName("更新來源為 GLOBAL — 已存在其他 GLOBAL 時拋出例外")
        void updateSource_switchToGlobalWhenAnotherExistsThrows() {
            SignalSourceConfig existing = SignalSourceConfig.builder()
                    .id(2L).name("B源").routingMode(SignalSourceConfig.RoutingMode.ASSIGNED).enabled(true).build();
            UpdateSignalSourceRequest req = UpdateSignalSourceRequest.builder()
                    .routingMode("GLOBAL").build();

            when(sourceRepository.findById(2L)).thenReturn(Optional.of(existing));
            when(sourceRepository.existsByRoutingModeAndIdNot(SignalSourceConfig.RoutingMode.GLOBAL, 2L)).thenReturn(true);

            assertThatThrownBy(() -> service.updateSource(2L, req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("只能有一個");

            verify(sourceRepository, never()).save(any());
        }

        @Test
        @DisplayName("更新來源 — GLOBAL 改自己的其他欄位不觸發限制")
        void updateSource_globalUpdatesOwnFieldsSucceeds() {
            SignalSourceConfig existing = SignalSourceConfig.builder()
                    .id(1L).name("陳哥").routingMode(SignalSourceConfig.RoutingMode.GLOBAL).enabled(true).build();
            UpdateSignalSourceRequest req = UpdateSignalSourceRequest.builder()
                    .displayName("新顯示名").build();  // 不改 routingMode

            when(sourceRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            SignalSourceConfig result = service.updateSource(1L, req);

            assertThat(result.getDisplayName()).isEqualTo("新顯示名");
            assertThat(result.getRoutingMode()).isEqualTo(SignalSourceConfig.RoutingMode.GLOBAL);
        }
    }

    // ==================== 用戶綁定 ====================

    @Nested
    @DisplayName("用戶綁定")
    class UserAssignmentTests {

        private SignalSourceConfig source;
        private User user;

        @BeforeEach
        void setUpSource() {
            source = SignalSourceConfig.builder()
                    .id(1L).name("VIP").displayName("訊號源 A").enabled(true).build();
            user = User.builder()
                    .userId("u1").email("test@example.com").name("測試用戶").build();
        }

        @Test
        @DisplayName("綁定用戶 — 成功綁定新用戶")
        void assignUsers_success() {
            when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(userRepository.findById("u1")).thenReturn(Optional.of(user));
            when(userSourceRepository.existsByUserIdAndSourceId("u1", 1L)).thenReturn(false);
            when(userSourceRepository.existsByUserId("u1")).thenReturn(false);
            when(userSourceRepository.save(any(UserSignalSource.class))).thenAnswer(inv -> {
                UserSignalSource a = inv.getArgument(0);
                a.setId(10L);
                return a;
            });

            List<UserAssignmentResponse> results = service.assignUsers(1L, List.of("u1"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getUserId()).isEqualTo("u1");
            assertThat(results.get(0).getEmail()).isEqualTo("test@example.com");
            verify(userSourceRepository).save(any(UserSignalSource.class));
        }

        @Test
        @DisplayName("綁定用戶 — 已綁定同來源則跳過不重複建立")
        void assignUsers_skipAlreadyAssignedSameSource() {
            UserSignalSource existing = UserSignalSource.builder()
                    .id(10L).userId("u1").sourceId(1L).enabled(true).build();

            when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(userRepository.findById("u1")).thenReturn(Optional.of(user));
            when(userSourceRepository.existsByUserIdAndSourceId("u1", 1L)).thenReturn(true);
            when(userSourceRepository.findByUserIdAndSourceId("u1", 1L)).thenReturn(Optional.of(existing));

            List<UserAssignmentResponse> results = service.assignUsers(1L, List.of("u1"));

            assertThat(results).hasSize(1);
            verify(userSourceRepository, never()).save(any()); // 不重複儲存
        }

        @Test
        @DisplayName("綁定用戶 — MVP 一對一：已綁定其他來源時拋出例外")
        void assignUsers_alreadyBoundToAnotherSourceThrows() {
            when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(userRepository.findById("u1")).thenReturn(Optional.of(user));
            when(userSourceRepository.existsByUserIdAndSourceId("u1", 1L)).thenReturn(false);
            when(userSourceRepository.existsByUserId("u1")).thenReturn(true); // 已綁定其他來源

            assertThatThrownBy(() -> service.assignUsers(1L, List.of("u1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已綁定其他訊號來源");
        }

        @Test
        @DisplayName("綁定用戶 — 用戶不存在則跳過")
        void assignUsers_skipNonExistentUser() {
            when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(userRepository.findById("ghost")).thenReturn(Optional.empty());

            List<UserAssignmentResponse> results = service.assignUsers(1L, List.of("ghost"));

            assertThat(results).isEmpty();
            verify(userSourceRepository, never()).save(any());
        }

        @Test
        @DisplayName("綁定用戶 — 來源不存在時拋出例外")
        void assignUsers_sourceNotFoundThrows() {
            when(sourceRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assignUsers(99L, List.of("u1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在");
        }

        @Test
        @DisplayName("解除綁定 — 呼叫 deleteByUserIdAndSourceId")
        void unassignUser_callsDelete() {
            service.unassignUser(1L, "u1");

            verify(userSourceRepository).deleteByUserIdAndSourceId("u1", 1L);
        }

        @Test
        @DisplayName("切換綁定狀態 — 成功切換 enabled")
        void toggleUserAssignment_success() {
            UserSignalSource assignment = UserSignalSource.builder()
                    .id(10L).userId("u1").sourceId(1L).enabled(true).build();

            when(userSourceRepository.findByUserIdAndSourceId("u1", 1L)).thenReturn(Optional.of(assignment));

            service.toggleUserAssignment(1L, "u1", false);

            assertThat(assignment.isEnabled()).isFalse();
            verify(userSourceRepository).save(assignment);
        }

        @Test
        @DisplayName("切換綁定狀態 — 綁定不存在時拋出例外")
        void toggleUserAssignment_notFoundThrows() {
            when(userSourceRepository.findByUserIdAndSourceId("u1", 99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.toggleUserAssignment(99L, "u1", true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("綁定不存在");
        }
    }

    // ==================== 廣播路由 ====================

    @Nested
    @DisplayName("廣播路由 resolveTargetUserIds")
    class BroadcastRoutingTests {

        @Test
        @DisplayName("channelId + guildId 精確匹配 — 回傳綁定用戶 ID")
        void resolve_exactMatch() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(1L).channelId("ch-1").guildId("g-1").enabled(true).build();

            when(sourceRepository.findByChannelIdAndGuildId("ch-1", "g-1")).thenReturn(Optional.of(source));
            when(userSourceRepository.findEnabledUserIdsBySourceId(1L)).thenReturn(List.of("u1", "u2"));

            Optional<Set<String>> result = service.resolveTargetUserIds("ch-1", "g-1");

            assertThat(result).isPresent();
            assertThat(result.get()).containsExactlyInAnyOrder("u1", "u2");
        }

        @Test
        @DisplayName("channelId-only fallback — guildId 無匹配時退回 channelId 查詢")
        void resolve_channelIdFallback() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(2L).channelId("ch-2").enabled(true).build();

            when(sourceRepository.findByChannelIdAndGuildId("ch-2", "g-x")).thenReturn(Optional.empty());
            when(sourceRepository.findByChannelId("ch-2")).thenReturn(Optional.of(source));
            when(userSourceRepository.findEnabledUserIdsBySourceId(2L)).thenReturn(List.of("u3"));

            Optional<Set<String>> result = service.resolveTargetUserIds("ch-2", "g-x");

            assertThat(result).isPresent();
            assertThat(result.get()).containsExactly("u3");
        }

        @Test
        @DisplayName("無匹配來源 — 回傳 Optional.empty()")
        void resolve_noMatch() {
            when(sourceRepository.findByChannelIdAndGuildId("unknown", "unknown")).thenReturn(Optional.empty());
            when(sourceRepository.findByChannelId("unknown")).thenReturn(Optional.empty());

            Optional<Set<String>> result = service.resolveTargetUserIds("unknown", "unknown");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("來源已停用 — 回傳 Optional.empty()")
        void resolve_sourceDisabled() {
            SignalSourceConfig disabled = SignalSourceConfig.builder()
                    .id(3L).channelId("ch-3").guildId("g-3").enabled(false).build();

            when(sourceRepository.findByChannelIdAndGuildId("ch-3", "g-3")).thenReturn(Optional.of(disabled));

            Optional<Set<String>> result = service.resolveTargetUserIds("ch-3", "g-3");

            assertThat(result).isEmpty();
            verify(userSourceRepository, never()).findEnabledUserIdsBySourceId(any());
        }

        @Test
        @DisplayName("GLOBAL 模式 — 回傳 Optional.empty()（由呼叫端判斷排除邏輯）")
        void resolve_globalMode_returnsEmpty() {
            SignalSourceConfig globalSource = SignalSourceConfig.builder()
                    .id(4L).channelId("ch-global").guildId("g-global").enabled(true)
                    .routingMode(SignalSourceConfig.RoutingMode.GLOBAL).build();

            when(sourceRepository.findByChannelIdAndGuildId("ch-global", "g-global"))
                    .thenReturn(Optional.of(globalSource));

            Optional<Set<String>> result = service.resolveTargetUserIds("ch-global", "g-global");

            assertThat(result).isEmpty(); // GLOBAL → empty，呼叫端用 resolvedSourceId 區分
            verify(userSourceRepository, never()).findEnabledUserIdsBySourceId(any());
        }

        @Test
        @DisplayName("ASSIGNED 模式 — 回傳綁定用戶（與預設行為一致）")
        void resolve_assignedMode_returnsBoundUsers() {
            SignalSourceConfig assignedSource = SignalSourceConfig.builder()
                    .id(5L).channelId("ch-assigned").guildId("g-assigned").enabled(true)
                    .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED).build();

            when(sourceRepository.findByChannelIdAndGuildId("ch-assigned", "g-assigned"))
                    .thenReturn(Optional.of(assignedSource));
            when(userSourceRepository.findEnabledUserIdsBySourceId(5L))
                    .thenReturn(List.of("u1", "u2"));

            Optional<Set<String>> result = service.resolveTargetUserIds("ch-assigned", "g-assigned");

            assertThat(result).isPresent();
            assertThat(result.get()).containsExactlyInAnyOrder("u1", "u2");
        }
    }

    // ==================== GLOBAL 排除查詢 ====================

    @Nested
    @DisplayName("getUserIdsBoundToAssignedSources")
    class BoundToAssignedSourcesTests {

        @Test
        @DisplayName("有綁定到 ASSIGNED 來源的用戶 — 回傳 userId 集合")
        void returnsUserIdsBoundToAssignedSources() {
            when(userSourceRepository.findUserIdsBoundToEnabledAssignedSources())
                    .thenReturn(List.of("u1", "u2"));

            Set<String> result = service.getUserIdsBoundToAssignedSources();

            assertThat(result).containsExactlyInAnyOrder("u1", "u2");
        }

        @Test
        @DisplayName("無綁定用戶 — 回傳空集合")
        void returnsEmptyWhenNoBoundUsers() {
            when(userSourceRepository.findUserIdsBoundToEnabledAssignedSources())
                    .thenReturn(List.of());

            Set<String> result = service.getUserIdsBoundToAssignedSources();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("重複 userId 自動去重")
        void deduplicatesUserIds() {
            when(userSourceRepository.findUserIdsBoundToEnabledAssignedSources())
                    .thenReturn(List.of("u1", "u1", "u2"));

            Set<String> result = service.getUserIdsBoundToAssignedSources();

            assertThat(result).hasSize(2);
            assertThat(result).containsExactlyInAnyOrder("u1", "u2");
        }
    }

    // ==================== 績效查詢 ====================

    @Nested
    @DisplayName("績效查詢")
    class PerformanceTests {

        @Test
        @DisplayName("有交易統計 — 正確計算勝率與 PnL")
        void getSourcePerformance_withStats() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(1L).name("VIP").displayName("訊號源 A")
                    .channelId("ch-1").guildId("g-1").build();

            // stats: [tradeCount, winCount, totalPnl, avgPnl]
            Object[] stats = new Object[]{10L, 7L, 350.5, 35.05};

            when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(tradeRepository.getSourcePerformanceStats("ch-1", "g-1")).thenReturn(stats);

            SignalSourcePerformanceDto result = service.getSourcePerformance(1L);

            assertThat(result.getSourceId()).isEqualTo(1L);
            assertThat(result.getTradeCount()).isEqualTo(10L);
            assertThat(result.getWinCount()).isEqualTo(7L);
            assertThat(result.getWinRate()).isEqualTo(70.0);
            assertThat(result.getTotalPnl()).isEqualTo(350.5);
            assertThat(result.getAvgPnl()).isEqualTo(35.05);
        }

        @Test
        @DisplayName("channelId 為 null — 回傳空統計")
        void getSourcePerformance_nullChannelId() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(2L).name("手動").displayName("手動源").channelId(null).build();

            when(sourceRepository.findById(2L)).thenReturn(Optional.of(source));

            SignalSourcePerformanceDto result = service.getSourcePerformance(2L);

            assertThat(result.getSourceId()).isEqualTo(2L);
            assertThat(result.getTradeCount()).isEqualTo(0L);
            assertThat(result.getTotalPnl()).isEqualTo(0.0);
            verify(tradeRepository, never()).getSourcePerformanceStats(any(), any());
        }

        @Test
        @DisplayName("stats 為 null — 回傳空統計")
        void getSourcePerformance_nullStats() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(3L).name("新").displayName("新源").channelId("ch-3").guildId("g-3").build();

            when(sourceRepository.findById(3L)).thenReturn(Optional.of(source));
            when(tradeRepository.getSourcePerformanceStats("ch-3", "g-3")).thenReturn(null);

            SignalSourcePerformanceDto result = service.getSourcePerformance(3L);

            assertThat(result.getTradeCount()).isEqualTo(0L);
        }

        @Test
        @DisplayName("來源不存在 — 拋出例外")
        void getSourcePerformance_notFoundThrows() {
            when(sourceRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSourcePerformance(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在");
        }
    }

    // ==================== MonitorConfig 同步 ====================

    @Nested
    @DisplayName("MonitorConfig 同步")
    class MonitorConfigSyncTests {

        @Test
        @DisplayName("建立來源後觸發 syncMonitorConfig")
        void createSource_triggersSync() {
            CreateSignalSourceRequest req = CreateSignalSourceRequest.builder()
                    .name("新來源").displayName("顯示名")
                    .channelId("ch-new").guildId("g-new").build();

            when(sourceRepository.existsByChannelIdAndGuildId("ch-new", "g-new")).thenReturn(false);
            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> {
                SignalSourceConfig s = inv.getArgument(0);
                s.setId(10L);
                return s;
            });
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(
                    SignalSourceConfig.builder().id(10L).channelId("ch-new").guildId("g-new").enabled(true).build()
            ));

            service.createSource(req);

            verify(monitorConfigStore).updateConfig(anyList(), anyList(), anyList(), anyList(), eq("admin"), contains("source_created"));
        }

        @Test
        @DisplayName("更新來源後觸發 syncMonitorConfig")
        void updateSource_triggersSync() {
            SignalSourceConfig existing = SignalSourceConfig.builder()
                    .id(1L).name("舊名").displayName("舊").enabled(true).build();
            UpdateSignalSourceRequest req = UpdateSignalSourceRequest.builder().name("新名").build();

            when(sourceRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> inv.getArgument(0));
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(existing));

            service.updateSource(1L, req);

            verify(monitorConfigStore).updateConfig(anyList(), anyList(), anyList(), anyList(), eq("admin"), contains("source_updated"));
        }

        @Test
        @DisplayName("刪除來源後觸發 syncMonitorConfig")
        void deleteSource_triggersSync() {
            when(sourceRepository.existsById(1L)).thenReturn(true);
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of());

            service.deleteSource(1L);

            verify(sourceRepository).deleteById(1L);
            verify(monitorConfigStore).updateConfig(anyList(), anyList(), anyList(), anyList(), eq("admin"), contains("source_deleted"));
        }

        @Test
        @DisplayName("syncOnStartup — DB 有啟用 source 時觸發同步")
        void syncOnStartup_withSources() {
            SignalSourceConfig source = SignalSourceConfig.builder()
                    .id(1L).channelId("ch-1").guildId("g-1").enabled(true).build();
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(source));

            service.syncOnStartup();

            // findByEnabledTrue 被呼叫：syncOnStartup 1次 + syncMonitorConfig 內部 1次
            verify(sourceRepository, atLeast(1)).findByEnabledTrue();
            verify(monitorConfigStore).updateConfig(anyList(), anyList(), anyList(), anyList(), eq("system"), eq("startup_sync"));
        }

        @Test
        @DisplayName("syncOnStartup — DB 無啟用 source 時不觸發同步")
        void syncOnStartup_noSources() {
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of());

            service.syncOnStartup();

            verify(monitorConfigStore, never()).updateConfig(anyList(), anyList(), anyList(), anyList(), anyString(), anyString());
        }

        @Test
        @DisplayName("sync — 無 GLOBAL 來源時合併 env var 預設頻道")
        @SuppressWarnings("unchecked")
        void sync_noGlobal_mergesEnvVarDefaults() {
            // DB 只有 ASSIGNED 來源
            SignalSourceConfig assigned = SignalSourceConfig.builder()
                    .id(1L).channelId("ch-assigned").guildId("g-1")
                    .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED).enabled(true).build();
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(assigned));
            when(monitorConfigStore.getDefaultChannelIdList()).thenReturn(List.of("ch-env-default"));

            service.syncOnStartup();

            // 驗證 channelIds 包含 DB + env var 預設
            var captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(monitorConfigStore).updateConfig(captor.capture(), anyList(), anyList(), anyList(), eq("system"), eq("startup_sync"));
            List<String> channelIds = captor.getValue();
            assertThat(channelIds).containsExactlyInAnyOrder("ch-assigned", "ch-env-default");
        }

        @Test
        @DisplayName("sync — 有 GLOBAL 來源時不合併 env var 預設（GLOBAL 取代）")
        @SuppressWarnings("unchecked")
        void sync_withGlobal_doesNotMergeEnvVarDefaults() {
            SignalSourceConfig global = SignalSourceConfig.builder()
                    .id(1L).channelId("ch-global").guildId("g-1")
                    .routingMode(SignalSourceConfig.RoutingMode.GLOBAL).enabled(true).build();
            SignalSourceConfig assigned = SignalSourceConfig.builder()
                    .id(2L).channelId("ch-assigned").guildId("g-2")
                    .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED).enabled(true).build();
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(global, assigned));
            when(monitorConfigStore.getDefaultChannelIdList()).thenReturn(List.of("ch-env-default"));

            service.syncOnStartup();

            var captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(monitorConfigStore).updateConfig(captor.capture(), anyList(), anyList(), anyList(), eq("system"), eq("startup_sync"));
            List<String> channelIds = captor.getValue();
            // 只有 DB 頻道，不含 env var 預設
            assertThat(channelIds).containsExactlyInAnyOrder("ch-global", "ch-assigned");
            assertThat(channelIds).doesNotContain("ch-env-default");
        }

        @Test
        @DisplayName("sync — 無 GLOBAL 時 env var 預設頻道不重複加入")
        @SuppressWarnings("unchecked")
        void sync_noGlobal_noDuplicateDefaults() {
            // DB 的 channelId 和 env var 預設重疊
            SignalSourceConfig assigned = SignalSourceConfig.builder()
                    .id(1L).channelId("ch-same").guildId("g-1")
                    .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED).enabled(true).build();
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(assigned));
            when(monitorConfigStore.getDefaultChannelIdList()).thenReturn(List.of("ch-same", "ch-extra"));

            service.syncOnStartup();

            var captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(monitorConfigStore).updateConfig(captor.capture(), anyList(), anyList(), anyList(), eq("system"), eq("startup_sync"));
            List<String> channelIds = captor.getValue();
            assertThat(channelIds).containsExactlyInAnyOrder("ch-same", "ch-extra");
        }
    }

    // ==================== RoutingMode 解析 ====================

    @Nested
    @DisplayName("RoutingMode 解析與建立")
    class RoutingModeTests {

        @Test
        @DisplayName("建立來源時指定 GLOBAL — 正確設定 routingMode")
        void createSource_withGlobalRoutingMode() {
            CreateSignalSourceRequest req = CreateSignalSourceRequest.builder()
                    .name("全域來源").displayName("全域")
                    .channelId("ch-g").guildId("g-g")
                    .routingMode("GLOBAL").build();

            when(sourceRepository.existsByChannelIdAndGuildId("ch-g", "g-g")).thenReturn(false);
            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> {
                SignalSourceConfig s = inv.getArgument(0);
                s.setId(20L);
                return s;
            });
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of());

            SignalSourceConfig result = service.createSource(req);

            assertThat(result.getRoutingMode()).isEqualTo(SignalSourceConfig.RoutingMode.GLOBAL);
        }

        @Test
        @DisplayName("建立來源時不指定 routingMode — 預設 ASSIGNED")
        void createSource_defaultAssigned() {
            CreateSignalSourceRequest req = CreateSignalSourceRequest.builder()
                    .name("預設來源").displayName("預設").build();

            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> {
                SignalSourceConfig s = inv.getArgument(0);
                s.setId(21L);
                return s;
            });
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of());

            SignalSourceConfig result = service.createSource(req);

            assertThat(result.getRoutingMode()).isEqualTo(SignalSourceConfig.RoutingMode.ASSIGNED);
        }

        @Test
        @DisplayName("更新 routingMode — ASSIGNED → GLOBAL")
        void updateSource_changeRoutingMode() {
            SignalSourceConfig existing = SignalSourceConfig.builder()
                    .id(1L).name("來源").displayName("來源").enabled(true)
                    .routingMode(SignalSourceConfig.RoutingMode.ASSIGNED).build();
            UpdateSignalSourceRequest req = UpdateSignalSourceRequest.builder()
                    .routingMode("GLOBAL").build();

            when(sourceRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> inv.getArgument(0));
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of(existing));

            SignalSourceConfig result = service.updateSource(1L, req);

            assertThat(result.getRoutingMode()).isEqualTo(SignalSourceConfig.RoutingMode.GLOBAL);
        }

        @Test
        @DisplayName("routingMode 無效值 — fallback 為 ASSIGNED")
        void createSource_invalidRoutingModeFallback() {
            CreateSignalSourceRequest req = CreateSignalSourceRequest.builder()
                    .name("無效模式").displayName("X")
                    .routingMode("INVALID_MODE").build();

            when(sourceRepository.save(any(SignalSourceConfig.class))).thenAnswer(inv -> {
                SignalSourceConfig s = inv.getArgument(0);
                s.setId(22L);
                return s;
            });
            when(sourceRepository.findByEnabledTrue()).thenReturn(List.of());

            SignalSourceConfig result = service.createSource(req);

            assertThat(result.getRoutingMode()).isEqualTo(SignalSourceConfig.RoutingMode.ASSIGNED);
        }
    }
}
