package com.trader.trading.service;

import com.trader.trading.config.ShadowGraduationConfig;
import com.trader.trading.dto.signalsource.ShadowGraduationResult;
import com.trader.trading.dto.signalsource.ShadowGraduationResult.GraduationStatus;
import com.trader.trading.dto.signalsource.SignalSourcePerformanceDto;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.repository.SignalSourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SHADOW 畢業評估服務
 *
 * 評估所有 SHADOW + paperTradingEnabled 頻道的模擬交易績效，
 * 比較四項指標與畢業門檻，產生畢業狀態：
 * - READY（4/4 通過）
 * - APPROACHING（3/4 通過）
 * - NOT_READY（≤ 2/4 通過）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowGraduationService {

    private final SignalSourceConfigRepository sourceRepository;
    private final SignalSourceService signalSourceService;
    private final ShadowGraduationConfig config;

    /**
     * 評估所有 SHADOW 頻道的畢業狀態
     */
    public List<ShadowGraduationResult> evaluateAll() {
        List<SignalSourceConfig> shadowSources = sourceRepository.findByEnabledTrue().stream()
                .filter(s -> s.getTradeMode() == SignalSourceConfig.TradeMode.SHADOW)
                .filter(SignalSourceConfig::isPaperTradingEnabled)
                .collect(Collectors.toList());

        return shadowSources.stream()
                .map(this::evaluate)
                .collect(Collectors.toList());
    }

    /**
     * 評估單一來源的畢業狀態
     */
    ShadowGraduationResult evaluate(SignalSourceConfig source) {
        SignalSourcePerformanceDto perf;
        try {
            perf = signalSourceService.getSourcePerformance(source.getId(), "all");
        } catch (Exception e) {
            log.warn("評估 SHADOW 來源績效失敗: sourceId={} error={}", source.getId(), e.getMessage());
            return buildNotReadyResult(source);
        }

        // 逐項比較
        boolean tradesPass = perf.getPaperTradeCount() >= config.getMinTrades();
        boolean winRatePass = perf.getPaperWinRate() >= config.getMinWinRate();
        boolean profitFactorPass = perf.getPaperProfitFactor() >= config.getMinProfitFactor();
        boolean consecutiveLossesPass = perf.getPaperMaxConsecutiveLosses() <= config.getMaxConsecutiveLosses();

        int passed = 0;
        if (tradesPass) passed++;
        if (winRatePass) passed++;
        if (profitFactorPass) passed++;
        if (consecutiveLossesPass) passed++;

        GraduationStatus status;
        if (passed == 4) {
            status = GraduationStatus.READY;
        } else if (passed == 3) {
            status = GraduationStatus.APPROACHING;
        } else {
            status = GraduationStatus.NOT_READY;
        }

        return ShadowGraduationResult.builder()
                .sourceId(source.getId())
                .name(source.getName())
                .displayName(source.getDisplayName())
                .paperTradeCount(perf.getPaperTradeCount())
                .paperWinRate(perf.getPaperWinRate())
                .paperProfitFactor(perf.getPaperProfitFactor())
                .paperMaxConsecutiveLosses(perf.getPaperMaxConsecutiveLosses())
                .paperTotalPnl(perf.getPaperTotalPnl())
                .tradesPass(tradesPass)
                .winRatePass(winRatePass)
                .profitFactorPass(profitFactorPass)
                .consecutiveLossesPass(consecutiveLossesPass)
                .passedCriteria(passed)
                .status(status)
                .build();
    }

    private ShadowGraduationResult buildNotReadyResult(SignalSourceConfig source) {
        return ShadowGraduationResult.builder()
                .sourceId(source.getId())
                .name(source.getName())
                .displayName(source.getDisplayName())
                .status(GraduationStatus.NOT_READY)
                .passedCriteria(0)
                .build();
    }
}
