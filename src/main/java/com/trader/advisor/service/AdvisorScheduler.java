package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.exchange.ExchangeCredentials;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 顧問排程器 + 手動測試 endpoint
 *
 * - 每小時自動觸發 AI 分析（可透過 advisor.enabled 開關）
 * - 提供 /api/advisor/test 方便手動觸發測試
 * - 多用戶模式：遍歷每個有 API Key 的用戶，各自產生 AI 分析報告
 */
@Slf4j
@Component
@RestController
@RequestMapping("/api/advisor")
@RequiredArgsConstructor
public class AdvisorScheduler {

    private final AdvisorService advisorService;
    private final AdvisorConfig advisorConfig;
    private final MultiUserConfig multiUserConfig;
    private final UserRepository userRepository;
    private final UserApiKeyService userApiKeyService;
    private final ExchangeAdapterFactory exchangeAdapterFactory;

    /**
     * 定時觸發 AI 顧問分析
     * 預設每小時整點執行，可透過 advisor.cron-expression 調整
     *
     * 多用戶模式：遍歷所有 enabled + 有 API Key 的用戶
     * 單人模式：全局執行（現有行為不變）
     */
    @Scheduled(cron = "${advisor.cron-expression:0 0 * * * *}", zone = "${app.timezone}")
    public void scheduledAdvisory() {
        if (!advisorConfig.isEnabled()) {
            log.debug("AI Advisor 已停用，跳過");
            return;
        }

        try {
            if (multiUserConfig.isEnabled()) {
                runForAllUsers();
            } else {
                runGlobal();
            }
        } catch (Exception e) {
            log.error("AI Advisor 排程執行失敗: {}", e.getMessage(), e);
        }
    }

    /**
     * 單人模式 — 全局執行（現有行為不變）
     */
    private void runGlobal() {
        log.info("AI Advisor 排程觸發，開始分析...");
        advisorService.runAdvisory();
    }

    /**
     * 多用戶模式 — 遍歷每個有 API Key 的用戶
     *
     * 利用 ThreadLocal 機制：
     * - setAdvisoryContext → 設定 per-user ExchangeAdapter（支援多交易所）
     * - setCurrentUserId → TradeRecordService 查該用戶的交易紀錄
     * - runAdvisory() 內部方法（findAllOpenTrades 等）會自動讀 ThreadLocal
     */
    private void runForAllUsers() {
        List<User> users = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(u -> userApiKeyService.getUserPrimaryExchangeKeys(u.getUserId()).isPresent())
                .toList();

        log.info("AI Advisor 多用戶排程觸發: {} 個用戶", users.size());
        int success = 0;

        for (User user : users) {
            try {
                var primaryOpt = userApiKeyService.getUserPrimaryExchangeKeys(user.getUserId());
                if (primaryOpt.isEmpty()) continue;

                String exchange = primaryOpt.get().getKey();
                ExchangeKeys keys = primaryOpt.get().getValue();
                ExchangeAdapter adapter = exchangeAdapterFactory.getAdapter(exchange);
                adapter.setCredentials(new ExchangeCredentials(keys.apiKey(), keys.secretKey(), keys.passphrase()));

                advisorService.setAdvisoryContext(adapter);
                TradeRecordService.setCurrentUserId(user.getUserId());
                advisorService.runAdvisory();
                success++;
            } catch (Exception e) {
                log.error("AI Advisor 用戶 {} 執行失敗: {}", user.getUserId(), e.getMessage());
            } finally {
                advisorService.clearAdvisoryContext();
                TradeRecordService.clearCurrentUserId();
            }
        }

        log.info("AI Advisor 多用戶排程完成: {}/{} 成功", success, users.size());
    }

    /**
     * 手動觸發 AI 顧問分析（測試用）
     * GET /api/advisor/test
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, String>> triggerManually() {
        if (!advisorConfig.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "status", "disabled",
                    "message", "AI Advisor 未啟用，請設定 ADVISOR_ENABLED=true"));
        }

        try {
            advisorService.runAdvisory();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "AI 分析已完成，請查看 Discord"));
        } catch (Exception e) {
            log.error("手動觸發 AI Advisor 失敗: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()));
        }
    }
}
