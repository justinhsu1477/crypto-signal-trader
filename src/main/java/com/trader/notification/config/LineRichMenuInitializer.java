package com.trader.notification.config;

import com.trader.notification.service.LineRichMenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 啟動時初始化 LINE Rich Menu
 *
 * 使用 ApplicationRunner 確保在 Spring Context 完全就緒後才執行。
 * 失敗時只 log error，不影響應用正常啟動。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineRichMenuInitializer implements ApplicationRunner {

    private final LineRichMenuService richMenuService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            richMenuService.initializeMenus();
        } catch (Exception e) {
            log.error("LINE Rich Menu 初始化失敗（不影響應用啟動）: {}", e.getMessage(), e);
        }
    }
}
