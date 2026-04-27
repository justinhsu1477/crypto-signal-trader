package com.trader.trading.service;

import com.trader.notification.service.NotificationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WeeklyTradeReportService — 每週交易績效報告")
class WeeklyTradeReportServiceTest {

    private EntityManager em;
    private NotificationService notificationService;
    private WeeklyTradeReportService service;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        notificationService = mock(NotificationService.class);
        service = new WeeklyTradeReportService(em, notificationService);
    }

    private Query mockOverallQuery(long total, long wins, long losses,
                                    double pnl, double best, double worst) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(new Object[]{total, wins, losses, pnl, best, worst});
        return q;
    }

    private Query mockListQuery(List<Object[]> rows) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.setParameter(anyString(), anyInt())).thenReturn(q);
        when(q.getResultList()).thenReturn(rows);
        return q;
    }

    @Nested
    @DisplayName("computeLastWeekWindow")
    class WindowComputeTests {

        @Test
        @DisplayName("窗口結束在本週一 00:00、起點在上週一 00:00")
        void windowAlignsToMondays() {
            WeeklyTradeReportService.Window w = service.computeLastWeekWindow();

            assertThat(w.start.toLocalTime().toString()).isEqualTo("00:00");
            assertThat(w.end.toLocalTime().toString()).isEqualTo("00:00");
            // 兩端都是週一
            assertThat(w.start.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(w.end.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            // 差 7 天
            assertThat(w.start.toLocalDate().plusDays(7)).isEqualTo(w.end.toLocalDate());
        }
    }

    @Nested
    @DisplayName("sendWeeklyReport — 整體流程")
    class SendWeeklyReportTests {

        @Test
        @DisplayName("無交易 → 跳過推送，不呼叫 Discord")
        void noTradesSkipsPush() {
            // 先建好 query mock，避免 Mockito UnfinishedStubbingException
            Query overall = mockOverallQuery(0, 0, 0, 0, 0, 0);
            when(em.createNativeQuery(anyString())).thenReturn(overall);

            service.sendWeeklyReport();

            verify(notificationService, never())
                    .sendNotificationToAdmins(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("有正獲利交易 → 推送 GREEN 色 + 標題含日期")
        void positivePnlPushesGreen() {
            // 第一個 createNativeQuery 是 overall summary
            // 後續是 top users / sources / sides — 各回空 list
            Query overall = mockOverallQuery(10, 8, 2, 500.0, 200.0, -50.0);
            List<Object[]> usersList = new java.util.ArrayList<>();
            usersList.add(new Object[]{"User A", 5L, 300.0});
            Query users = mockListQuery(usersList);
            List<Object[]> sourcesList = new java.util.ArrayList<>();
            sourcesList.add(new Object[]{"陳哥", 10L, 500.0});
            Query sources = mockListQuery(sourcesList);
            List<Object[]> sidesList = new java.util.ArrayList<>();
            sidesList.add(new Object[]{"SHORT", 7L, 400.0});
            sidesList.add(new Object[]{"LONG", 3L, 100.0});
            Query sides = mockListQuery(sidesList);

            when(em.createNativeQuery(anyString()))
                    .thenReturn(overall, users, sources, sides);

            service.sendWeeklyReport();

            verify(notificationService).sendNotificationToAdmins(
                    org.mockito.ArgumentMatchers.contains("週報"),
                    org.mockito.ArgumentMatchers.contains("+500.00"),
                    eq(com.trader.notification.service.DiscordWebhookService.COLOR_GREEN));
        }

        @Test
        @DisplayName("有虧損 → RED 色")
        void negativePnlPushesRed() {
            Query overall = mockOverallQuery(10, 3, 7, -200.0, 50.0, -100.0);
            Query users = mockListQuery(List.of());
            Query sources = mockListQuery(List.of());
            Query sides = mockListQuery(List.of());

            when(em.createNativeQuery(anyString()))
                    .thenReturn(overall, users, sources, sides);

            service.sendWeeklyReport();

            verify(notificationService).sendNotificationToAdmins(
                    anyString(), anyString(),
                    eq(com.trader.notification.service.DiscordWebhookService.COLOR_RED));
        }
    }

    @Nested
    @DisplayName("buildBody — 訊息格式化")
    class BuildBodyTests {

        @Test
        @DisplayName("訊息含總覽 + Top 用戶 + 訊號來源 + 多空")
        void bodyContainsAllSections() {
            WeeklyTradeReportService.Window w = service.computeLastWeekWindow();
            WeeklyTradeReportService.Summary s = new WeeklyTradeReportService.Summary();
            s.totalTrades = 142;
            s.wins = 142;
            s.losses = 0;
            s.totalPnl = 8501.03;
            s.bestTrade = 296.54;
            s.worstTrade = 0.08;

            WeeklyTradeReportService.UserStat u = new WeeklyTradeReportService.UserStat();
            u.name = "pillowman";
            u.trades = 12;
            u.pnl = 1925.33;
            s.topUsers.add(u);

            WeeklyTradeReportService.SourceStat src = new WeeklyTradeReportService.SourceStat();
            src.name = "陳哥合約頻道";
            src.trades = 123;
            src.pnl = 7911.70;
            s.sources.add(src);

            s.sideCounts.put("SHORT", 122L);
            s.sidePnl.put("SHORT", 8062.36);

            String body = service.buildBody(w, s);

            assertThat(body).contains("142 筆");
            assertThat(body).contains("+8501.03");
            assertThat(body).contains("100%");
            assertThat(body).contains("pillowman");
            assertThat(body).contains("+1925.33");
            assertThat(body).contains("陳哥合約頻道");
            assertThat(body).contains("SHORT");
        }
    }
}
