package com.trader.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Admin 更新 Monitor 頻道設定的 Request DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateChannelsRequest {

    /** 要監聽的 Discord 頻道 ID 清單（必填） */
    private List<String> channelIds;

    /** 伺服器（Guild）ID 過濾（選填） */
    private List<String> guildIds;

    /** 作者 ID 過濾（選填） */
    private List<String> authorIds;

    /** 訊息內容黑名單關鍵字（選填） */
    private List<String> ignoreKeywords;
}
