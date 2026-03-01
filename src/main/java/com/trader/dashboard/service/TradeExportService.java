package com.trader.dashboard.service;

import com.trader.shared.config.AppConstants;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 交易紀錄匯出服務
 *
 * 將用戶的已平倉交易產生為 CSV 格式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeExportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String CSV_HEADER = "Date,Symbol,Side,Entry Price,Exit Price,Quantity,Leverage,Gross Profit,Commission,Net Profit,Exit Reason,DCA Count";

    private final TradeRepository tradeRepository;

    /**
     * 產生 CSV 字串
     *
     * @param userId  用戶 ID
     * @param days    查詢天數（往回推）
     * @param maxRows 最大筆數上限
     * @return CSV 內容（含 header）
     */
    public String generateCsv(String userId, int days, int maxRows) {
        LocalDateTime from = LocalDateTime.now(AppConstants.ZONE_ID).minusDays(days);
        List<Trade> trades = tradeRepository.findUserClosedTradesAfter(userId, from);

        if (trades.size() > maxRows) {
            trades = trades.subList(0, maxRows);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(CSV_HEADER).append("\n");

        for (Trade t : trades) {
            sb.append(formatDate(t.getExitTime())).append(",");
            sb.append(safe(t.getSymbol())).append(",");
            sb.append(safe(t.getSide())).append(",");
            sb.append(formatDouble(t.getEntryPrice())).append(",");
            sb.append(formatDouble(t.getExitPrice())).append(",");
            sb.append(formatDouble(t.getEntryQuantity())).append(",");
            sb.append(t.getLeverage() != null ? t.getLeverage() : "").append(",");
            sb.append(formatDouble(t.getGrossProfit())).append(",");
            sb.append(formatDouble(t.getCommission())).append(",");
            sb.append(formatDouble(t.getNetProfit())).append(",");
            sb.append(safe(t.getExitReason())).append(",");
            sb.append(t.getDcaCount() != null ? t.getDcaCount() : 0);
            sb.append("\n");
        }

        log.info("📊 CSV 匯出完成: userId={} trades={} days={}", userId, trades.size(), days);
        return sb.toString();
    }

    private static String formatDate(LocalDateTime dt) {
        return dt != null ? dt.format(DATE_FMT) : "";
    }

    private static String formatDouble(Double value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String safe(String value) {
        if (value == null) return "";
        // CSV 欄位含逗號或雙引號時需包裹
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
