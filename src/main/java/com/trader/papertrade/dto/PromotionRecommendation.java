package com.trader.papertrade.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 自動升 AUTO 的推薦結果 — 單一 source 的決策 + 原因。
 *
 * <p>Decision 分 3 級：
 * <ul>
 *   <li><b>PROMOTE</b>: 達標所有門檻，建議手動 review 後升 AUTO</li>
 *   <li><b>MONITOR</b>: 資料不足或部分指標達標，持續觀察</li>
 *   <li><b>REJECT</b>: 明顯不該升 (low win rate / negative PnL / 過大 DD)</li>
 * </ul>
 */
@Data
@Builder
public class PromotionRecommendation {

    public enum Decision { PROMOTE, MONITOR, REJECT }

    private SourcePerformanceMetrics metrics;
    private Decision decision;
    /** 每條門檻的 pass/fail 描述，給 Discord 通知用 */
    private List<String> reasons;
}
