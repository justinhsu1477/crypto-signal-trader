package com.trader.trading.service;

import com.trader.shared.entity.AdminAuditLog;
import com.trader.shared.service.AdminAuditService;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.trading.dto.signalsource.UpdateMirrorWebhookRequest;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.repository.SignalSourceConfigRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.repository.UserSignalSourceRepository;
import com.trader.trading.validation.CustomPromptValidator;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SignalSourceService.updateMirrorWebhook 行為測試。
 *
 * <p>Service 端職責：
 * <ul>
 *     <li>URL 格式驗證（必須是 discord.com / discordapp.com webhook）</li>
 *     <li>AES 加密入庫</li>
 *     <li>清除 URL 時強制 disabled=false</li>
 *     <li>內容沒變不 save / 不 audit</li>
 *     <li>所有改動寫 admin_audit_log（不存全文，只存 fingerprint）</li>
 * </ul>
 */
class SignalSourceServiceMirrorTest {

    private SignalSourceConfigRepository sourceRepository;
    private AdminAuditService adminAuditService;
    private AesEncryptionUtil aes;
    private MonitorConfigStore monitorConfigStore;
    private SignalSourceService service;

    @BeforeEach
    void setUp() {
        sourceRepository = mock(SignalSourceConfigRepository.class);
        adminAuditService = mock(AdminAuditService.class);
        aes = mock(AesEncryptionUtil.class);
        monitorConfigStore = mock(MonitorConfigStore.class);

        // MonitorConfigStore stub（syncMonitorConfig 會用到）
        when(monitorConfigStore.getCurrentConfig())
                .thenReturn(com.trader.trading.grpc.generated.MonitorConfig.newBuilder().build());
        when(monitorConfigStore.getDefaultChannelIdList())
                .thenReturn(java.util.List.of());
        when(sourceRepository.findByEnabledTrue()).thenReturn(java.util.List.of());

        // AES 模擬：encrypt 加上 "enc:" 前綴
        when(aes.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        service = new SignalSourceService(
                sourceRepository,
                mock(UserSignalSourceRepository.class),
                mock(TradeRepository.class),
                mock(UserRepository.class),
                monitorConfigStore,
                new CustomPromptValidator(),
                adminAuditService,
                aes);
    }

    private SignalSourceConfig sourceWith(String currentEncryptedUrl, boolean currentEnabled) {
        return SignalSourceConfig.builder()
                .id(42L)
                .name("chenge")
                .displayName("陳哥")
                .channelId("c1")
                .guildId("g1")
                .mirrorWebhookUrl(currentEncryptedUrl)
                .mirrorEnabled(currentEnabled)
                .build();
    }

    private UpdateMirrorWebhookRequest req(String url, boolean enabled, String reason) {
        UpdateMirrorWebhookRequest r = new UpdateMirrorWebhookRequest();
        r.setWebhookUrl(url);
        r.setEnabled(enabled);
        r.setReason(reason);
        return r;
    }

    @Test
    @DisplayName("set new webhook URL → encrypted via AES + saved + audit recorded")
    void setNewWebhook_encryptsAndPersists() {
        SignalSourceConfig src = sourceWith(null, false);
        when(sourceRepository.findById(42L)).thenReturn(Optional.of(src));
        when(sourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateMirrorWebhook(42L,
                req("https://discord.com/api/webhooks/123/abc-XYZ_def_long_enough_token_value", true, "陳哥上線"),
                "admin-1", "10.0.0.1");

        assertThat(src.getMirrorWebhookUrl())
                .isEqualTo("enc:https://discord.com/api/webhooks/123/abc-XYZ_def_long_enough_token_value");
        assertThat(src.isMirrorEnabled()).isTrue();
        verify(adminAuditService).record(
                eq(AdminAuditLog.Action.UPDATE_MIRROR_WEBHOOK),
                eq(AdminAuditLog.TargetType.SIGNAL_SOURCE),
                eq("42"),
                anyString(),
                anyString(),
                eq("陳哥上線"),
                eq("10.0.0.1"));
    }

    @Test
    @DisplayName("clear webhook URL (empty string) → enabled forced to false")
    void clearWebhook_forcesDisabled() {
        SignalSourceConfig src = sourceWith("enc:old-url", true);
        when(aes.decrypt("enc:old-url")).thenReturn("https://discord.com/api/webhooks/1/old-token-at-least-20chars");
        when(sourceRepository.findById(42L)).thenReturn(Optional.of(src));
        when(sourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // admin 試圖留 enabled=true 但 URL 是空 → service 強制 false
        service.updateMirrorWebhook(42L,
                req("", true, "rotate"),
                "admin-1", "10.0.0.1");

        assertThat(src.getMirrorWebhookUrl()).isNull();
        assertThat(src.isMirrorEnabled()).isFalse();
    }

    @Test
    @DisplayName("no actual change → skip save + skip audit (noise reduction)")
    void noChange_skipsAuditAndSave() {
        String webhookUrl = "https://discord.com/api/webhooks/123/abc-XYZ_def_long_enough_token_value";
        SignalSourceConfig src = sourceWith("enc:" + webhookUrl, true);
        when(aes.decrypt("enc:" + webhookUrl)).thenReturn(webhookUrl);
        when(sourceRepository.findById(42L)).thenReturn(Optional.of(src));

        service.updateMirrorWebhook(42L,
                req(webhookUrl, true, "no-op"),
                "admin-1", "10.0.0.1");

        verify(sourceRepository, never()).save(any());
        verify(adminAuditService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("invalid URL (not discord.com / discordapp.com) → rejected")
    void invalidUrl_rejected() {
        SignalSourceConfig src = sourceWith(null, false);
        when(sourceRepository.findById(42L)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.updateMirrorWebhook(42L,
                req("https://evil.example.com/api/webhooks/123/abc", true, "bad"),
                "admin-1", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discord webhook");
    }

    @Test
    @DisplayName("truncated URL (token < 20 chars) → rejected")
    void truncatedToken_rejected() {
        SignalSourceConfig src = sourceWith(null, false);
        when(sourceRepository.findById(42L)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.updateMirrorWebhook(42L,
                req("https://discord.com/api/webhooks/123/short", true, "bad"),
                "admin-1", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discord webhook");
    }

    @Test
    @DisplayName("source not found → IllegalArgumentException")
    void missingSource_throws() {
        when(sourceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMirrorWebhook(99L,
                req("https://discord.com/api/webhooks/123/abc-XYZ_def_long_enough_token_value", true, "x"),
                "admin-1", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("flip enabled flag only (URL same) → save + audit")
    void toggleEnabledOnly_persists() {
        String webhookUrl = "https://discord.com/api/webhooks/123/abc-XYZ_def_long_enough_token_value";
        SignalSourceConfig src = sourceWith("enc:" + webhookUrl, false);
        when(aes.decrypt("enc:" + webhookUrl)).thenReturn(webhookUrl);
        when(sourceRepository.findById(42L)).thenReturn(Optional.of(src));
        when(sourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateMirrorWebhook(42L,
                req(webhookUrl, true, "enable mirror"),
                "admin-1", "10.0.0.1");

        assertThat(src.isMirrorEnabled()).isTrue();
        verify(adminAuditService).record(
                eq(AdminAuditLog.Action.UPDATE_MIRROR_WEBHOOK),
                eq(AdminAuditLog.TargetType.SIGNAL_SOURCE),
                eq("42"),
                anyString(),
                anyString(),
                eq("enable mirror"),
                eq("10.0.0.1"));
    }
}
