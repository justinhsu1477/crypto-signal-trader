package com.trader.dashboard.controller;

import com.trader.trading.dto.signalsource.*;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.service.SignalSourceService;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminSignalSourceController 單元測試
 *
 * 覆蓋：
 * - 來源 CRUD（getAllSources / createSource / getSource / updateSource / deleteSource）
 * - 用戶綁定（getSourceUsers / assignUsers / unassignUser / toggleUserAssignment）
 * - 績效查詢（getAllPerformance / getSourcePerformance）
 * - 各種異常路徑（404 / 400）
 */
class AdminSignalSourceControllerTest {

    private SignalSourceService signalSourceService;
    private AdminSignalSourceController controller;

    private static final Long SOURCE_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 12, 10, 0, 0);

    @BeforeEach
    void setUp() {
        signalSourceService = mock(SignalSourceService.class);
        controller = new AdminSignalSourceController(signalSourceService);
    }

    // ==================== Helper ====================

    private SignalSourceResponse buildSourceResponse(Long id, String name, String displayName) {
        return SignalSourceResponse.builder()
                .id(id)
                .name(name)
                .displayName(displayName)
                .channelId("ch-" + id)
                .guildId("guild-" + id)
                .description("描述 " + id)
                .enabled(true)
                .assignedUserCount(2)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private SignalSourceConfig buildSourceConfig(Long id, String name) {
        return SignalSourceConfig.builder()
                .id(id)
                .name(name)
                .displayName("顯示-" + name)
                .channelId("ch-" + id)
                .guildId("guild-" + id)
                .description("描述")
                .enabled(true)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private UserAssignmentResponse buildAssignment(Long id, String userId, String email, String name) {
        return UserAssignmentResponse.builder()
                .id(id)
                .userId(userId)
                .email(email)
                .name(name)
                .enabled(true)
                .assignedAt(NOW)
                .build();
    }

    private SignalSourcePerformanceDto buildPerformance(Long sourceId, String name, String displayName) {
        return SignalSourcePerformanceDto.builder()
                .sourceId(sourceId)
                .name(name)
                .displayName(displayName)
                .tradeCount(100)
                .winCount(65)
                .winRate(65.0)
                .totalPnl(1500.50)
                .avgPnl(15.005)
                .build();
    }

    // ==================== 來源 CRUD ====================

    @Nested
    @DisplayName("getAllSources - 查詢全部來源")
    class GetAllSources {

        @Test
        @DisplayName("回傳所有來源列表")
        void returnsAllSources() {
            List<SignalSourceResponse> sources = List.of(
                    buildSourceResponse(1L, "來源A", "訊號源 A"),
                    buildSourceResponse(2L, "來源B", "訊號源 B")
            );
            when(signalSourceService.getAllSources()).thenReturn(sources);

            ResponseEntity<List<SignalSourceResponse>> response = controller.getAllSources();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).getName()).isEqualTo("來源A");
            assertThat(response.getBody().get(1).getName()).isEqualTo("來源B");
            verify(signalSourceService).getAllSources();
        }

        @Test
        @DisplayName("無來源時回傳空列表")
        void returnsEmptyList() {
            when(signalSourceService.getAllSources()).thenReturn(List.of());

            ResponseEntity<List<SignalSourceResponse>> response = controller.getAllSources();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("createSource - 建立來源")
    class CreateSource {

        @Test
        @DisplayName("建立成功 → 200 + 完整 response")
        void createSuccess() {
            CreateSignalSourceRequest request = CreateSignalSourceRequest.builder()
                    .name("陳哥VIP群")
                    .displayName("訊號源 A")
                    .channelId("ch-100")
                    .guildId("guild-100")
                    .description("VIP 訊號")
                    .build();

            SignalSourceConfig created = buildSourceConfig(10L, "陳哥VIP群");
            SignalSourceResponse expectedResponse = buildSourceResponse(10L, "陳哥VIP群", "訊號源 A");

            when(signalSourceService.createSource(request)).thenReturn(created);
            when(signalSourceService.getAllSources()).thenReturn(List.of(expectedResponse));

            ResponseEntity<SignalSourceResponse> response = controller.createSource(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isEqualTo(10L);
            assertThat(response.getBody().getName()).isEqualTo("陳哥VIP群");
            verify(signalSourceService).createSource(request);
            verify(signalSourceService).getAllSources();
        }

        @Test
        @DisplayName("建立後從 getAllSources 找到對應 id 的 response")
        void createReturnsMatchingIdFromAllSources() {
            CreateSignalSourceRequest request = CreateSignalSourceRequest.builder()
                    .name("新來源")
                    .displayName("顯示名")
                    .build();

            SignalSourceConfig created = buildSourceConfig(5L, "新來源");
            SignalSourceResponse otherSource = buildSourceResponse(3L, "其他", "其他顯示");
            SignalSourceResponse targetSource = buildSourceResponse(5L, "新來源", "顯示名");

            when(signalSourceService.createSource(request)).thenReturn(created);
            when(signalSourceService.getAllSources()).thenReturn(List.of(otherSource, targetSource));

            ResponseEntity<SignalSourceResponse> response = controller.createSource(request);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isEqualTo(5L);
        }
    }

    @Nested
    @DisplayName("getSource - 查詢單一來源")
    class GetSource {

        @Test
        @DisplayName("來源存在 → 200 + response")
        void sourceExists() {
            SignalSourceConfig config = buildSourceConfig(SOURCE_ID, "來源A");
            SignalSourceResponse expectedResponse = buildSourceResponse(SOURCE_ID, "來源A", "訊號源 A");

            when(signalSourceService.getSourceById(SOURCE_ID)).thenReturn(Optional.of(config));
            when(signalSourceService.getAllSources()).thenReturn(List.of(expectedResponse));

            ResponseEntity<SignalSourceResponse> response = controller.getSource(SOURCE_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getId()).isEqualTo(SOURCE_ID);
            assertThat(response.getBody().getName()).isEqualTo("來源A");
        }

        @Test
        @DisplayName("來源不存在（getSourceById 回傳 empty） → 404")
        void sourceNotFound() {
            when(signalSourceService.getSourceById(999L)).thenReturn(Optional.empty());

            ResponseEntity<SignalSourceResponse> response = controller.getSource(999L);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).isNull();
            verify(signalSourceService, never()).getAllSources();
        }

        @Test
        @DisplayName("getSourceById 存在但 getAllSources 找不到該 id → 404")
        void sourceExistsButNotInAllSources() {
            SignalSourceConfig config = buildSourceConfig(SOURCE_ID, "來源A");
            SignalSourceResponse otherSource = buildSourceResponse(99L, "其他", "其他");

            when(signalSourceService.getSourceById(SOURCE_ID)).thenReturn(Optional.of(config));
            when(signalSourceService.getAllSources()).thenReturn(List.of(otherSource));

            ResponseEntity<SignalSourceResponse> response = controller.getSource(SOURCE_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("updateSource - 更新來源")
    class UpdateSource {

        @Test
        @DisplayName("更新成功 → 200 + 更新後 response")
        void updateSuccess() {
            UpdateSignalSourceRequest request = UpdateSignalSourceRequest.builder()
                    .name("更新名稱")
                    .displayName("新顯示名")
                    .enabled(false)
                    .build();

            SignalSourceResponse updatedResponse = buildSourceResponse(SOURCE_ID, "更新名稱", "新顯示名");

            when(signalSourceService.updateSource(SOURCE_ID, request)).thenReturn(buildSourceConfig(SOURCE_ID, "更新名稱"));
            when(signalSourceService.getAllSources()).thenReturn(List.of(updatedResponse));

            ResponseEntity<SignalSourceResponse> response = controller.updateSource(SOURCE_ID, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getName()).isEqualTo("更新名稱");
            verify(signalSourceService).updateSource(SOURCE_ID, request);
        }

        @Test
        @DisplayName("來源不存在 → 404")
        void updateNotFound() {
            UpdateSignalSourceRequest request = UpdateSignalSourceRequest.builder()
                    .name("更新名稱")
                    .build();

            when(signalSourceService.updateSource(eq(999L), any()))
                    .thenThrow(new IllegalArgumentException("訊號來源不存在: id=999"));

            ResponseEntity<SignalSourceResponse> response = controller.updateSource(999L, request);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).isNull();
        }
    }

    @Nested
    @DisplayName("deleteSource - 刪除來源")
    class DeleteSource {

        @Test
        @DisplayName("刪除成功 → 200 + 訊息")
        void deleteSuccess() {
            doNothing().when(signalSourceService).deleteSource(SOURCE_ID);

            ResponseEntity<Map<String, String>> response = controller.deleteSource(SOURCE_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().get("message")).isEqualTo("訊號來源已刪除");
            verify(signalSourceService).deleteSource(SOURCE_ID);
        }

        @Test
        @DisplayName("來源不存在 → 404")
        void deleteNotFound() {
            doThrow(new IllegalArgumentException("訊號來源不存在: id=999"))
                    .when(signalSourceService).deleteSource(999L);

            ResponseEntity<Map<String, String>> response = controller.deleteSource(999L);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).isNull();
        }
    }

    // ==================== 用戶綁定 ====================

    @Nested
    @DisplayName("getSourceUsers - 查詢來源綁定用戶")
    class GetSourceUsers {

        @Test
        @DisplayName("回傳綁定用戶列表")
        void returnsUsers() {
            List<UserAssignmentResponse> assignments = List.of(
                    buildAssignment(1L, "user-001", "a@test.com", "用戶A"),
                    buildAssignment(2L, "user-002", "b@test.com", "用戶B")
            );
            when(signalSourceService.getUsersForSource(SOURCE_ID)).thenReturn(assignments);

            ResponseEntity<List<UserAssignmentResponse>> response = controller.getSourceUsers(SOURCE_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).getUserId()).isEqualTo("user-001");
            assertThat(response.getBody().get(1).getUserId()).isEqualTo("user-002");
            verify(signalSourceService).getUsersForSource(SOURCE_ID);
        }

        @Test
        @DisplayName("無綁定用戶 → 回傳空列表")
        void returnsEmptyList() {
            when(signalSourceService.getUsersForSource(SOURCE_ID)).thenReturn(List.of());

            ResponseEntity<List<UserAssignmentResponse>> response = controller.getSourceUsers(SOURCE_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("assignUsers - 綁定用戶")
    class AssignUsers {

        @Test
        @DisplayName("綁定成功 → 200 + 結果列表")
        void assignSuccess() {
            AssignUserRequest request = AssignUserRequest.builder()
                    .userIds(List.of("user-001", "user-002"))
                    .build();

            List<UserAssignmentResponse> results = List.of(
                    buildAssignment(1L, "user-001", "a@test.com", "用戶A"),
                    buildAssignment(2L, "user-002", "b@test.com", "用戶B")
            );

            when(signalSourceService.assignUsers(SOURCE_ID, request.getUserIds())).thenReturn(results);

            ResponseEntity<?> response = controller.assignUsers(SOURCE_ID, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            List<UserAssignmentResponse> body = (List<UserAssignmentResponse>) response.getBody();
            assertThat(body).hasSize(2);
            verify(signalSourceService).assignUsers(SOURCE_ID, request.getUserIds());
        }

        @Test
        @DisplayName("用戶已綁定其他來源 → IllegalStateException → 400")
        void assignAlreadyBoundToOtherSource() {
            AssignUserRequest request = AssignUserRequest.builder()
                    .userIds(List.of("user-001"))
                    .build();

            when(signalSourceService.assignUsers(SOURCE_ID, request.getUserIds()))
                    .thenThrow(new IllegalStateException("用戶 用戶A (user-001) 已綁定其他訊號來源，請先解除綁定"));

            ResponseEntity<?> response = controller.assignUsers(SOURCE_ID, request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("error")).contains("已綁定其他訊號來源");
        }

        @Test
        @DisplayName("來源不存在 → IllegalArgumentException → 404")
        void assignSourceNotFound() {
            AssignUserRequest request = AssignUserRequest.builder()
                    .userIds(List.of("user-001"))
                    .build();

            when(signalSourceService.assignUsers(eq(999L), anyList()))
                    .thenThrow(new IllegalArgumentException("訊號來源不存在: id=999"));

            ResponseEntity<?> response = controller.assignUsers(999L, request);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).isNull();
        }
    }

    @Nested
    @DisplayName("unassignUser - 解除綁定")
    class UnassignUser {

        @Test
        @DisplayName("解除綁定成功 → 200 + 訊息")
        void unassignSuccess() {
            doNothing().when(signalSourceService).unassignUser(SOURCE_ID, "user-001");

            ResponseEntity<Map<String, String>> response = controller.unassignUser(SOURCE_ID, "user-001");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().get("message")).isEqualTo("已解除綁定");
            verify(signalSourceService).unassignUser(SOURCE_ID, "user-001");
        }
    }

    @Nested
    @DisplayName("toggleUserAssignment - 切換綁定啟用狀態")
    class ToggleUserAssignment {

        @Test
        @DisplayName("啟用 → 200 + 已啟用")
        void toggleEnable() {
            doNothing().when(signalSourceService).toggleUserAssignment(SOURCE_ID, "user-001", true);

            ResponseEntity<Map<String, String>> response = controller.toggleUserAssignment(
                    SOURCE_ID, "user-001", Map.of("enabled", true));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().get("message")).isEqualTo("已啟用");
            verify(signalSourceService).toggleUserAssignment(SOURCE_ID, "user-001", true);
        }

        @Test
        @DisplayName("停用 → 200 + 已停用")
        void toggleDisable() {
            doNothing().when(signalSourceService).toggleUserAssignment(SOURCE_ID, "user-001", false);

            ResponseEntity<Map<String, String>> response = controller.toggleUserAssignment(
                    SOURCE_ID, "user-001", Map.of("enabled", false));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().get("message")).isEqualTo("已停用");
            verify(signalSourceService).toggleUserAssignment(SOURCE_ID, "user-001", false);
        }

        @Test
        @DisplayName("body 無 enabled 欄位 → 預設 true（已啟用）")
        void toggleDefaultEnabled() {
            doNothing().when(signalSourceService).toggleUserAssignment(SOURCE_ID, "user-001", true);

            ResponseEntity<Map<String, String>> response = controller.toggleUserAssignment(
                    SOURCE_ID, "user-001", Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("message")).isEqualTo("已啟用");
            verify(signalSourceService).toggleUserAssignment(SOURCE_ID, "user-001", true);
        }

        @Test
        @DisplayName("綁定不存在 → IllegalArgumentException → 404")
        void toggleNotFound() {
            doThrow(new IllegalArgumentException("綁定不存在"))
                    .when(signalSourceService).toggleUserAssignment(SOURCE_ID, "user-999", true);

            ResponseEntity<Map<String, String>> response = controller.toggleUserAssignment(
                    SOURCE_ID, "user-999", Map.of("enabled", true));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).isNull();
        }
    }

    // ==================== 績效查詢 ====================

    @Nested
    @DisplayName("getAllPerformance - 查詢全部績效")
    class GetAllPerformance {

        @Test
        @DisplayName("回傳所有來源績效列表")
        void returnsAllPerformances() {
            List<SignalSourcePerformanceDto> performances = List.of(
                    buildPerformance(1L, "來源A", "訊號源 A"),
                    buildPerformance(2L, "來源B", "訊號源 B")
            );
            when(signalSourceService.getAllSourcePerformances()).thenReturn(performances);

            ResponseEntity<List<SignalSourcePerformanceDto>> response = controller.getAllPerformance();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).getTradeCount()).isEqualTo(100);
            assertThat(response.getBody().get(0).getWinRate()).isEqualTo(65.0);
            assertThat(response.getBody().get(1).getTotalPnl()).isEqualTo(1500.50);
            verify(signalSourceService).getAllSourcePerformances();
        }

        @Test
        @DisplayName("無績效資料 → 回傳空列表")
        void returnsEmptyList() {
            when(signalSourceService.getAllSourcePerformances()).thenReturn(List.of());

            ResponseEntity<List<SignalSourcePerformanceDto>> response = controller.getAllPerformance();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getSourcePerformance - 查詢單一來源績效")
    class GetSourcePerformance {

        @Test
        @DisplayName("來源存在 → 200 + 績效資料")
        void performanceExists() {
            SignalSourcePerformanceDto performance = buildPerformance(SOURCE_ID, "來源A", "訊號源 A");
            when(signalSourceService.getSourcePerformance(SOURCE_ID)).thenReturn(performance);

            ResponseEntity<SignalSourcePerformanceDto> response = controller.getSourcePerformance(SOURCE_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getSourceId()).isEqualTo(SOURCE_ID);
            assertThat(response.getBody().getName()).isEqualTo("來源A");
            assertThat(response.getBody().getTradeCount()).isEqualTo(100);
            assertThat(response.getBody().getWinCount()).isEqualTo(65);
            assertThat(response.getBody().getWinRate()).isEqualTo(65.0);
            assertThat(response.getBody().getTotalPnl()).isEqualTo(1500.50);
            assertThat(response.getBody().getAvgPnl()).isEqualTo(15.005);
            verify(signalSourceService).getSourcePerformance(SOURCE_ID);
        }

        @Test
        @DisplayName("來源不存在 → 404")
        void performanceNotFound() {
            when(signalSourceService.getSourcePerformance(999L))
                    .thenThrow(new IllegalArgumentException("訊號來源不存在: id=999"));

            ResponseEntity<SignalSourcePerformanceDto> response = controller.getSourcePerformance(999L);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).isNull();
        }
    }
}
