package com.trader.trading.risk;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.service.MarketIndicatorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 多因子市場風控評分器 — 取代單一 EMA 趨勢過濾。
 *
 * 針對加密貨幣永續合約市場設計，使用以下指標：
 * 1. Funding Rate（0~30 分）：反映多空情緒，與馬丁逆勢邏輯契合
 * 2. Open Interest 變化（0~20 分）：反映倉位變化和潛在反轉
 * 3. RSI（0~30 分）：超買超賣判斷，與均值回歸策略一致
 * 4. ATR 波動度（0~20 分）：適度波動最佳，極端值扣分
 *
 * 總分 0~100，超過閾值才允許入場。
 */
@Slf4j
@Component
public class MarketRiskScorer {

    private static final int RSI_PERIOD = 14;

    private final MarketIndicatorService indicatorService;

    public MarketRiskScorer(MarketIndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    public RiskScoreResult evaluate(String symbol, TradeSignal.Side side, MartingaleStrategyConfig config) {
        double fundingRate = indicatorService.getFundingRate(symbol);
        double oiChange = indicatorService.getOpenInterestChange4h(symbol);
        double rsi = indicatorService.getRSI(symbol, RSI_PERIOD);
        double atrPercent = config.getAtrPeriod() > 0
                ? indicatorService.getATRPercent(symbol, config.getAtrPeriod())
                : Double.NaN;

        int frScore = scoreFundingRate(fundingRate, side);
        int oiScore = scoreOpenInterest(oiChange, side);
        int rsiScore = scoreRSI(rsi, side);
        int volScore = scoreVolatility(atrPercent, config.getAtrReferencePercent());

        int total = frScore + oiScore + rsiScore + volScore;

        String breakdown = String.format(
                "funding=%d, oi=%d, rsi=%d, vol=%d (fr=%.5f, oiΔ=%.3f, rsi=%.1f, atr%%=%.4f)",
                frScore, oiScore, rsiScore, volScore,
                Double.isNaN(fundingRate) ? 0 : fundingRate,
                Double.isNaN(oiChange) ? 0 : oiChange,
                Double.isNaN(rsi) ? 0 : rsi,
                Double.isNaN(atrPercent) ? 0 : atrPercent
        );

        log.debug("RiskScore {}/{}: total={} [{}]", symbol, side, total, breakdown);

        return new RiskScoreResult(total, frScore, oiScore, rsiScore, volScore, breakdown);
    }

    /**
     * Funding Rate 評分（0~30）。
     * 馬丁策略是逆勢策略，所以：
     * - LONG: funding 極端負值（市場過度看空）→ 高分
     * - SHORT: funding 極端正值（市場過度看多）→ 高分
     */
    int scoreFundingRate(double fundingRate, TradeSignal.Side side) {
        if (Double.isNaN(fundingRate)) return 15; // 無資料給中間分

        if (side == TradeSignal.Side.LONG) {
            if (fundingRate < -0.0001) return 30;       // 極端負費率 → 超賣
            if (fundingRate < 0.0) return 20;            // 輕微負費率
            if (fundingRate < 0.0001) return 10;         // 中性偏多
            return 0;                                    // 高正費率 → 市場已過度看多
        } else {
            if (fundingRate > 0.0003) return 30;         // 極端正費率 → 超買
            if (fundingRate > 0.0001) return 20;         // 明顯正費率
            if (fundingRate > 0.0) return 10;            // 輕微正費率
            return 0;                                    // 負費率 → 市場已過度看空
        }
    }

    /**
     * Open Interest 變化評分（0~20）。
     * OI 急增 + 方向與入場相反 = 可能超賣/超買（有利馬丁）。
     * OI 急減 = 平倉潮（趨勢可能反轉，有利馬丁）。
     */
    int scoreOpenInterest(double oiChangePercent, TradeSignal.Side side) {
        if (Double.isNaN(oiChangePercent)) return 10; // 無資料給中間分

        // OI 大幅減少（平倉潮）→ 反轉信號
        if (oiChangePercent < -0.05) return 20;

        if (side == TradeSignal.Side.LONG) {
            // LONG: OI 增加 + 價格下跌 = 空頭加倉 → 可能超賣
            if (oiChangePercent > 0.05) return 15;
            if (oiChangePercent > 0.02) return 10;
            return 5;
        } else {
            // SHORT: OI 增加 + 價格上漲 = 多頭加倉 → 可能超買
            if (oiChangePercent > 0.05) return 15;
            if (oiChangePercent > 0.02) return 10;
            return 5;
        }
    }

    /**
     * RSI 評分（0~30）。
     * 與均值回歸策略天然契合：超賣做多，超買做空。
     */
    int scoreRSI(double rsi, TradeSignal.Side side) {
        if (Double.isNaN(rsi)) return 15; // 無資料給中間分

        if (side == TradeSignal.Side.LONG) {
            if (rsi < 25) return 30;         // 深度超賣
            if (rsi < 35) return 20;         // 超賣
            if (rsi < 50) return 10;         // 中性偏弱
            return 0;                        // RSI > 50 → 不適合逆勢做多
        } else {
            if (rsi > 75) return 30;         // 深度超買
            if (rsi > 65) return 20;         // 超買
            if (rsi > 50) return 10;         // 中性偏強
            return 0;                        // RSI < 50 → 不適合逆勢做空
        }
    }

    /**
     * ATR 波動度評分（0~20）。
     * 適度波動最適合馬丁：太低則難成交，太高則風險過大。
     */
    int scoreVolatility(double atrPercent, double atrReferencePercent) {
        if (Double.isNaN(atrPercent) || atrReferencePercent <= 0) return 10; // 無資料給中間分

        double ratio = atrPercent / atrReferencePercent;

        if (ratio >= 1.0 && ratio <= 2.0) return 20;    // 適度波動（最佳）
        if (ratio >= 0.5 && ratio < 1.0) return 15;     // 偏低但可接受
        if (ratio > 2.0 && ratio <= 3.0) return 10;     // 偏高但可接受
        if (ratio < 0.5) return 5;                       // 太平靜，層難成交
        return 5;                                        // ratio > 3.0 太劇烈
    }
}
