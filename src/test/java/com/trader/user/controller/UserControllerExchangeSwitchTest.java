package com.trader.user.controller;

import com.google.gson.Gson;
import com.trader.shared.util.SecurityUtil;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.dto.SaveApiKeyRequest;
import com.trader.user.entity.UserApiKey;
import com.trader.user.service.UserService;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserController — 交易所切換驗證測試
 *
 * 覆蓋場景：
 * 1. 新用戶儲存 Key → 正常通過
 * 2. 同交易所更新 Key → 正常通過
 * 3. 切換交易所 + 無未收斂交易 → 允許切換
 * 4. 切換交易所 + 有 OPEN 交易 → 拒絕（400）
 * 5. 切換交易所 + 有 PENDING_CLOSE 交易 → 拒絕（400）
 * 6. 不支援的交易所 → 拒絕（400 UNSUPPORTED_EXCHANGE）
 * 7. exchange 大小寫/空白正規化 → 正常通過
 * 8. BITGET + 有 passphrase → 200 OK
 * 9. BITGET + 無 passphrase → 400 PASSPHRASE_REQUIRED
 * 10. BITGET 在白名單中 → 正常通過
 */
class UserControllerExchangeSwitchTest {

    private UserService userService;
    private TradeRepository tradeRepository;
    private UserController controller;
    private MockedStatic<SecurityUtil> securityMock;

    private static final String USER_ID = "test-user";

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        tradeRepository = mock(TradeRepository.class);
        controller = new UserController(userService, tradeRepository);

        securityMock = mockStatic(SecurityUtil.class);
        securityMock.when(SecurityUtil::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityMock.close();
    }

    private SaveApiKeyRequest buildRequest(String exchange) {
        SaveApiKeyRequest req = new SaveApiKeyRequest();
        req.setExchange(exchange);
        req.setApiKey("test-api-key");
        req.setSecretKey("test-secret-key");
        return req;
    }

    private SaveApiKeyRequest buildBitgetRequest() {
        SaveApiKeyRequest req = new SaveApiKeyRequest();
        req.setExchange("BITGET");
        req.setApiKey("test-api-key");
        req.setSecretKey("test-secret-key");
        req.setPassphrase("test-passphrase");
        return req;
    }

