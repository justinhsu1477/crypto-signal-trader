package com.trader.subscription.service;

import com.trader.subscription.config.CryptoPaymentConfig;
import com.trader.subscription.dto.CryptoCheckoutResponse;
import com.trader.subscription.dto.PlanResponse;
import com.trader.subscription.dto.SubscriptionStatusResponse;
import com.trader.subscription.entity.PaymentHistory;
import com.trader.subscription.entity.Plan;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.PaymentHistoryRepository;
import com.trader.subscription.repository.PlanRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SubscriptionServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private PlanRepository planRepository;
    private PaymentHistoryRepository paymentHistoryRepository;
    private CryptoPaymentConfig cryptoConfig;
    private TronService tronService;
    private SubscriptionService service;

    private static final String USER_ID = "user-123";
    private static final String TX_HASH = "abc123def456";
    private static final String WALLET_ADDRESS = "TTestWallet";
    private static final String NETWORK = "TRC20";
    /** 與 SubscriptionService 一致的時區 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        planRepository = mock(PlanRepository.class);
        paymentHistoryRepository = mock(PaymentHistoryRepository.class);
        cryptoConfig = mock(CryptoPaymentConfig.class);
        tronService = mock(TronService.class);

        when(cryptoConfig.getSubscriptionDays()).thenReturn(30);
        when(cryptoConfig.getWalletAddress()).thenReturn(WALLET_ADDRESS);
        when(cryptoConfig.getNetwork()).thenReturn(NETWORK);

        service = new SubscriptionService(
                subscriptionRepository,
                planRepository,
                paymentHistoryRepository,
                cryptoConfig,
                tronService
        );
    }

    // ==================== 工具方法 ====================

    private Plan buildPlan(String planId, String name, Double priceUsdt) {
        BigDecimal price = priceUsdt != null ? BigDecimal.valueOf(priceUsdt) : null;
        return Plan.builder()
                .planId(planId)
                .name(name)
                .priceUsdt(price)
                .priceMonthly(price)
                .maxPositions(5)
                .maxSymbols(10)
                .dcaLayersAllowed(3)
                .maxRiskPercent(0.1)
                .active(true)
                .build();
    }

    private Subscription buildActiveSub(String userId, String planId, LocalDateTime end) {
        return Subscription.builder()
                .id(1L)
                .userId(userId)
                .planId(planId)
                .status(Subscription.Status.ACTIVE)
                .currentPeriodStart(LocalDateTime.now(ZONE).minusDays(30))
                .currentPeriodEnd(end)
                .build();
    }

    private TronService.VerificationResult successResult(double amount) {
        return TronService.VerificationResult.ok(
                BigDecimal.valueOf(amount), "TFromAddress", "TToAddress"
        );
    }

    // ==================== getPlans ====================

    @Nested
    @DisplayName("getPlans -- 查詢可用方案")
    class GetPlans {

        @Test
        @DisplayName("回傳所有啟用中方案，正確對應 PlanResponse 欄位")
        void returnsAllActivePlansMappedToPlanResponse() {
            Plan basic = buildPlan("basic", "Basic", 19.0);
            Plan pro = buildPlan("pro", "Pro", 49.0);
            when(planRepository.findByActiveTrue()).thenReturn(List.of(basic, pro));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            List<PlanResponse> result = service.getPlans(USER_ID);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getPlanId()).isEqualTo("basic");
            assertThat(result.get(0).getName()).isEqualTo("Basic");
            assertThat(result.get(0).getPriceUsdt()).isEqualByComparingTo(BigDecimal.valueOf(19.0));
            assertThat(result.get(0).getMaxPositions()).isEqualTo(5);
            assertThat(result.get(0).getMaxSymbols()).isEqualTo(10);
            assertThat(result.get(0).getDcaLayersAllowed()).isEqualTo(3);
            assertThat(result.get(0).getMaxRiskPercent()).isEqualTo(0.1);
            assertThat(result.get(1).getPlanId()).isEqualTo("pro");
        }

        @Test
        @DisplayName("已訂閱方案標記 current=true，其他為 false")
        void marksCurrentPlanAsTrue() {
            Plan basic = buildPlan("basic", "Basic", 19.0);
            Plan pro = buildPlan("pro", "Pro", 49.0);
            when(planRepository.findByActiveTrue()).thenReturn(List.of(basic, pro));

            Subscription sub = buildActiveSub(USER_ID, "pro", LocalDateTime.now(ZONE).plusDays(15));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));

            List<PlanResponse> result = service.getPlans(USER_ID);

            assertThat(result.get(0).isCurrent()).isFalse();
            assertThat(result.get(1).isCurrent()).isTrue();
        }

        @Test
        @DisplayName("無訂閱時 currentPlanId 為 'free'，所有方案 current=false")
        void noSubscriptionDefaultsToFree() {
            Plan basic = buildPlan("basic", "Basic", 19.0);
            when(planRepository.findByActiveTrue()).thenReturn(List.of(basic));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            List<PlanResponse> result = service.getPlans(USER_ID);

            assertThat(result).allMatch(p -> !p.isCurrent());
        }
    }

    // ==================== getStatus ====================

    @Nested
    @DisplayName("getStatus -- 查詢訂閱狀態")
    class GetStatus {

        @Test
        @DisplayName("有有效訂閱時回傳完整狀態資訊")
        void activeSubReturnsFullStatus() {
            LocalDateTime endDate = LocalDateTime.now(ZONE).plusDays(15);
            Subscription sub = buildActiveSub(USER_ID, "pro", endDate);
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));

            Plan pro = buildPlan("pro", "Pro", 49.0);
            when(planRepository.findById("pro")).thenReturn(Optional.of(pro));

            SubscriptionStatusResponse status = service.getStatus(USER_ID);

            assertThat(status.getPlanId()).isEqualTo("pro");
            assertThat(status.getPlanName()).isEqualTo("Pro");
            assertThat(status.getStatus()).isEqualTo("ACTIVE");
            assertThat(status.getCurrentPeriodEnd()).isEqualTo(endDate);
            assertThat(status.isActive()).isTrue();
        }

        @Test
        @DisplayName("無訂閱時回傳 status=NONE, active=false")
        void noSubReturnsNone() {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            SubscriptionStatusResponse status = service.getStatus(USER_ID);

            assertThat(status.getStatus()).isEqualTo("NONE");
            assertThat(status.isActive()).isFalse();
            assertThat(status.getPlanId()).isNull();
        }

        @Test
        @DisplayName("Plan 不存在時使用 planId 作為 planName fallback")
        void planNotFoundUsesPlanIdAsFallback() {
            Subscription sub = buildActiveSub(USER_ID, "unknown-plan", LocalDateTime.now(ZONE).plusDays(10));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findById("unknown-plan")).thenReturn(Optional.empty());

            SubscriptionStatusResponse status = service.getStatus(USER_ID);

            assertThat(status.getPlanName()).isEqualTo("unknown-plan");
        }
    }

    // ==================== isUserActive ====================

    @Nested
    @DisplayName("isUserActive -- 檢查訂閱是否有效")
    class IsUserActive {

        @Test
        @DisplayName("有有效訂閱時回傳 true")
        void hasActiveSubReturnsTrue() {
            Subscription sub = buildActiveSub(USER_ID, "basic", LocalDateTime.now(ZONE).plusDays(10));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));

            assertThat(service.isUserActive(USER_ID)).isTrue();
        }

        @Test
        @DisplayName("無訂閱時回傳 false")
        void noSubReturnsFalse() {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            assertThat(service.isUserActive(USER_ID)).isFalse();
        }
    }

    // ==================== getCheckoutInfo ====================

    @Nested
    @DisplayName("getCheckoutInfo -- 取得付款資訊")
    class GetCheckoutInfo {

        @Test
        @DisplayName("有效方案回傳錢包地址、金額、網路")
        void validPlanReturnsCheckoutInfo() {
            Plan basic = buildPlan("basic", "Basic", 19.0);
            when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));

            CryptoCheckoutResponse resp = service.getCheckoutInfo(USER_ID, "basic");

            assertThat(resp.getPlanId()).isEqualTo("basic");
            assertThat(resp.getPlanName()).isEqualTo("Basic");
            assertThat(resp.getAmountUsdt()).isEqualByComparingTo(BigDecimal.valueOf(19.0));
            assertThat(resp.getWalletAddress()).isEqualTo(WALLET_ADDRESS);
            assertThat(resp.getNetwork()).isEqualTo(NETWORK);
        }

        @Test
        @DisplayName("方案不存在時拋出 IllegalArgumentException")
        void planNotFoundThrows() {
            when(planRepository.findByPlanIdAndActiveTrue("nonexistent")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getCheckoutInfo(USER_ID, "nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("方案不存在");
        }

        @Test
        @DisplayName("免費方案（priceUsdt=0.0）拋出 IllegalStateException")
        void freePlanThrows() {
            Plan free = buildPlan("free", "Free", 0.0);
            when(planRepository.findByPlanIdAndActiveTrue("free")).thenReturn(Optional.of(free));

            assertThatThrownBy(() -> service.getCheckoutInfo(USER_ID, "free"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("無需付款");
        }

        @Test
        @DisplayName("priceUsdt 為 null 時拋出 IllegalStateException")
        void nullPriceThrows() {
            Plan plan = buildPlan("test", "Test", null);
            when(planRepository.findByPlanIdAndActiveTrue("test")).thenReturn(Optional.of(plan));

            assertThatThrownBy(() -> service.getCheckoutInfo(USER_ID, "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("無需付款");
        }

        @Test
        @DisplayName("priceUsdt 為負數時拋出 IllegalStateException")
        void negativePriceThrows() {
            Plan plan = buildPlan("test", "Test", -5.0);
            when(planRepository.findByPlanIdAndActiveTrue("test")).thenReturn(Optional.of(plan));

            assertThatThrownBy(() -> service.getCheckoutInfo(USER_ID, "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("無需付款");
        }
    }

    // ==================== submitPayment ====================

    @Nested
    @DisplayName("submitPayment -- 付款驗證")
    class SubmitPayment {

        @Nested
        @DisplayName("前置檢查")
        class PreChecks {

            @Test
            @DisplayName("方案不存在時拋出 IllegalArgumentException")
            void planNotFoundThrows() {
                when(planRepository.findByPlanIdAndActiveTrue("nonexistent")).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.submitPayment(USER_ID, "nonexistent", TX_HASH))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("方案不存在");
            }

            @Test
            @DisplayName("免費方案拋出 IllegalStateException")
            void freePlanThrows() {
                Plan free = buildPlan("free", "Free", 0.0);
                when(planRepository.findByPlanIdAndActiveTrue("free")).thenReturn(Optional.of(free));

                assertThatThrownBy(() -> service.submitPayment(USER_ID, "free", TX_HASH))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("免費方案");
            }

            @Test
            @DisplayName("txHash 已使用過拋出 IllegalArgumentException")
            void duplicateTxHashThrows() {
                Plan basic = buildPlan("basic", "Basic", 19.0);
                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH))
                        .thenReturn(Optional.of(PaymentHistory.builder().build()));

                assertThatThrownBy(() -> service.submitPayment(USER_ID, "basic", TX_HASH))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("已經使用過");
            }
        }

        @Nested
        @DisplayName("鏈上驗證失敗")
        class ChainVerificationFailure {

            @Test
            @DisplayName("TronService 驗證失敗時記錄 failed 付款並拋出 IllegalStateException")
            void verificationFailSavesFailedPaymentAndThrows() {
                Plan basic = buildPlan("basic", "Basic", 19.0);
                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(TronService.VerificationResult.fail("金額不足"));

                assertThatThrownBy(() -> service.submitPayment(USER_ID, "basic", TX_HASH))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("金額不足");

                verify(paymentHistoryRepository).save(any(PaymentHistory.class));
            }

            @Test
            @DisplayName("驗證失敗的 PaymentHistory 紀錄正確欄位")
            void failedPaymentHistoryHasCorrectFields() {
                Plan basic = buildPlan("basic", "Basic", 19.0);
                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(TronService.VerificationResult.fail("驗證失敗"));

                try {
                    service.submitPayment(USER_ID, "basic", TX_HASH);
                } catch (IllegalStateException ignored) {
                }

                ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
                verify(paymentHistoryRepository).save(captor.capture());
                PaymentHistory saved = captor.getValue();

                assertThat(saved.getStatus()).isEqualTo("failed");
                assertThat(saved.getSubscriptionId()).isNull();
                assertThat(saved.getUserId()).isEqualTo(USER_ID);
                assertThat(saved.getTxHash()).isEqualTo(TX_HASH);
                assertThat(saved.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(19.0));
                assertThat(saved.getCurrency()).isEqualTo("USDT");
                assertThat(saved.getNetwork()).isEqualTo(NETWORK);
                assertThat(saved.getWalletAddress()).isEqualTo(WALLET_ADDRESS);
                assertThat(saved.getPaidAt()).isNull();
            }
        }

        @Nested
        @DisplayName("金額精度驗證")
        class AmountPrecision {

            @Test
            @DisplayName("priceUsdt=19.0 時 TronService 收到正確的 BigDecimal")
            void price19PassedCorrectly() {
                Plan basic = buildPlan("basic", "Basic", 19.0);
                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(19.0));

                service.submitPayment(USER_ID, "basic", TX_HASH);

                ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
                verify(tronService).verifyTransaction(eq(TX_HASH), captor.capture());
                assertThat(captor.getValue()).isEqualByComparingTo(BigDecimal.valueOf(19.0));
            }

            @Test
            @DisplayName("priceUsdt=49.0 時 TronService 收到正確的 BigDecimal")
            void price49PassedCorrectly() {
                Plan pro = buildPlan("pro", "Pro", 49.0);
                when(planRepository.findByPlanIdAndActiveTrue("pro")).thenReturn(Optional.of(pro));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(49.0));

                service.submitPayment(USER_ID, "pro", TX_HASH);

                ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
                verify(tronService).verifyTransaction(eq(TX_HASH), captor.capture());
                assertThat(captor.getValue()).isEqualByComparingTo(BigDecimal.valueOf(49.0));
            }

            @Test
            @DisplayName("BigDecimal 精度保持正確（19.99 不因浮點損失）")
            void noFloatingPointPrecisionLoss() {
                Plan plan = buildPlan("test", "Test", 19.99);
                when(planRepository.findByPlanIdAndActiveTrue("test")).thenReturn(Optional.of(plan));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(19.99));

                service.submitPayment(USER_ID, "test", TX_HASH);

                ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
                verify(tronService).verifyTransaction(eq(TX_HASH), captor.capture());

                // BigDecimal.valueOf(19.99) should equal 19.99 exactly
                BigDecimal expected = BigDecimal.valueOf(19.99);
                assertThat(captor.getValue()).isEqualByComparingTo(expected);
                assertThat(captor.getValue().subtract(expected).abs())
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }

        @Nested
        @DisplayName("新訂閱建立")
        class NewSubscription {

            @BeforeEach
            void setUpCommon() {
                Plan basic = buildPlan("basic", "Basic", 19.0);
                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(19.0));
            }

            @Test
            @DisplayName("無現有訂閱時建立新的 ACTIVE 訂閱")
            void createsNewActiveSubscription() {
                service.submitPayment(USER_ID, "basic", TX_HASH);

                ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
                verify(subscriptionRepository).save(captor.capture());
                Subscription saved = captor.getValue();

                assertThat(saved.getUserId()).isEqualTo(USER_ID);
                assertThat(saved.getPlanId()).isEqualTo("basic");
                assertThat(saved.getStatus()).isEqualTo(Subscription.Status.ACTIVE);
            }

            @Test
            @DisplayName("新訂閱的 currentPeriodEnd 約為 now + 30 天")
            void newSubEndDateIsNowPlus30Days() {
                LocalDateTime before = LocalDateTime.now(ZONE).plusDays(29);
                service.submitPayment(USER_ID, "basic", TX_HASH);
                LocalDateTime after = LocalDateTime.now(ZONE).plusDays(31);

                ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
                verify(subscriptionRepository).save(captor.capture());
                Subscription saved = captor.getValue();

                assertThat(saved.getCurrentPeriodEnd()).isAfter(before);
                assertThat(saved.getCurrentPeriodEnd()).isBefore(after);
            }

            @Test
            @DisplayName("儲存 status=succeeded 的 PaymentHistory")
            void savesSucceededPaymentHistory() {
                service.submitPayment(USER_ID, "basic", TX_HASH);

                ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
                // save called once for subscription + once for payment history
                verify(paymentHistoryRepository).save(captor.capture());
                PaymentHistory saved = captor.getValue();

                assertThat(saved.getStatus()).isEqualTo("succeeded");
                assertThat(saved.getUserId()).isEqualTo(USER_ID);
            }

            @Test
            @DisplayName("回傳訊息包含方案名稱")
            void returnMessageContainsPlanName() {
                String result = service.submitPayment(USER_ID, "basic", TX_HASH);

                assertThat(result).contains("Basic");
                assertThat(result).contains("付款驗證成功");
            }

            @Test
            @DisplayName("PaymentHistory 紀錄正確的 txHash、network、walletAddress")
            void paymentHistoryHasCorrectFields() {
                service.submitPayment(USER_ID, "basic", TX_HASH);

                ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
                verify(paymentHistoryRepository).save(captor.capture());
                PaymentHistory saved = captor.getValue();

                assertThat(saved.getTxHash()).isEqualTo(TX_HASH);
                assertThat(saved.getNetwork()).isEqualTo(NETWORK);
                assertThat(saved.getWalletAddress()).isEqualTo(WALLET_ADDRESS);
                assertThat(saved.getCurrency()).isEqualTo("USDT");
                assertThat(saved.getPaidAt()).isNotNull();
            }
        }

        @Nested
        @DisplayName("訂閱延長")
        class SubscriptionExtension {

            @Test
            @DisplayName("currentEnd 在未來時，newEnd = currentEnd + 30 天")
            void futureEndExtendsFromCurrentEnd() {
                LocalDateTime futureEnd = LocalDateTime.now(ZONE).plusDays(10);
                Subscription existing = buildActiveSub(USER_ID, "basic", futureEnd);
                Plan basic = buildPlan("basic", "Basic", 19.0);

                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(existing));
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(19.0));

                service.submitPayment(USER_ID, "basic", TX_HASH);

                ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
                verify(subscriptionRepository).save(captor.capture());
                Subscription saved = captor.getValue();

                // newEnd should be approximately currentEnd + 30 days
                LocalDateTime expectedEnd = futureEnd.plusDays(30);
                assertThat(saved.getCurrentPeriodEnd()).isAfter(expectedEnd.minusMinutes(1));
                assertThat(saved.getCurrentPeriodEnd()).isBefore(expectedEnd.plusMinutes(1));
            }

            @Test
            @DisplayName("currentEnd 已過期時，newEnd = now + 30 天")
            void pastEndStartsFromNow() {
                LocalDateTime pastEnd = LocalDateTime.now(ZONE).minusDays(5);
                Subscription existing = buildActiveSub(USER_ID, "basic", pastEnd);
                Plan basic = buildPlan("basic", "Basic", 19.0);

                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(existing));
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(19.0));

                LocalDateTime before = LocalDateTime.now(ZONE).plusDays(29);
                service.submitPayment(USER_ID, "basic", TX_HASH);
                LocalDateTime after = LocalDateTime.now(ZONE).plusDays(31);

                ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
                verify(subscriptionRepository).save(captor.capture());
                Subscription saved = captor.getValue();

                assertThat(saved.getCurrentPeriodEnd()).isAfter(before);
                assertThat(saved.getCurrentPeriodEnd()).isBefore(after);
            }

            @Test
            @DisplayName("currentEnd 為 null 時，newEnd = now + 30 天")
            void nullEndStartsFromNow() {
                Subscription existing = buildActiveSub(USER_ID, "basic", null);
                Plan basic = buildPlan("basic", "Basic", 19.0);

                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(existing));
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(19.0));

                LocalDateTime before = LocalDateTime.now(ZONE).plusDays(29);
                service.submitPayment(USER_ID, "basic", TX_HASH);
                LocalDateTime after = LocalDateTime.now(ZONE).plusDays(31);

                ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
                verify(subscriptionRepository).save(captor.capture());
                Subscription saved = captor.getValue();

                assertThat(saved.getCurrentPeriodEnd()).isAfter(before);
                assertThat(saved.getCurrentPeriodEnd()).isBefore(after);
            }

            @Test
            @DisplayName("續約時更新 planId 為新方案")
            void renewalUpdatesPlanId() {
                LocalDateTime futureEnd = LocalDateTime.now(ZONE).plusDays(10);
                Subscription existing = buildActiveSub(USER_ID, "basic", futureEnd);
                Plan pro = buildPlan("pro", "Pro", 49.0);

                when(planRepository.findByPlanIdAndActiveTrue("pro")).thenReturn(Optional.of(pro));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(existing));
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(49.0));

                service.submitPayment(USER_ID, "pro", TX_HASH);

                ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
                verify(subscriptionRepository).save(captor.capture());

                assertThat(captor.getValue().getPlanId()).isEqualTo("pro");
            }
        }

        @Nested
        @DisplayName("付款金額記錄")
        class PaymentAmountRecording {

            @Test
            @DisplayName("PaymentHistory 記錄鏈上實際金額，而非方案價格")
            void recordsActualOnChainAmount() {
                Plan basic = buildPlan("basic", "Basic", 19.0);
                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
                // On-chain amount matches plan price
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(19.0));

                service.submitPayment(USER_ID, "basic", TX_HASH);

                ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
                verify(paymentHistoryRepository).save(captor.capture());

                // Payment amount should be the on-chain amount
                assertThat(captor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.valueOf(19.0));
            }

            @Test
            @DisplayName("超額付款時記錄實際鏈上金額（25 USDT for 19 方案 -> 記錄 25.0）")
            void overpaymentRecordsActualAmount() {
                Plan basic = buildPlan("basic", "Basic", 19.0);
                when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));
                when(paymentHistoryRepository.findByTxHash(TX_HASH)).thenReturn(Optional.empty());
                when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
                // User overpaid: 25 USDT for a 19 USDT plan
                when(tronService.verifyTransaction(eq(TX_HASH), any(BigDecimal.class)))
                        .thenReturn(successResult(25.0));

                service.submitPayment(USER_ID, "basic", TX_HASH);

                ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
                verify(paymentHistoryRepository).save(captor.capture());

                // Should record 25.0 (actual on-chain amount), not 19.0 (plan price)
                assertThat(captor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.valueOf(25.0));
            }
        }
    }

    // ==================== cancel ====================

    @Nested
    @DisplayName("cancel -- 取消訂閱")
    class Cancel {

        @Test
        @DisplayName("有有效訂閱時標記為 CANCELLED 並設定結束時間為現在")
        void activeSubMarkedCancelled() {
            Subscription sub = buildActiveSub(USER_ID, "basic", LocalDateTime.now(ZONE).plusDays(15));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));

            LocalDateTime before = LocalDateTime.now(ZONE).minusSeconds(1);
            service.cancel(USER_ID);
            LocalDateTime after = LocalDateTime.now(ZONE).plusSeconds(1);

            ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            Subscription saved = captor.getValue();

            assertThat(saved.getStatus()).isEqualTo(Subscription.Status.CANCELLED);
            assertThat(saved.getCurrentPeriodEnd()).isAfter(before);
            assertThat(saved.getCurrentPeriodEnd()).isBefore(after);
        }

        @Test
        @DisplayName("無訂閱時拋出 IllegalStateException")
        void noSubThrows() {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("沒有有效訂閱");
        }
    }

    // ==================== upgrade ====================

    @Nested
    @DisplayName("upgrade -- 升級方案")
    class Upgrade {

        @Test
        @DisplayName("成功升級方案後更新 planId")
        void successfulUpgradeUpdatesPlanId() {
            Subscription sub = buildActiveSub(USER_ID, "basic", LocalDateTime.now(ZONE).plusDays(15));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            Plan pro = buildPlan("pro", "Pro", 49.0);
            when(planRepository.findByPlanIdAndActiveTrue("pro")).thenReturn(Optional.of(pro));

            service.upgrade(USER_ID, "pro");

            ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
            verify(subscriptionRepository).save(captor.capture());
            assertThat(captor.getValue().getPlanId()).isEqualTo("pro");
        }

        @Test
        @DisplayName("無有效訂閱時拋出 IllegalStateException")
        void noActiveSubThrows() {
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.upgrade(USER_ID, "pro"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("沒有有效訂閱");
        }

        @Test
        @DisplayName("目標方案不存在時拋出 IllegalArgumentException")
        void planNotFoundThrows() {
            Subscription sub = buildActiveSub(USER_ID, "basic", LocalDateTime.now(ZONE).plusDays(15));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            when(planRepository.findByPlanIdAndActiveTrue("nonexistent")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.upgrade(USER_ID, "nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("方案不存在");
        }

        @Test
        @DisplayName("已經是同方案時拋出 IllegalArgumentException")
        void samePlanThrows() {
            Subscription sub = buildActiveSub(USER_ID, "basic", LocalDateTime.now(ZONE).plusDays(15));
            when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(sub));
            Plan basic = buildPlan("basic", "Basic", 19.0);
            when(planRepository.findByPlanIdAndActiveTrue("basic")).thenReturn(Optional.of(basic));

            assertThatThrownBy(() -> service.upgrade(USER_ID, "basic"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已經是此方案");
        }
    }

    // ==================== expireOverdueSubscriptions ====================

    @Nested
    @DisplayName("expireOverdueSubscriptions -- 標記到期訂閱")
    class ExpireOverdue {

        @Test
        @DisplayName("已過期的 ACTIVE 訂閱被標記為 CANCELLED")
        void expiredActiveSubsMarkedCancelled() {
            Subscription expired = Subscription.builder()
                    .id(1L).userId("u1").planId("basic")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).minusDays(1))
                    .build();
            when(subscriptionRepository.findAll()).thenReturn(List.of(expired));

            List<Subscription> result = service.expireOverdueSubscriptions();

            assertThat(result).hasSize(1);
            verify(subscriptionRepository).save(expired);
            assertThat(expired.getStatus()).isEqualTo(Subscription.Status.CANCELLED);
        }

        @Test
        @DisplayName("尚未到期的 ACTIVE 訂閱不受影響")
        void notYetExpiredSubsUntouched() {
            Subscription active = Subscription.builder()
                    .id(1L).userId("u1").planId("basic")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).plusDays(10))
                    .build();
            when(subscriptionRepository.findAll()).thenReturn(List.of(active));

            List<Subscription> result = service.expireOverdueSubscriptions();

            assertThat(result).isEmpty();
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("已 CANCELLED 的訂閱不會被重複處理")
        void alreadyCancelledSkipped() {
            Subscription cancelled = Subscription.builder()
                    .id(1L).userId("u1").planId("basic")
                    .status(Subscription.Status.CANCELLED)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).minusDays(1))
                    .build();
            when(subscriptionRepository.findAll()).thenReturn(List.of(cancelled));

            List<Subscription> result = service.expireOverdueSubscriptions();

            assertThat(result).isEmpty();
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("回傳所有被標記到期的訂閱清單")
        void returnsListOfExpiredSubs() {
            Subscription expired1 = Subscription.builder()
                    .id(1L).userId("u1").planId("basic")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).minusDays(1))
                    .build();
            Subscription expired2 = Subscription.builder()
                    .id(2L).userId("u2").planId("pro")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).minusDays(3))
                    .build();
            Subscription active = Subscription.builder()
                    .id(3L).userId("u3").planId("basic")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).plusDays(10))
                    .build();
            when(subscriptionRepository.findAll()).thenReturn(List.of(expired1, expired2, active));

            List<Subscription> result = service.expireOverdueSubscriptions();

            assertThat(result).hasSize(2);
            assertThat(result).extracting(Subscription::getUserId).containsExactly("u1", "u2");
            verify(subscriptionRepository, times(2)).save(any());
        }
    }

    // ==================== findExpiringSubscriptions ====================

    @Nested
    @DisplayName("findExpiringSubscriptions -- 即將到期查詢")
    class FindExpiring {

        @Test
        @DisplayName("結束日在 N 天內的 ACTIVE 訂閱被找到")
        void endWithinDaysFound() {
            Subscription expiringSoon = Subscription.builder()
                    .id(1L).userId("u1").planId("basic")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).plusDays(2))
                    .build();
            when(subscriptionRepository.findAll()).thenReturn(List.of(expiringSoon));

            List<Subscription> result = service.findExpiringSubscriptions(3);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo("u1");
        }

        @Test
        @DisplayName("結束日超過 N 天的訂閱不被包含")
        void endBeyondDaysExcluded() {
            Subscription farAway = Subscription.builder()
                    .id(1L).userId("u1").planId("basic")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).plusDays(15))
                    .build();
            when(subscriptionRepository.findAll()).thenReturn(List.of(farAway));

            List<Subscription> result = service.findExpiringSubscriptions(3);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("結束日已過期的訂閱不被包含")
        void pastEndExcluded() {
            Subscription past = Subscription.builder()
                    .id(1L).userId("u1").planId("basic")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).minusDays(1))
                    .build();
            when(subscriptionRepository.findAll()).thenReturn(List.of(past));

            List<Subscription> result = service.findExpiringSubscriptions(3);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("CANCELLED 狀態的訂閱不被包含")
        void cancelledExcluded() {
            Subscription cancelled = Subscription.builder()
                    .id(1L).userId("u1").planId("basic")
                    .status(Subscription.Status.CANCELLED)
                    .currentPeriodEnd(LocalDateTime.now(ZONE).plusDays(2))
                    .build();
            when(subscriptionRepository.findAll()).thenReturn(List.of(cancelled));

            List<Subscription> result = service.findExpiringSubscriptions(3);

            assertThat(result).isEmpty();
        }
    }
}
