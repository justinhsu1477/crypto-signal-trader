package com.trader.notification.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trader.shared.config.LineConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * LINE Rich Menu 管理服務
 *
 * 負責：
 * - 啟動時自動建立/偵測兩組 Rich Menu（default / bound）
 * - 動態產生 Rich Menu 圖片（Java2D）
 * - 綁定成功後切換 per-user Rich Menu
 * - 解除綁定時移除 per-user Rich Menu（回落到 default）
 *
 * Rich Menu API 用同步呼叫（execute），因為需要拿回 richMenuId。
 * Per-user 切換用非同步（enqueue），不阻塞綁定流程。
 */
@Slf4j
@Service
public class LineRichMenuService {

    private static final String API_BASE = "https://api.line.me/v2/bot";
    private static final String API_DATA_BASE = "https://api-data.line.me/v2/bot";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final MediaType PNG_TYPE = MediaType.get("image/png");
    private static final Gson GSON = new Gson();

    private static final String MENU_NAME_DEFAULT = "hookfi-default";
    private static final String MENU_NAME_BOUND = "hookfi-bound";

    // 圖片尺寸（LINE 規範）
    private static final int WIDTH = 2500;
    private static final int HEIGHT = 1686;
    private static final int COLS = 3;
    private static final int ROWS = 2;

    private final OkHttpClient httpClient;
    private final LineConfig lineConfig;

    // 初始化後保存的 Menu ID
    private volatile String defaultMenuId;
    private volatile String boundMenuId;

    public LineRichMenuService(OkHttpClient httpClient, LineConfig lineConfig) {
        this.httpClient = httpClient;
        this.lineConfig = lineConfig;
    }

    // ==================== 初始化 ====================

    /**
     * 初始化 Rich Menu：檢查已存在 / 建立新的 / 設定全局預設
     */
    public void initializeMenus() {
        if (!isEnabled()) {
            log.info("LINE Rich Menu 已停用，跳過初始化");
            return;
        }

        log.info("開始初始化 LINE Rich Menu...");

        try {
            // 列出所有已存在的 Rich Menu
            JsonArray existingMenus = listRichMenus();

            // 找或建立 default menu
            defaultMenuId = findMenuByName(existingMenus, MENU_NAME_DEFAULT);
            if (defaultMenuId == null) {
                defaultMenuId = createMenu(MENU_NAME_DEFAULT, buildDefaultMenuJson());
                if (defaultMenuId != null) {
                    uploadMenuImage(defaultMenuId, generateDefaultImage());
                    log.info("已建立 default Rich Menu: {}", defaultMenuId);
                }
            } else {
                log.info("使用既有 default Rich Menu: {}", defaultMenuId);
            }

            // 找或建立 bound menu
            boundMenuId = findMenuByName(existingMenus, MENU_NAME_BOUND);
            if (boundMenuId == null) {
                boundMenuId = createMenu(MENU_NAME_BOUND, buildBoundMenuJson());
                if (boundMenuId != null) {
                    uploadMenuImage(boundMenuId, generateBoundImage());
                    log.info("已建立 bound Rich Menu: {}", boundMenuId);
                }
            } else {
                log.info("使用既有 bound Rich Menu: {}", boundMenuId);
            }

            // 設定 default menu 為全局預設
            if (defaultMenuId != null) {
                setDefaultMenu(defaultMenuId);
                log.info("已設定全局預設 Rich Menu: {}", defaultMenuId);
            }

            log.info("LINE Rich Menu 初始化完成 (default={}, bound={})", defaultMenuId, boundMenuId);
        } catch (Exception e) {
            log.error("LINE Rich Menu 初始化失敗（不影響其他功能）: {}", e.getMessage(), e);
        }
    }

    // ==================== Per-User 切換 ====================

    /**
     * 將用戶的 Rich Menu 切換到已綁定版本
     */
    public void linkBoundMenu(String lineUserId) {
        if (!isEnabled() || boundMenuId == null) return;

        Request request = new Request.Builder()
                .url(API_BASE + "/user/" + lineUserId + "/richmenu/" + boundMenuId)
                .addHeader("Authorization", bearer())
                .post(RequestBody.create("", JSON_TYPE))
                .build();

        httpClient.newCall(request).enqueue(logCallback("linkBoundMenu", lineUserId));
    }

    /**
     * 移除用戶的 per-user Rich Menu（回落到全局 default）
     */
    public void unlinkUserMenu(String lineUserId) {
        if (!isEnabled()) return;

        Request request = new Request.Builder()
                .url(API_BASE + "/user/" + lineUserId + "/richmenu")
                .addHeader("Authorization", bearer())
                .delete()
                .build();

        httpClient.newCall(request).enqueue(logCallback("unlinkUserMenu", lineUserId));
    }

    // ==================== LINE API 呼叫 ====================