    private UserApiKey buildSavedKey(String exchange) {
        return UserApiKey.builder()
                .userId(USER_ID)
                .exchange(exchange)
                .encryptedApiKey("enc-api")
                .encryptedSecretKey("enc-secret")
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("新用戶儲存 Key → 200 OK")
    void newUser_savesSuccessfully() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of());
        when(userService.saveApiKey(eq(USER_ID), eq("BINANCE"), anyString(), anyString(), any()))
                .thenReturn(buildSavedKey("BINANCE"));

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BINANCE"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(tradeRepository, never()).countByUserIdAndStatusIn(anyString(), anyList());
    }

    @Test
    @DisplayName("同交易所更新 Key → 200 OK（不檢查未收斂交易）")
    void sameExchange_updatesWithoutCheck() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of(buildSavedKey("BINANCE")));
        when(userService.saveApiKey(eq(USER_ID), eq("BINANCE"), anyString(), anyString(), any()))
                .thenReturn(buildSavedKey("BINANCE"));

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BINANCE"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(tradeRepository, never()).countByUserIdAndStatusIn(anyString(), anyList());
    }

    @Test
    @DisplayName("切換交易所 + 無未收斂交易 → 200 OK")
    void switchExchange_noUnsettledTrades_allowed() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of(buildSavedKey("BINANCE")));
        when(tradeRepository.countByUserIdAndStatusIn(eq(USER_ID), anyList())).thenReturn(0L);
        when(userService.saveApiKey(eq(USER_ID), eq("BYBIT"), anyString(), anyString(), any()))
                .thenReturn(buildSavedKey("BYBIT"));

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BYBIT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(tradeRepository).countByUserIdAndStatusIn(eq(USER_ID),
                eq(List.of("OPEN", "PENDING_CLOSE")));
    }

    @Test
    @DisplayName("切換交易所 + 有 OPEN 交易 → 400 EXCHANGE_SWITCH_BLOCKED")
    void switchExchange_hasOpenTrades_blocked() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of(buildSavedKey("BINANCE")));
        when(tradeRepository.countByUserIdAndStatusIn(eq(USER_ID), anyList())).thenReturn(3L);

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BYBIT"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        String body = new Gson().toJson(response.getBody());
        assertThat(body).contains("EXCHANGE_SWITCH_BLOCKED");
        assertThat(body).contains("3");

        // 不應呼叫 saveApiKey
        verify(userService, never()).saveApiKey(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("切換交易所 + 有 PENDING_CLOSE 交易 → 400 EXCHANGE_SWITCH_BLOCKED")
    void switchExchange_hasPendingCloseTrades_blocked() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of(buildSavedKey("BINANCE")));
        // 模擬 1 筆 PENDING_CLOSE（OPEN=0 但 PENDING_CLOSE=1）
        when(tradeRepository.countByUserIdAndStatusIn(eq(USER_ID), anyList())).thenReturn(1L);

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BYBIT"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        String body = new Gson().toJson(response.getBody());
        assertThat(body).contains("EXCHANGE_SWITCH_BLOCKED");

        verify(userService, never()).saveApiKey(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("不支援的交易所 → 400 UNSUPPORTED_EXCHANGE")
    void unsupportedExchange_rejected() {
        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BITGET_TEST"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        String body = new Gson().toJson(response.getBody());
        assertThat(body).contains("UNSUPPORTED_EXCHANGE");

        // 不應查詢任何資料
        verify(userService, never()).getApiKeys(anyString());
        verify(userService, never()).saveApiKey(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("exchange 大小寫/空白正規化 → 自動 trim + toUpperCase")
    void exchange_normalizedBeforeSave() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of());
        when(userService.saveApiKey(eq(USER_ID), eq("BYBIT"), anyString(), anyString(), any()))
                .thenReturn(buildSavedKey("BYBIT"));

        // 故意傳入帶空白的小寫
        ResponseEntity<?> response = controller.saveApiKeys(buildRequest(" bybit "));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // 驗證存入的是正規化後的 "BYBIT"
        verify(userService).saveApiKey(eq(USER_ID), eq("BYBIT"), anyString(), anyString(), any());
    }

    // ==================== Bitget Passphrase 測試 ====================

    @Test
    @DisplayName("BITGET + 有 passphrase → 200 OK")
    void bitget_withPassphrase_savesSuccessfully() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of());
        when(userService.saveApiKey(eq(USER_ID), eq("BITGET"), anyString(), anyString(), eq("test-passphrase")))
                .thenReturn(buildSavedKey("BITGET"));

        ResponseEntity<?> response = controller.saveApiKeys(buildBitgetRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(userService).saveApiKey(eq(USER_ID), eq("BITGET"), anyString(), anyString(), eq("test-passphrase"));
    }

    @Test
    @DisplayName("BITGET + 無 passphrase → 400 PASSPHRASE_REQUIRED")
    void bitget_withoutPassphrase_rejected() {
        // 不帶 passphrase 的 BITGET 請求
        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BITGET"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        String body = new Gson().toJson(response.getBody());
        assertThat(body).contains("PASSPHRASE_REQUIRED");

        // 不應呼叫 saveApiKey
        verify(userService, never()).saveApiKey(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("BITGET + 空白 passphrase → 400 PASSPHRASE_REQUIRED")
    void bitget_withBlankPassphrase_rejected() {
        SaveApiKeyRequest req = buildBitgetRequest();
        req.setPassphrase("   ");

        ResponseEntity<?> response = controller.saveApiKeys(req);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        String body = new Gson().toJson(response.getBody());
        assertThat(body).contains("PASSPHRASE_REQUIRED");
    }

    @Test
    @DisplayName("非 BITGET + 無 passphrase → 200 OK（passphrase 僅 BITGET 必填）")
    void nonBitget_withoutPassphrase_allowed() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of());
        when(userService.saveApiKey(eq(USER_ID), eq("BINANCE"), anyString(), anyString(), any()))
                .thenReturn(buildSavedKey("BINANCE"));

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BINANCE"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
