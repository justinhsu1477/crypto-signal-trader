# Crypto Signal Trader

[![繁體中文](https://img.shields.io/badge/lang-繁體中文-red)](README.md)
[![English](https://img.shields.io/badge/lang-English-blue)](README.en.md)

[![CI](https://github.com/justinhsu1477/crypto-signal-trader/actions/workflows/ci.yml/badge.svg)](https://github.com/justinhsu1477/crypto-signal-trader/actions/workflows/ci.yml)
![Tests](https://img.shields.io/badge/tests-2757%20passed-brightgreen)
![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-6DB33F)
![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB)
![Next.js](https://img.shields.io/badge/Next.js-14-black)

> Discord signals → AI parsing → multi-user Binance Futures auto-trading

Automatically converts messages from Discord signal channels into Binance Futures orders. **Multi-user SaaS support**: signal broadcasting, per-user risk controls, USDT subscription billing, and an admin Discord chatbot.

- 📖 [Engineering Case Study](docs/CASE_STUDY.md) — design decisions, 5 real failure cases, testing strategy, deployment
- 🛡️ [SECURITY.md](SECURITY.md) — threat model across 8 attack surfaces + responsible disclosure

---

## 🎯 Why This Exists

Chinese-speaking crypto copy-trading lives almost entirely inside Discord signal groups. But **manual copy-trading** has 4 structural pain points:

| Pain | What actually happens |
|------|----------------------|
| Reaction time | Group leader posts "BTC long 78000" → open Binance → size position → submit. At least 30 sec. Market is now 79000. |
| Missed overnight signals | Leader posts on US west-coast time, Asian users sleep through it, signal expired by morning |
| Compound actions done wrong | "Close BTC, enter ETH" — one sentence, two actions, beginners only do one |
| Cross-group conflicts | Following 5 groups at once — conflicting signals require sub-second judgment |

And existing auto-trading tools don't fit Chinese signal groups:

- **3Commas / Cornix** — English-first, weak Chinese LLM parsing, can't recognize group-specific slang like "進多", "平掉", "TP-SL 修改"
- **Discord bot copytrading** — requires the signal source's group owner to **actively install** a bot — owners usually refuse outright
- **Telegram bots / MetaMask snap** — wrong UX surface for Chinese users, and decoupled from the Discord signal source itself

→ This system uses **the user's own Discord desktop client + CDP injection + Gemini Chinese parsing + cloud execution**. Completely invisible to the signal source (the group owner never needs to know you're copy-trading), and Chinese slang / compound actions / image-based signals all work.

---

## 📊 Production Status

> Self-hosted production · solo-developed over 3 months (2026-02 → 05) · snapshot 2026-05-23

| Dimension | Scale |
|-----------|-------|
| **Uptime profile** | Production, daily-driven, real money on the line |
| **Active users** | **21** running Binance Futures with real capital |
| **Signal sources** | **151** Discord channels, parsing **5,800+** messages/month |
| **Signal throughput** | 5,834 raw messages → **1,305** broadcasts → **900** trades executed |
| **End-to-end latency** | < **3 sec** (signal posted → AI parsed → 13-gate risk → multi-user parallel execution) |
| **Codebase size** | Java **44.3k** + Python **4.4k** + TypeScript **29.7k** LoC |
| **Test coverage** | **2,428** Java unit + **329** Python + **30-case** AI eval (weekly cron, Mon 09:00) |
| **Dev cadence** | **596** commits · **51** Flyway migrations · **11** modules |
| **Infrastructure** | DigitalOcean 2GB VM (Singapore) + Neon Postgres serverless + Caddy + Cloudflare |

---

## How It Works

```
[Discord signal]
   ↓ CDP injection
[Python Monitor (local)] ── Gemini AI parsing (text / image / compound actions)
   ↓ HTTPS REST
[Spring Boot backend (cloud VM)] ── 13-gate risk + broadcast dispatch
   ↓
[Binance Futures API] ── per-user API key + per-user WebSocket
```

The upper half (CDP injection) must run next to local Chrome; the lower half runs on a cloud VM. gRPC streaming lets Java push config (channel whitelist, prompt versions, etc.) to local Python in real time.

---

## Highlights

### 1. Multimodal AI Signal Parsing
- **Text signals**: Gemini 2.5 Flash + custom SYSTEM_PROMPT (70+ few-shot examples)
- **Image signals**: Vision LLM extracts trade params from banner-style screenshots (some channels post signals as images to evade text scrapers)
- **Compound actions**: Recognizes patterns like "TP 50% + move SL to breakeven" → automatically issues CLOSE 50% + MOVE_SL to breakeven (with fee compensation)

### 2. Multi-User Broadcast Trading
- One signal → parallel dispatch to all subscribed users (broadcastExecutor 5–20 threads, aligned with DB pool size)
- **Per-user isolation**: API keys (AES-256-GCM encrypted) / risk params / notification channels are independent
- **Per-user WebSocket**: SL/TP fills trigger real-time PnL sync

### 3. Multi-Gate Risk Pipeline (13 gates / 4 stages)

Every signal entering [`BinanceFuturesService.executeSignalInternal`](src/main/java/com/trader/trading/service/BinanceFuturesService.java#L750) must pass through 4 stages totaling 13 gates. Any rejection halts the trade and writes an audit log.

**A. Entry eligibility** (3)
1. **Symbol whitelist** — rejects if symbol not in `allowedSymbols`
2. **Daily-loss circuit breaker** — halts all trading if realized loss ≥ `min(SOD × 80%, 2000 USDT)`
3. **Position state + DCA depth** — if position exists, only DCA allowed; max 3 layers; direction must match

**B. Signal dedup** (5 — each layer blocks a different attack surface)
4. **Binance open orders** — blocks duplicate LIMIT orders when a fill is still pending
5. **In-memory 5 min** — process-local `ConcurrentMap`, O(1) check against same-batch replays
6. **DB 5 min** — `trades.signal_hash + created_at` query, survives restarts
7. **Per-user 5 min** — `userId` included in hash, so **same signal can fire for different users** but not twice for the same one
8. **CANCEL 30 sec** — dedicated short window for CANCEL signals, blocks rapid user retries

**C. Signal sanity** (2)
9. **Stop-loss validation** — non-DCA entries must carry an SL with correct direction (LONG: SL < entry / SHORT: SL > entry)
10. **Price deviation** — rejects if entry differs from Binance markPrice by > 10% (protects against stale signals)

**D. Position sizing math** (3)
11. **Notional cap** — single-trade notional ≤ `min(balance × maxPositionPercent, 50k USDT)`
12. **Margin cap** — required margin ≤ 90% of available balance (liquidation safety)
13. **Min notional** — rejects if computed notional < 5 USDT (Binance min order)

Dedup logic lives in [`SignalDeduplicationService`](src/main/java/com/trader/trading/service/SignalDeduplicationService.java); sizing math and entry gates are in the main `BinanceFuturesService` flow. 8 knobs are env-tunable (`whitelist` / `daily-loss-percent` / `max-daily-loss-usdt` / `max-dca-per-symbol` / `dca-risk-multiplier` / `max-position-percent` / `max-position-usdt` / `dedup-enabled`); the rest are hardcoded safety floors.

### 4. Real-Time Admin Chatbot (Discord)
DM the bot directly:
- "Show all users' balances" → real-time Binance API query
- "This week's user PnL" → time-window aggregation (7d/30d/90d)
- "Today's signal status" → signals + broadcast outcomes (success/fail/skipped)

<p align="center">
  <img src="docs/images/chatbot.png" alt="Admin Discord Chatbot" width="700"/>
  <br/>
  <em>Admin DM: "Check all users' balances" → Bot instantly fetches each user's Binance USDT balance and lists them</em>
</p>

### 5. Full Audit Trail
- **DB**: `trades` / `signals` / `broadcast_logs` (with AI confidence + per-user result JSON) + image signals' `sha256` persisted, traceable from screenshot to trade
- **Prometheus**: `signal_image_total` / `signal_compound_total` / `chatbot_llm_calls_total` and related business metrics

---

## System Architecture

```mermaid
graph TD
    Discord["Discord Desktop<br/>(CDP injection)"]
    Monitor["Python Monitor<br/>Gemini AI parsing"]
    API["Spring Boot API<br/>13-gate risk + Broadcast"]
    RMQ["RabbitMQ<br/>DLQ + Retries"]
    Redis["Redis<br/>7-region cache"]
    Binance["Binance Futures"]
    WS["Per-User WebSocket"]
    Primary[("Neon Primary")]
    Replica[("Neon Replica")]
    Notify["Discord + LINE"]

    Discord -->|CDP| Monitor
    Monitor -->|HTTPS REST| API
    API <-->|gRPC Streaming| Monitor
    API -->|Signed orders| Binance
    Binance -->|Fill events| WS --> API
    API --> Primary
    API -->|readOnly| Replica
    API --> Redis
    API --> RMQ --> Notify
```

---

## Tech Stack

| Layer | Stack |
|---|---|
| Backend | Java 17 + Spring Boot 3.2.5 + Gradle |
| Frontend | Next.js 14 + shadcn/ui + i18n (en / zh-TW / zh-CN) |
| DB | Neon Postgres 16 (Primary + Read Replica) + Flyway migrations |
| Cache | Redis 7 (7 isolated regions + Graceful Degradation) |
| MQ | RabbitMQ 3 (DLQ + exponential backoff retries) |
| AI | Gemini 2.5 Flash (parsing + scoring + advisor + image) |
| Auth | JWT HttpOnly Cookie + LINE OAuth + RBAC + Email verification |
| Subscription | USDT TRC20 on-chain verification (TronGrid) |
| Comms | gRPC streaming + REST + AMQP + WebSocket |
| Deployment | Docker Compose + Caddy + Cloudflare + GitHub Actions CI/CD |
| Listener | Python 3.10+ + CDP (Chrome DevTools Protocol) |
| Testing | **2,428 Java + 329 Python + 30 AI eval** (JUnit 5 + Mockito + pytest + Gemini eval harness) |

---

## Quick Start

> For anyone evaluating a self-host — full env vars / VM provisioning / Caddy setup live in [`docs/雲端部署架構圖.md`](docs/雲端部署架構圖.md).

```bash
# Cloud backend
cp .env.example .env && docker compose -f docker-compose.cloud.yml up -d --build

# Local Python Monitor (CDP must run next to local Chrome)
cd discord-monitor && pip install -r requirements.txt && python -m src.main --config config.yml
```

---

## Modules

```
trading       Core trading (risk + ordering + broadcast + WebSocket + TradeContext)
chatbot       Admin Discord bot (function calling + AI assistant + conversation history)
notification  Discord/LINE multichannel notifications (async via RabbitMQ)
auth          JWT + LINE OAuth + Rate Limiting + Email verification
user          Account + encrypted API keys + trade settings + webhooks
subscription  USDT TRC20 subscription billing (on-chain verification)
dashboard     Performance analytics + broadcast logs + admin management
advisor       AI trading advisor (Gemini hourly analysis + signal confidence scoring)
papertrade    Paper-trading simulator (slippage / Sharpe / DD / auto-promote)
referral      Referral system (invite codes + commission tracking)
shared        Shared components (Config / DTO / Cache / Rate Limiter)
```

**Dependency rules**: no cycles, no reverse dependencies. See `CLAUDE.md`.

---

## Monitoring

- **Health probes** — `/api/health` liveness + `/api/health/deep` (DB / Binance / heartbeat / Discord bot)
- **Weekly AI eval cron** — Mon 09:00 runs the 30-case eval → emoji-tier digest pushed to admin Discord
- **Prometheus** — `/actuator/prometheus` exposes chatbot LLM / signal image+compound / trade outcomes
