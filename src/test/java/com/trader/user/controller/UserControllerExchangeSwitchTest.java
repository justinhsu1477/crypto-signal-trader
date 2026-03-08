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
 * 3. 切換交易所 + 無未平倉 → 允許切換
 * 4. 切換交易所 + 有未平倉 → 拒絕（400）
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
        when(userService.saveApiKey(eq(USER_ID), eq("BINANCE"), anyString(), anyString()))
                .thenReturn(buildSavedKey("BINANCE"));

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BINANCE"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(tradeRepository, never()).countByUserIdAndStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("同交易所更新 Key → 200 OK（不檢查未平倉）")
    void sameExchange_updatesWithoutCheck() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of(buildSavedKey("BINANCE")));
        when(userService.saveApiKey(eq(USER_ID), eq("BINANCE"), anyString(), anyString()))
                .thenReturn(buildSavedKey("BINANCE"));

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BINANCE"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(tradeRepository, never()).countByUserIdAndStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("切換交易所 + 無未平倉 → 200 OK")
    void switchExchange_noOpenTrades_allowed() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of(buildSavedKey("BINANCE")));
        when(tradeRepository.countByUserIdAndStatus(USER_ID, "OPEN")).thenReturn(0L);
        when(userService.saveApiKey(eq(USER_ID), eq("BYBIT"), anyString(), anyString()))
                .thenReturn(buildSavedKey("BYBIT"));

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BYBIT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(tradeRepository).countByUserIdAndStatus(USER_ID, "OPEN");
    }

    @Test
    @DisplayName("切換交易所 + 有未平倉 → 400 EXCHANGE_SWITCH_BLOCKED")
    void switchExchange_hasOpenTrades_blocked() {
        when(userService.getApiKeys(USER_ID)).thenReturn(List.of(buildSavedKey("BINANCE")));
        when(tradeRepository.countByUserIdAndStatus(USER_ID, "OPEN")).thenReturn(3L);

        ResponseEntity<?> response = controller.saveApiKeys(buildRequest("BYBIT"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        String body = new Gson().toJson(response.getBody());
        assertThat(body).contains("EXCHANGE_SWITCH_BLOCKED");
        assertThat(body).contains("3");

        // 不應呼叫 saveApiKey
        verify(userService, never()).saveApiKey(anyString(), anyString(), anyString(), anyString());
    }
}
