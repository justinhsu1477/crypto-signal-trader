# Caddy 實作文件（本地訊號入口 + 雲端廣播執行）

> 適用於本專案目前架構：
> - 本地：`Discord Desktop + CDP + discord-monitor`
> - 雲端 VM：`Spring Boot API + Web Dashboard`
> - 目標：用 `Caddy` 作為對外入口（HTTPS / 路由分流 / 基本安全控制）

---

## 1. 為什麼用 Caddy

Caddy 是一個反向代理（Reverse Proxy）與 Web Server，適合本專案的原因：

1. 自動 HTTPS（Let's Encrypt）
2. 設定檔簡單，適合單 VM 架構
3. 可把前端與 API 收斂到同一個網域
4. 可對敏感端點（如 monitor 端點）做 IP allowlist

費用說明：

- `Caddy（開源版）`：免費
- `Let's Encrypt 憑證`：免費
- 需要付費的是：`VM 主機`、`網域`

---

## 2. 目標部署拓樸

```text
外部使用者 / 本地 monitor
        |
      HTTPS:443
        |
      Caddy (VM)
      /        \
  /api/*      /*
   |           |
 Java API    Web Dashboard
 8080         3000
 (內網)       (內網)
```

重點：

1. 對外只開 `80/443`
2. `8080/3000` 不直接暴露公網
3. monitor 呼叫雲端 API 一律走 `HTTPS`

---

## 3. 前置條件

請先準備好：

1. 一台 Linux VM（建議 Ubuntu 22.04+）
2. 一個網域（例如 `app.yourdomain.com`）
3. DNS `A` 紀錄指向 VM 公網 IP
4. 雲端防火牆只開：
   - `80/tcp`
   - `443/tcp`
5. Spring Boot API 與 Web Dashboard 已可在 VM 上啟動

建議服務綁定（只聽本機）：

- Spring Boot：`127.0.0.1:8080`
- Web Dashboard：`127.0.0.1:3000`

---

## 4. 安裝 Caddy（Ubuntu / Debian）

```bash
sudo apt update
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl

curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg

curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list

sudo apt update
sudo apt install -y caddy
```

安裝後常用指令：

```bash
sudo systemctl status caddy
sudo systemctl restart caddy
sudo systemctl reload caddy
sudo journalctl -u caddy -f
```

---

## 5. 本專案建議的 Caddyfile（基本版）

假設你的網域是 `app.example.com`。

檔案路徑：`/etc/caddy/Caddyfile`

```caddy
app.example.com {
    encode zstd gzip

    # API 反向代理到 Spring Boot
    handle /api/* {
        reverse_proxy 127.0.0.1:8080
    }

    # 其餘流量轉到 Web Dashboard
    handle {
        reverse_proxy 127.0.0.1:3000
    }

    log {
        output file /var/log/caddy/app-example-com.log
        format console
    }
}
```

驗證與套用：

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

---

## 6. 加入 Monitor 端點保護（IP Allowlist）

你的架構中最敏感的端點通常是：

1. `/api/broadcast-trade`
2. `/api/heartbeat`

建議保護方式：`Caddy IP allowlist + App 層 Monitor API Key`

### 6.1 Caddyfile 範例（含 allowlist）

將 `203.0.113.10` 換成你的本地出口 IP（或固定 VPN 出口 IP）。

```caddy
app.example.com {
    encode zstd gzip

    # ===== Monitor 專用端點（先擋 IP）=====
    @monitor_paths {
        path /api/broadcast-trade /api/heartbeat
    }

    @monitor_allowed {
        remote_ip 203.0.113.10
    }

    handle @monitor_paths {
        handle @monitor_allowed {
            reverse_proxy 127.0.0.1:8080
        }
        respond "Forbidden" 403
    }

    # ===== 其餘 API =====
    handle /api/* {
        reverse_proxy 127.0.0.1:8080
    }

    # ===== Web Dashboard =====
    handle {
        reverse_proxy 127.0.0.1:3000
    }

    log {
        output file /var/log/caddy/app-example-com.log
        format console
    }
}
```

注意：

1. 這只是第一層保護，Java 端仍要保留 `Monitor API Key`
2. 若你本地 IP 常變，建議改用 `Tailscale / WireGuard`（比純 IP allowlist 穩定）

---

## 7. 與本專案整合建議（重要）

### 7.1 Spring Boot / Dashboard 只綁本機

如果你用 Docker Compose，建議把 port 綁到 `127.0.0.1`：

```yaml
services:
  trading-api:
    ports:
      - "127.0.0.1:8080:8080"

  web-dashboard:
    ports:
      - "127.0.0.1:3000:3000"
```

這樣就算 Caddy 掛掉或設定錯，也不會直接把 `8080/3000` 暴露到公網。

### 7.2 CORS 設定

若前後端都走同網域（Caddy 分流），CORS 壓力會大幅下降。

仍建議後端將 production CORS 設為你的正式網域，而不是只留 localhost。

### 7.3 Monitor 呼叫的 API Base URL

本地 `discord-monitor` 的 `api.base_url` 應改為你的 Caddy 網域，例如：

```yaml
api:
  base_url: https://app.example.com
```

並確保 monitor 仍帶 `X-Api-Key`。

---

## 8. 上線驗證清單（Caddy 層）

1. `https://app.example.com` 可打開前端
2. `https://app.example.com/api/health` 回傳 `200`
3. 從非 allowlist IP 呼叫 `/api/heartbeat` 回 `403`
4. 從 allowlist IP + 正確 `X-Api-Key` 呼叫 `/api/heartbeat` 回 `200`
5. `http://app.example.com` 自動轉 `https://`
6. `8080/3000` 從外網不可直接連線

建議測試：

```bash
curl -i https://app.example.com/api/health
curl -i -X POST https://app.example.com/api/heartbeat -H "Content-Type: application/json" -d '{"status":"connected"}'
```

---

## 9. 常見問題與排查

### Q1. 憑證申請失敗

常見原因：

1. DNS 還沒生效
2. `80/443` 沒開
3. 網域指錯 IP

檢查：

```bash
dig app.example.com +short
sudo journalctl -u caddy -f
```

### Q2. 前端有畫面但 API 404 / 502

檢查：

1. Spring Boot 是否真的在 `127.0.0.1:8080`
2. Caddyfile `/api/*` 是否正確分流
3. 後端服務是否存活

```bash
curl -i http://127.0.0.1:8080/api/health
```

### Q3. Monitor 被擋掉（403）

代表 Caddy 的 IP allowlist 生效，但來源 IP 不在清單內。

處理方式：

1. 確認本地出口 IP 是否變動
2. 更新 Caddy `remote_ip`
3. 或改用固定出口 VPN / Tailscale

---

## 10. 安全建議（本專案版本）

1. `Caddy allowlist` 不能取代 `Monitor API Key`
2. `Monitor API Key` 不能取代 `IP allowlist`
3. 敏感端點（廣播交易）要雙層保護
4. 不要將 `8080/3000/5432` 直接開放到公網
5. 正式環境務必使用 HTTPS（不要讓 monitor 明文送 API key）

---

## 11. 建議的落地順序（最省事）

1. 先把 Java API + Dashboard 部署到 VM（內網 port）
2. 安裝 Caddy，先做基本版 `/api` 與 `/` 分流
3. 驗證 HTTPS 正常
4. 加上 monitor 端點 `IP allowlist`
5. 本地 monitor 改連 `https://你的網域`
6. 最後再做 Java 端 endpoint 權限收斂（monitor/admin/user）

