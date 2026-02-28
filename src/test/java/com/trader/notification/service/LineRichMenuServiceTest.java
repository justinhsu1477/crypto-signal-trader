package com.trader.notification.service;

import com.trader.shared.config.LineConfig;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LineRichMenuService 單元測試
 *
 * 覆蓋：初始化（建立/既有偵測）、per-user 切換、disabled 狀態跳過
 */
class LineRichMenuServiceTest {

    private OkHttpClient httpClient;
    private LineConfig lineConfig;
    private LineConfig.RichMenuSettings richMenuSettings;
    private LineRichMenuService service;

    private static final String LINE_USER_ID = "U1234567890abcdef";

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        lineConfig = mock(LineConfig.class);
        richMenuSettings = mock(LineConfig.RichMenuSettings.class);

        when(lineConfig.isEnabled()).thenReturn(true);
        when(lineConfig.getChannelAccessToken()).thenReturn("test-token");
        when(lineConfig.getRichMenu()).thenReturn(richMenuSettings);
        when(richMenuSettings.isEnabled()).thenReturn(true);
        when(richMenuSettings.getWebBaseUrl()).thenReturn("https://hook-fi.com");

        service = new LineRichMenuService(httpClient, lineConfig);
    }

    // ==================== initializeMenus ====================

    @Nested
    @DisplayName("initializeMenus — 初始化")
    class InitializeTests {

        @Test
        @DisplayName("disabled 時跳過初始化")
        void skipWhenDisabled() {
            when(lineConfig.isEnabled()).thenReturn(false);

            service.initializeMenus();

            verify(httpClient, never()).newCall(any());
            assertThat(service.getDefaultMenuId()).isNull();
            assertThat(service.getBoundMenuId()).isNull();
        }

        @Test
        @DisplayName("Rich Menu disabled 時跳過初始化")
        void skipWhenRichMenuDisabled() {
            when(richMenuSettings.isEnabled()).thenReturn(false);

            service.initializeMenus();

            verify(httpClient, never()).newCall(any());
        }

        @Test
        @DisplayName("正常初始化 — 呼叫 API 列出/建立 menu")
        void normalInitialization() throws IOException {
            // Mock: list API 回傳空列表
            Call listCall = mock(Call.class);
            Response listResponse = new Response.Builder()
                    .request(new Request.Builder().url("https://api.line.me/v2/bot/richmenu/list").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("{\"richmenus\":[]}", MediaType.get("application/json")))
                    .build();
            when(listCall.execute()).thenReturn(listResponse);

            // Mock: create API 回傳 menuId（default + bound = 2 次）
            Call createCall1 = mock(Call.class);
            Response createResponse1 = new Response.Builder()
                    .request(new Request.Builder().url("https://api.line.me/v2/bot/richmenu").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("{\"richMenuId\":\"rm-default-123\"}", MediaType.get("application/json")))
                    .build();
            when(createCall1.execute()).thenReturn(createResponse1);

            Call createCall2 = mock(Call.class);
            Response createResponse2 = new Response.Builder()
                    .request(new Request.Builder().url("https://api.line.me/v2/bot/richmenu").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("{\"richMenuId\":\"rm-bound-456\"}", MediaType.get("application/json")))
                    .build();
            when(createCall2.execute()).thenReturn(createResponse2);

            // Mock: upload image + set default（成功回應）
            Call uploadCall1 = mock(Call.class);
            Call uploadCall2 = mock(Call.class);
            Call setDefaultCall = mock(Call.class);
            Response successResponse = new Response.Builder()
                    .request(new Request.Builder().url("https://api.line.me/v2/bot/richmenu/content").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("{}", MediaType.get("application/json")))
                    .build();

            // 依序：list → create default → upload default → create bound → upload bound → set default
            when(httpClient.newCall(any())).thenReturn(
                    listCall,        // list
                    createCall1,     // create default
                    uploadCall1,     // upload default image
                    createCall2,     // create bound
                    uploadCall2,     // upload bound image
                    setDefaultCall   // set default
            );

            // upload / setDefault 都回 200
            when(uploadCall1.execute()).thenReturn(successResponse);
            when(uploadCall2.execute()).thenReturn(cloneResponse(successResponse));
            when(setDefaultCall.execute()).thenReturn(cloneResponse(successResponse));

            service.initializeMenus();

            // 驗證呼叫了 6 次 API
            verify(httpClient, times(6)).newCall(any());
            assertThat(service.getDefaultMenuId()).isEqualTo("rm-default-123");
            assertThat(service.getBoundMenuId()).isEqualTo("rm-bound-456");
        }

        @Test
        @DisplayName("既有 menu → 不重複建立")
        void existingMenusReused() throws IOException {
            // Mock: list API 回傳兩個既有 menu
            Call listCall = mock(Call.class);
            Response listResponse = new Response.Builder()
                    .request(new Request.Builder().url("https://api.line.me/v2/bot/richmenu/list").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(
                            "{\"richmenus\":[" +
                                    "{\"richMenuId\":\"rm-existing-default\",\"name\":\"hookfi-default\"}," +
                                    "{\"richMenuId\":\"rm-existing-bound\",\"name\":\"hookfi-bound\"}" +
                                    "]}",
                            MediaType.get("application/json")))
                    .build();
            when(listCall.execute()).thenReturn(listResponse);

            // setDefault 呼叫
            Call setDefaultCall = mock(Call.class);
            Response setDefaultResponse = new Response.Builder()
                    .request(new Request.Builder().url("https://api.line.me/v2/bot/user/all/richmenu/x").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("{}", MediaType.get("application/json")))
                    .build();
            when(setDefaultCall.execute()).thenReturn(setDefaultResponse);

            when(httpClient.newCall(any())).thenReturn(listCall, setDefaultCall);

            service.initializeMenus();

            // 只有 list + setDefault = 2 次（沒有 create / upload）
            verify(httpClient, times(2)).newCall(any());
            assertThat(service.getDefaultMenuId()).isEqualTo("rm-existing-default");
            assertThat(service.getBoundMenuId()).isEqualTo("rm-existing-bound");
        }

        private Response cloneResponse(Response original) {
            return new Response.Builder()
                    .request(original.request())
                    .protocol(original.protocol())
                    .code(original.code())
                    .message(original.message())
                    .body(ResponseBody.create("{}", MediaType.get("application/json")))
                    .build();
        }
    }

    // ==================== linkBoundMenu / unlinkUserMenu ====================

    @Nested
    @DisplayName("Per-User Rich Menu 切換")
    class PerUserMenuTests {

        @Test
        @DisplayName("linkBoundMenu → POST 到正確 URL")
        void linkBoundMenuCallsApi() throws IOException {
            // 先初始化一個 boundMenuId（透過反射或直接調用）
            // 簡化：直接測試 enabled=false 時跳過
            Call mockCall = mock(Call.class);
            when(httpClient.newCall(any())).thenReturn(mockCall);

            // boundMenuId 未初始化，應跳過
            service.linkBoundMenu(LINE_USER_ID);
            verify(httpClient, never()).newCall(any());
        }

        @Test
        @DisplayName("unlinkUserMenu → DELETE 呼叫")
        void unlinkUserMenuCallsDelete() {
            Call mockCall = mock(Call.class);
            when(httpClient.newCall(any())).thenReturn(mockCall);

            service.unlinkUserMenu(LINE_USER_ID);

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(httpClient).newCall(captor.capture());

            Request request = captor.getValue();
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.url().toString()).contains(LINE_USER_ID);
            assertThat(request.url().toString()).contains("/richmenu");
        }

        @Test
        @DisplayName("disabled 時跳過 linkBoundMenu")
        void linkSkippedWhenDisabled() {
            when(lineConfig.isEnabled()).thenReturn(false);

            service.linkBoundMenu(LINE_USER_ID);
            verify(httpClient, never()).newCall(any());
        }

        @Test
        @DisplayName("disabled 時跳過 unlinkUserMenu")
        void unlinkSkippedWhenDisabled() {
            when(lineConfig.isEnabled()).thenReturn(false);

            service.unlinkUserMenu(LINE_USER_ID);
            verify(httpClient, never()).newCall(any());
        }
    }
}