    private JsonArray listRichMenus() throws IOException {
        Request request = new Request.Builder()
                .url(API_BASE + "/richmenu/list")
                .addHeader("Authorization", bearer())
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("列出 Rich Menu 失敗: HTTP {}", response.code());
                return new JsonArray();
            }
            JsonObject body = GSON.fromJson(response.body().string(), JsonObject.class);
            return body.has("richmenus") ? body.getAsJsonArray("richmenus") : new JsonArray();
        }
    }

    private String findMenuByName(JsonArray menus, String name) {
        for (JsonElement el : menus) {
            JsonObject menu = el.getAsJsonObject();
            if (name.equals(menu.get("name").getAsString())) {
                return menu.get("richMenuId").getAsString();
            }
        }
        return null;
    }

    private String createMenu(String name, String menuJson) throws IOException {
        Request request = new Request.Builder()
                .url(API_BASE + "/richmenu")
                .addHeader("Authorization", bearer())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(menuJson, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                log.error("建立 Rich Menu '{}' 失敗: HTTP {} - {}", name, response.code(), errorBody);
                return null;
            }
            JsonObject body = GSON.fromJson(response.body().string(), JsonObject.class);
            return body.get("richMenuId").getAsString();
        }
    }

    private void uploadMenuImage(String richMenuId, byte[] imageBytes) throws IOException {
        Request request = new Request.Builder()
                .url(API_DATA_BASE + "/richmenu/" + richMenuId + "/content")
                .addHeader("Authorization", bearer())
                .post(RequestBody.create(imageBytes, PNG_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                log.error("上傳 Rich Menu 圖片失敗: HTTP {} - {}", response.code(), errorBody);
            }
        }
    }

    private void setDefaultMenu(String richMenuId) throws IOException {
        Request request = new Request.Builder()
                .url(API_BASE + "/user/all/richmenu/" + richMenuId)
                .addHeader("Authorization", bearer())
                .post(RequestBody.create("", JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                log.warn("設定預設 Rich Menu 失敗: HTTP {} - {}", response.code(), errorBody);
            }
        }
    }

    // ==================== Rich Menu JSON ====================

    private String buildDefaultMenuJson() {
        String base = lineConfig.getRichMenu().getWebBaseUrl();
        return buildMenuJson(MENU_NAME_DEFAULT, new MenuButton[]{
                // Row 1
                new MenuButton(0, 0, "uri", "官網首頁", base),
                new MenuButton(1, 0, "uri", "訂閱方案", base + "/#pricing"),
                new MenuButton(2, 0, "uri", "註冊帳號", base + "/register"),
                // Row 2
                new MenuButton(0, 1, "message", "綁定帳號", "綁定"),
                new MenuButton(1, 1, "message", "聯繫客服", "客服"),
                new MenuButton(2, 1, "uri", "使用說明", base + "/blog"),
        });
    }

    private String buildBoundMenuJson() {
        String base = lineConfig.getRichMenu().getWebBaseUrl();
        return buildMenuJson(MENU_NAME_BOUND, new MenuButton[]{
                // Row 1
                new MenuButton(0, 0, "uri", "交易紀錄", base + "/trades"),
                new MenuButton(1, 0, "uri", "績效總覽", base + "/performance"),
                new MenuButton(2, 0, "uri", "通知設定", base + "/settings"),
                // Row 2
                new MenuButton(0, 1, "uri", "訂閱方案", base + "/#pricing"),
                new MenuButton(1, 1, "message", "聯繫客服", "客服"),
                new MenuButton(2, 1, "uri", "官網首頁", base),
        });
    }

    private record MenuButton(int col, int row, String type, String label, String value) {}

    private String buildMenuJson(String name, MenuButton[] buttons) {
        int cellW = WIDTH / COLS;
        int cellH = HEIGHT / ROWS;

        JsonObject menu = new JsonObject();
        menu.add("size", sizeJson(WIDTH, HEIGHT));
        menu.addProperty("selected", false);
        menu.addProperty("name", name);
        menu.addProperty("chatBarText", "📊 功能選單");

        JsonArray areas = new JsonArray();
        for (MenuButton btn : buttons) {
            JsonObject area = new JsonObject();
            area.add("bounds", boundsJson(btn.col() * cellW, btn.row() * cellH, cellW, cellH));

            JsonObject action = new JsonObject();
            action.addProperty("type", btn.type());
            action.addProperty("label", btn.label());
            if ("uri".equals(btn.type())) {
                action.addProperty("uri", btn.value());
            } else {
                action.addProperty("text", btn.value());
            }
            area.add("action", action);
            areas.add(area);
        }
        menu.add("areas", areas);

        return GSON.toJson(menu);
    }

    private JsonObject sizeJson(int w, int h) {
        JsonObject o = new JsonObject();
        o.addProperty("width", w);
        o.addProperty("height", h);
        return o;
    }

    private JsonObject boundsJson(int x, int y, int w, int h) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("width", w);
        o.addProperty("height", h);
        return o;
    }

    // ==================== 圖片產生（Java2D + Twemoji PNG）====================

    /** Emoji 圖片路徑（Twemoji，CC-BY 4.0） */
    private static final String EMOJI_DIR = "line/emoji/";

    /**
     * 每格設計：emoji 檔名、中文標籤
     * emoji 圖片來自 Twemoji（72×72 PNG），用 drawImage() 縮放繪製
     */
    private record CellDesign(String label, String emojiFile) {}

    private byte[] generateDefaultImage() throws IOException {
        CellDesign[] cells = {
                new CellDesign("官網首頁", "globe.png"),
                new CellDesign("訂閱方案", "money.png"),
                new CellDesign("註冊帳號", "memo.png"),
                new CellDesign("綁定帳號", "link.png"),
                new CellDesign("聯繫客服", "phone.png"),
                new CellDesign("使用說明", "book.png"),
        };
        return generateMenuImage(cells);
    }

    private byte[] generateBoundImage() throws IOException {
        CellDesign[] cells = {
                new CellDesign("交易紀錄", "chart-bar.png"),
                new CellDesign("績效總覽", "chart-up.png"),
                new CellDesign("通知設定", "gear.png"),
                new CellDesign("訂閱方案", "money.png"),
                new CellDesign("聯繫客服", "phone.png"),
                new CellDesign("官網首頁", "globe.png"),
        };
        return generateMenuImage(cells);
    }

    /**
     * 產生 Rich Menu 圖片（2500×1686，2×3 格）
     *
     * 設計：深色背景 + 格線 + Twemoji 彩色 emoji + 中文標籤
     * emoji 用 drawImage() 繪製（不依賴系統字型）
     * 中文標籤需要 CJK 字型（Docker 安裝 fonts-noto-cjk）
     */
    private byte[] generateMenuImage(CellDesign[] cells) throws IOException {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // 反鋸齒
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // 背景
        g.setColor(new Color(0x1A, 0x1A, 0x2E));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        int cellW = WIDTH / COLS;
        int cellH = HEIGHT / ROWS;

        // 格線
        g.setColor(new Color(0x2D, 0x2D, 0x44));
        g.setStroke(new BasicStroke(3));
        for (int c = 1; c < COLS; c++) {
            g.drawLine(c * cellW, 0, c * cellW, HEIGHT);
        }
        g.drawLine(0, cellH, WIDTH, cellH);

        // 中文標籤字型（CJK 字型由 Docker 的 fonts-noto-cjk 提供）
        Font labelFont = new Font("SansSerif", Font.BOLD, 56);

        // emoji 繪製尺寸（72px 原圖放大到 120px，在 2500px 寬的圖上看起來清晰）
        int emojiSize = 120;

        for (int i = 0; i < cells.length; i++) {
            CellDesign cell = cells[i];
            int col = i % COLS;
            int row = i / COLS;
            int cx = col * cellW + cellW / 2;
            int cy = row * cellH + cellH / 2;

            // 載入並繪製 emoji PNG（置中偏上）
            BufferedImage emoji = loadEmojiImage(cell.emojiFile());
            if (emoji != null) {
                int emojiX = cx - emojiSize / 2;
                int emojiY = cy - emojiSize / 2 - 50;
                g.drawImage(emoji, emojiX, emojiY, emojiSize, emojiSize, null);
            }

            // 中文標籤（emoji 下方）
            g.setFont(labelFont);
            g.setColor(new Color(0xDD, 0xDD, 0xDD));
            FontMetrics labelFm = g.getFontMetrics();
            int labelX = cx - labelFm.stringWidth(cell.label()) / 2;
            int labelY = cy + emojiSize / 2 - 50 + 40 + labelFm.getAscent();
            g.drawString(cell.label(), labelX, labelY);
        }

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 從 classpath 載入 Twemoji PNG
     */
    private BufferedImage loadEmojiImage(String filename) {
        try {
            ClassPathResource resource = new ClassPathResource(EMOJI_DIR + filename);
            try (InputStream is = resource.getInputStream()) {
                return ImageIO.read(is);
            }
        } catch (IOException e) {
            log.warn("載入 emoji 圖片失敗: {} — {}", filename, e.getMessage());
            return null;
        }
    }

    // ==================== Helpers ====================

    private boolean isEnabled() {
        return lineConfig.isEnabled()
                && lineConfig.getRichMenu() != null
                && lineConfig.getRichMenu().isEnabled();
    }

    private String bearer() {
        return "Bearer " + lineConfig.getChannelAccessToken();
    }

    private Callback logCallback(String operation, String lineUserId) {
        return new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("LINE {} 失敗 (lineUserId={}): {}", operation, lineUserId, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (response.isSuccessful()) {
                        log.debug("LINE {} 成功 (lineUserId={})", operation, lineUserId);
                    } else {
                        log.warn("LINE {} 異常 (lineUserId={}): HTTP {}",
                                operation, lineUserId, response.code());
                    }
                }
            }
        };
    }

    // ==================== Test Helpers ====================

    /** 供測試用：取得 default menu ID */
    String getDefaultMenuId() {
        return defaultMenuId;
    }

    /** 供測試用：取得 bound menu ID */
    String getBoundMenuId() {
        return boundMenuId;
    }
}
