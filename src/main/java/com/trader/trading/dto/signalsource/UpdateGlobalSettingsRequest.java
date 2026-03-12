package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 全局監聽設定（非 per-source）— authorIds、ignoreKeywords
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGlobalSettingsRequest {

    /** 僅監聽指定作者的訊息（空 = 不過濾） */
    private List<String> authorIds;

    /** 忽略含有這些關鍵字的訊息 */
    private List<String> ignoreKeywords;
}
