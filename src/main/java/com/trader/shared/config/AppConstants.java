package com.trader.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * 全域應用常數
 *
 * 透過 Spring 啟動時讀取 application.yml 設定，
 * 寫入 static 欄位供 Entity（@PrePersist）等靜態 context 使用。
 *
 * 使用方式：
 * - Entity / 靜態方法：{@code AppConstants.ZONE_ID}
 * - @Scheduled zone：{@code zone = "${app.timezone}"}
 */
@Component
public class AppConstants {

    /** 應用時區（ZoneId），供 LocalDateTime.now(ZONE_ID) 使用 */
    public static ZoneId ZONE_ID = ZoneId.of("Asia/Taipei");

    /** 應用時區字串，供 @Scheduled zone 等需要 String 的場景 */
    public static String TIMEZONE_STR = "Asia/Taipei";

    @Value("${app.timezone:Asia/Taipei}")
    public void setTimezone(String tz) {
        ZONE_ID = ZoneId.of(tz);
        TIMEZONE_STR = tz;
    }
}
