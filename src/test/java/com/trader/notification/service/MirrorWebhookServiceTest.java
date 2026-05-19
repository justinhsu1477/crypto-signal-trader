package com.trader.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.config.MirrorConfig;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.trading.entity.DiscordRawMessage;
import com.trader.trading.entity.SignalSourceConfig;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for MirrorWebhookService — Discord webhook fan-out with 3-layer kill switch.
 *
 * <p>Layer 1: global config (MirrorConfig.enabled)
 * Layer 2: per-source flag (SignalSourceConfig.mirrorEnabled)
 * Layer 3: URL presence (mirrorWebhookUrl != null && != "")
 *
 * <p>Failures (decrypt error, HTTP non-2xx, IOException) MUST be swallowed — mirror is
 * observation infrastructure, must never break audit / broadcast main flow.
 */
class MirrorWebhookServiceTest {

    private OkHttpClient httpClient;
    private Call call;
    private AesEncryptionUtil aes;

    @BeforeEach
    void setUp() throws IOException {
        httpClient = mock(OkHttpClient.class);
        call = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(call);

        Response ok = new Response.Builder()
                .request(new Request.Builder().url("https://discord.com/api/webhooks/x/y").build())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .body(ResponseBody.create("", MediaType.parse("application/json")))
                .build();
        when(call.execute()).thenReturn(ok);

        aes = mock(AesEncryptionUtil.class);
        when(aes.decrypt("enc-url")).thenReturn("https://discord.com/api/webhooks/123/abc");
    }

    private MirrorWebhookService build(boolean globalEnabled) {
        MirrorConfig cfg = new MirrorConfig(globalEnabled);
        return new MirrorWebhookService(httpClient, new ObjectMapper(), aes, cfg);
    }

    private SignalSourceConfig source(boolean enabled, String encryptedUrl) {
        return SignalSourceConfig.builder()
                .id(42L)
                .name("chenge")
                .displayName("陳哥")
                .channelId("c1")
                .guildId("g1")
                .mirrorEnabled(enabled)
                .mirrorWebhookUrl(encryptedUrl)
                .build();
    }

    private DiscordRawMessage msg() {
        return DiscordRawMessage.builder()
                .messageId("m1")
                .sourceChannelId("c1")
                .sourceAuthorName("陳哥")
                .messageTimestamp(LocalDateTime.of(2026, 5, 16, 14, 32, 5))
                .content("BTC 80000 做空 SL 82000")
                .build();
    }

    @Test
    @DisplayName("L1 global disabled → no HTTP call")
    void globalDisabled_doesNotPost() throws IOException {
        build(false).mirrorAsync(source(true, "enc-url"), msg(), null);
        verify(httpClient, never()).newCall(any());
    }

    @Test
    @DisplayName("L2 per-source disabled → no HTTP call (even if global on)")
    void perSourceDisabled_doesNotPost() throws IOException {
        build(true).mirrorAsync(source(false, "enc-url"), msg(), null);
        verify(httpClient, never()).newCall(any());
    }

    @Test
    @DisplayName("L3 null webhook URL → no HTTP call")
    void nullUrl_doesNotPost() throws IOException {
        build(true).mirrorAsync(source(true, null), msg(), null);
        verify(httpClient, never()).newCall(any());
    }

    @Test
    @DisplayName("L3 blank webhook URL → no HTTP call")
    void blankUrl_doesNotPost() throws IOException {
        build(true).mirrorAsync(source(true, "   "), msg(), null);
        verify(httpClient, never()).newCall(any());
    }

    @Test
    @DisplayName("all 3 layers pass → POST to decrypted URL")
    void allEnabled_postsToDecryptedUrl() throws IOException {
        build(true).mirrorAsync(source(true, "enc-url"), msg(), null);
        ArgumentCaptor<Request> req = ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newCall(req.capture());
        assertThat(req.getValue().url().toString())
                .isEqualTo("https://discord.com/api/webhooks/123/abc");
    }

    @Test
    @DisplayName("mirror target overload → POST to decrypted target URL")
    void mirrorTarget_postsToDecryptedUrl() throws IOException {
        when(aes.decrypt("enc-target")).thenReturn("https://discord.com/api/webhooks/456/target");

        build(true).mirrorAsync("enc-target", "歐陽", "ouyang -> target-c1", msg(), null);

        ArgumentCaptor<Request> req = ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newCall(req.capture());
        assertThat(req.getValue().url().toString())
                .isEqualTo("https://discord.com/api/webhooks/456/target");
    }

    @Test
    @DisplayName("payload contains source display name as username + content as embed description")
    void payload_includesUsernameAndDescription() throws IOException {
        build(true).mirrorAsync(source(true, "enc-url"), msg(), null);
        ArgumentCaptor<Request> req = ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newCall(req.capture());

        okio.Buffer buf = new okio.Buffer();
        req.getValue().body().writeTo(buf);
        String body = buf.readUtf8();

        assertThat(body).contains("陳哥");
        assertThat(body).contains("BTC 80000 做空 SL 82000");
    }

    @Test
    @DisplayName("attachment URL set → payload includes embed.image.url")
    void imageMessage_includesEmbedImage() throws IOException {
        build(true).mirrorAsync(source(true, "enc-url"), msg(),
                "https://cdn.discordapp.com/attachments/x/y/banner.png");
        ArgumentCaptor<Request> req = ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newCall(req.capture());

        okio.Buffer buf = new okio.Buffer();
        req.getValue().body().writeTo(buf);
        String body = buf.readUtf8();

        assertThat(body).contains("banner.png");
        assertThat(body).contains("\"image\"");
    }

    @Test
    @DisplayName("AES decrypt throws → swallowed, no HTTP call")
    void aesDecryptThrows_swallowed() throws IOException {
        when(aes.decrypt("bad-cipher")).thenThrow(new RuntimeException("AES fail"));
        // 不該丟例外
        build(true).mirrorAsync(source(true, "bad-cipher"), msg(), null);
        verify(httpClient, never()).newCall(any());
    }

    @Test
    @DisplayName("HTTP IOException → swallowed (mirror failure must not break broadcast)")
    void httpThrows_swallowed() throws IOException {
        when(call.execute()).thenThrow(new IOException("Discord 503"));
        // 不該丟例外
        build(true).mirrorAsync(source(true, "enc-url"), msg(), null);
        verify(httpClient).newCall(any());
    }

    @Test
    @DisplayName("HTTP 4xx response → swallowed, logged but not thrown")
    void http4xx_swallowed() throws IOException {
        Response notFound = new Response.Builder()
                .request(new Request.Builder().url("https://discord.com/api/webhooks/x/y").build())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body(ResponseBody.create("{}", MediaType.parse("application/json")))
                .build();
        when(call.execute()).thenReturn(notFound);

        // 不該丟例外
        build(true).mirrorAsync(source(true, "enc-url"), msg(), null);
        verify(httpClient).newCall(any());
    }
}
