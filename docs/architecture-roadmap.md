# Architecture Roadmap — Multi-User Trading

## Current Architecture (MVP)

```
Discord Signal
  → Python (AI Parser)
    → POST /api/broadcast-trade
      → BroadcastTradeService
        → Thread Pool (10 threads)
          → per-user: ThreadLocal(API Key) + executeSignal()
          → finally: ThreadLocal.remove()
```

### Key Decisions
- **Per-user API Key**: ThreadLocal on BinanceFuturesService, zero-invasive to existing methods
- **No API Key = No Trade**: user without Binance API Key is skipped (not fallback to global)
- **Single BinanceFuturesService bean**: all users share one Service instance, ThreadLocal isolates keys
- **RiskConfig is global**: all users share same risk parameters (riskPercent, maxDailyLoss, leverage)

### Limitations
| Item | Current | Impact |
|------|---------|--------|
| Thread Pool | new per-request, shutdown after | OK for <50 users, wasteful if signals are frequent |
| Risk Config | Global singleton | All users same leverage/risk — no customization |
| Balance Check | Per-user (via ThreadLocal key) | Correct — queries user's own Binance account |
| Trade Record | Global DB | All users' trades in same table, no userId isolation |
| Dedup | Global hash window | Cross-user dedup — may skip valid trades for different users |

---

## Phase 1: Shared Thread Pool (Low Effort)

**When**: Signal frequency > 5/min or users > 20

Change `BroadcastTradeService` from creating a new `ExecutorService` per request to using a shared `@Bean`:

```java
@Bean
public ExecutorService broadcastExecutor() {
    return Executors.newFixedThreadPool(10);
}
```

Inject instead of `Executors.newFixedThreadPool()` in `broadcastTrade()`. Remove `shutdown()` call.

**Effort**: ~10 lines changed, 1 file.

---

## Phase 2: RabbitMQ Async (Medium Effort)

**When**: Users > 50, or need retry/dead-letter for failed trades

```
Python → POST /api/broadcast-trade
  → BroadcastTradeService publishes to RabbitMQ
    → Queue: trade.execute.{userId}
      → Consumer: TradeExecutionConsumer
        → ThreadLocal(API Key) + executeSignal()
```

### Benefits
- **Retry with backoff**: RabbitMQ dead-letter + TTL for failed trades
- **Decoupled**: API response is instant ("queued"), execution is async
- **Scalable**: Multiple consumers can process in parallel across instances
- **Observability**: Queue depth = backlog visibility

### Files to Create/Modify
| File | Action |
|------|--------|
| `RabbitMQConfig.java` | Already exists (skeleton) — add exchange/queue/binding |
| `TradeExecutionConsumer.java` | New — listens to queue, calls executeSignalForBroadcast |
| `BroadcastTradeService.java` | Modify — publish to queue instead of Thread Pool |
| `SignalMessage.java` | Already exists — use as message payload |
| `application.yml` | Add `spring.rabbitmq.*` config |

### Config Toggle
```yaml
multi-user:
  enabled: true
  async-mode: rabbitmq   # "thread-pool" (default) or "rabbitmq"
```

**Effort**: ~200 lines new code, 2 new files, 2 modified.

---

## Phase 3: Factory Pattern — Per-User BinanceClient (High Effort)

**When**: Users need different leverage, risk params, or per-user WebSocket monitoring

Replace ThreadLocal approach with per-user client instances:

```java
public class BinanceClientFactory {

    private final ConcurrentHashMap<String, BinanceClient> clients = new ConcurrentHashMap<>();

    public BinanceClient getClient(String userId) {
        return clients.computeIfAbsent(userId, this::createClient);
    }

    private BinanceClient createClient(String userId) {
        BinanceKeys keys = userApiKeyService.getUserBinanceKeys(userId).orElseThrow();
        UserRiskConfig risk = userRiskConfigService.getConfig(userId);
        return new BinanceClient(keys, risk, httpClient);
    }

    public void evict(String userId) {
        clients.remove(userId);  // on API Key update or user disable
    }
}
```

### BinanceClient (extracted from BinanceFuturesService)

```java
public class BinanceClient {
    private final String apiKey;
    private final String secretKey;
    private final UserRiskConfig riskConfig;

    // All trading methods moved here
    public List<OrderResult> executeSignal(TradeSignal signal) { ... }
    public List<OrderResult> executeClose(TradeSignal signal) { ... }
    public String sendSignedPost(endpoint, params) { ... }
}
```

### New Entities Needed
| Entity | Purpose |
|--------|---------|
| `UserRiskConfig` | Per-user riskPercent, maxDailyLoss, leverage, allowed symbols |
| `UserTradeRecord` | Trades with userId foreign key |

### Migration Steps
1. Extract `BinanceClient` from `BinanceFuturesService` (pure HTTP + trading logic)
2. Keep `BinanceFuturesService` as facade for single-account mode (backward compat)
3. Create `BinanceClientFactory` with cache + eviction
4. Modify `BroadcastTradeService` to use factory
5. Add `UserRiskConfig` entity + dashboard UI
6. Migrate `trades` table to include `userId` column

**Effort**: ~500-800 lines refactor, 4+ new files, significant testing.

---

## Decision Matrix

| Criteria | Thread Pool (now) | Shared Pool (P1) | RabbitMQ (P2) | Factory (P3) |
|----------|:-:|:-:|:-:|:-:|
| Max Users | ~50 | ~50 | ~1000+ | ~1000+ |
| Per-user Risk | No | No | No | Yes |
| Retry/DLQ | No | No | Yes | Yes |
| Observability | Logs only | Logs only | Queue metrics | Queue + per-client |
| Effort | Done | 1 hour | 1-2 days | 3-5 days |
| Prerequisite | — | — | RabbitMQ infra | P2 recommended |

### Recommended Order
```
Now: Thread Pool + ThreadLocal (done)
  → P1: Shared Thread Pool (when signals get frequent)
    → P2: RabbitMQ (when users > 50 or need reliability)
      → P3: Factory Pattern (when users need custom risk/leverage)
```
