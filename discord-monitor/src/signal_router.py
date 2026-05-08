"""Signal router — filters messages by channel, identifies signal type, forwards to API."""
from __future__ import annotations

import asyncio
import hashlib
import logging
import re
import time

from .api_client import ApiClient
from .config import DiscordConfig, ImageSignalConfig
from .trade_action_detector import detector

logger = logging.getLogger(__name__)

# Pattern: embed title/description containing a quoted timestamp like "2026-03-03 00:48"
_QUOTED_TIMESTAMP_RE = re.compile(r"\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}")

# Signal type identification by emoji prefix (only used when AI is disabled)
SIGNAL_TYPES = {
    "\U0001f4e2": "ENTRY",     # 📢 交易訊號發布
    "\u26a0\ufe0f": "CANCEL",  # ⚠️ 掛單取消
    "\U0001f680": "INFO",      # 🚀 訊號成交
    "\U0001f6d1": "INFO",      # 🛑 止損出場
    "\U0001f4b0": "INFO",      # 💰 盈虧更新
}

# Keyword-based signal types (no emoji prefix, matched by content)
KEYWORD_SIGNALS = {
    "TP-SL 修改": "MODIFY",    # 訂單/TP-SL 修改
    "TP-SL修改": "MODIFY",     # 無空格變體
}

# Types that should be forwarded to the API
ACTIONABLE_TYPES = {"ENTRY", "CANCEL", "MODIFY"}


class SignalRouter:
    """Routes Discord messages through filtering, identification, and forwarding.

    Now receives pre-parsed message dicts directly from the CDP JS hook
    (no more raw WebSocket frame decoding needed).
    """

    def __init__(
        self,
        discord_config: DiscordConfig,
        api_client: ApiClient,
        dry_run: bool = False,
        ai_parser=None,
        signal_queue=None,
        image_signal_config: ImageSignalConfig | None = None,
    ):
        self.channel_ids = set(discord_config.channel_ids) if discord_config.channel_ids else set()
        self.guild_ids = set(discord_config.guild_ids) if discord_config.guild_ids else None
        self.author_ids = set(discord_config.author_ids) if discord_config.author_ids else None
        self.api_client = api_client
        self.dry_run = dry_run
        self.ai_parser = ai_parser
        self.signal_queue = signal_queue
        self.ignore_keywords = discord_config.ignore_keywords or []
        self.image_signal_config = image_signal_config or ImageSignalConfig()
        self.source_metadata_map: dict[str, dict] = {}
        self.channel_last_seen: dict[str, float] = {}
        self._processed_ids: set[str] = set()
        self._max_dedup_size = 10000
        self._content_hashes: set[str] = set()
        self._max_content_hash_size = 5000

    def _has_processable_image(self, msg: dict) -> bool:
        """訊息是否含可處理的圖片（attachment 或 embed image）。

        只看 image/* MIME，過濾掉影片、PDF 等。
        """
        for att in msg.get("attachments", []):
            ctype = (att.get("content_type") or "").lower()
            if ctype.startswith("image/"):
                return True
        if msg.get("embed_images"):
            return True
        return False

    async def handle_message(self, msg: dict) -> None:
        """Called by CdpClient for each MESSAGE_CREATE event.

        Args:
            msg: dict with keys: id, channel_id, guild_id, author_id,
                 author_name, content, timestamp, embeds
        """
        channel_id = msg.get("channel_id", "")
        guild_id = msg.get("guild_id", "")
        author_name = msg.get("author_name", "?")
        message_id = msg.get("id", "")

        # Channel whitelist filter
        if self.channel_ids and channel_id not in self.channel_ids:
            return

        # 記錄頻道活動時間（通過 channel filter 就算活躍，不管後續是否被過濾）
        self.channel_last_seen[channel_id] = time.time()

        # Guild filter
        if self.guild_ids and guild_id not in self.guild_ids:
            return

        # Author filter
        if self.author_ids and msg.get("author_id", "") not in self.author_ids:
            return

        # Build content — 多層防護避免引用的舊訊號混入
        #   Layer 1: has_reference（標準 Discord 回覆）
        #   Layer 2: has_snapshots（Discord 轉發訊息 / message_snapshots）
        #   Layer 3: embed 時間戳偵測（Bot/APP 引用舊訊息帶時間戳）
        #   Layer 4: embed content-hash 去重（embeds 內容與已處理訊號重複）
        is_reply = msg.get("has_reference", False)
        has_snapshots = msg.get("has_snapshots", False)
        skip_embeds = is_reply or has_snapshots

        parts = []
        content_text = msg.get("content", "")
        if content_text:
            parts.append(content_text)

        if skip_embeds:
            ref_preview = msg.get("referenced_content", "")[:80].replace("\n", " | ")
            reason = "reply" if is_reply else "forwarded(snapshots)"
            logger.info("⤵️ %s detected, ignoring embeds: %s", reason, ref_preview)
        else:
            for embed in msg.get("embeds", []):
                embed_text = "\n".join(
                    filter(None, [embed.get("title", ""), embed.get("description", "")])
                )
                if not embed_text:
                    continue

                # Layer 3: 偵測含歷史時間戳的 embed（Bot/APP 引用舊訊息）
                if _QUOTED_TIMESTAMP_RE.search(embed_text):
                    logger.info(
                        "⤵️ Embed contains quoted timestamp, skipping: %s",
                        embed_text[:80].replace("\n", " | "),
                    )
                    continue

                # Layer 4: content-hash 去重（embed 內容與已處理訊號重複）
                embed_hash = self._content_hash(embed_text)
                if embed_hash in self._content_hashes:
                    logger.info(
                        "⤵️ Embed content-hash duplicated (previously processed signal), skipping: %s",
                        embed_text[:60].replace("\n", " | "),
                    )
                    continue

                parts.append(embed_text)

        content = "\n".join(parts)

        if not content.strip():
            # 觀測層：偵測純圖訊息（content 空 + 有圖）
            if self._has_processable_image(msg):
                logger.warning(
                    "MISSED_IMAGE_ONLY channel=%s author=%s msg_id=%s attachments=%d embed_images=%d",
                    channel_id,
                    author_name,
                    message_id,
                    len(msg.get("attachments", [])),
                    len(msg.get("embed_images", [])),
                )
            return

        # 轉發所有訊息到分析師收集 API（fire-and-forget，不阻塞主流程）
        asyncio.create_task(self._append_analyst_message(author_name, channel_id, content))

        # 內容黑名單過濾（一對一指導等非交易訊號）
        if self.ignore_keywords:
            for kw in self.ignore_keywords:
                if kw in content:
                    logger.info(
                        "⛔ 黑名單關鍵字 [%s] 命中，跳過: %s",
                        kw,
                        content[:80].replace("\n", " | "),
                    )
                    return

        # Dedup
        if message_id in self._processed_ids:
            return
        self._processed_ids.add(message_id)
        self._trim_dedup_set()

        # 建構訊號來源元資料
        source = {
            "platform": "DISCORD",
            "channel_id": channel_id,
            "guild_id": guild_id,
            "author_name": author_name,
            "message_id": message_id,
        }

        # 豐富 source：從 gRPC 推送的 per-source metadata 補充資訊
        metadata = self.source_metadata_map.get(channel_id)
        if metadata:
            source["source_name"] = metadata.get("name", "")
            source["display_name"] = metadata.get("display_name", "")
            source["trade_mode"] = metadata.get("trade_mode", "AUTO")
            source["risk_multiplier"] = metadata.get("risk_multiplier", 1.0)

        if self.ai_parser:
            # AI 模式：所有訊息都丟 AI 判斷，由 AI 決定 action
            logger.info(
                "#%s @%s: %s",
                channel_id[-6:],
                author_name,
                content[:120].replace("\n", " | "),
            )
            await self._forward_signal(content, source=source)
        else:
            # Regex fallback 模式：保留 emoji/keyword 過濾，避免閒聊打 API
            signal_type = self._identify_type(content)
            logger.info(
                "[%s] #%s @%s: %s",
                signal_type,
                channel_id[-6:],
                author_name,
                content[:120].replace("\n", " | "),
            )
            if signal_type not in ACTIONABLE_TYPES:
                logger.debug("Signal type %s is info-only, skipping API call", signal_type)
                return
            await self._forward_signal(content, source=source)

    def _identify_type(self, content: str) -> str:
        """Identify signal type by emoji prefix or keyword."""
        stripped = content.strip()
        for emoji, sig_type in SIGNAL_TYPES.items():
            if stripped.startswith(emoji):
                return sig_type
        # Fallback: keyword-based matching (no emoji prefix)
        for keyword, sig_type in KEYWORD_SIGNALS.items():
            if keyword in stripped:
                return sig_type
        return "UNKNOWN"

    async def _forward_signal(self, content: str, source: dict | None = None) -> None:
        """Forward the signal to the Spring Boot API.

        Strategy: AI-first, regex-fallback.
        1. If AI parser is available, try AI parsing first (Agent 1: Signal Parser)
        2. On AI success → send structured JSON to /api/execute-trade
        3. On AI failure → fallback to raw text /api/execute-signal (regex)

        Multi-Agent Extension Point (future):
        After Agent 1 parses successfully, additional agents can be inserted:
          - Agent 2 (Risk Assessment): evaluate win probability, news context
          - Agent 3 (Arbitration): when multiple agents disagree, vote on final decision
        Example:
          parsed = await self.ai_parser.parse(content)       # Agent 1
          risk_ok = await self.risk_agent.evaluate(parsed)   # Agent 2 (future)
          if not risk_ok: return                              # Rejected by risk agent
          await self.api_client.send_trade(parsed, ...)      # Execute
        """
        # === Agent 1: AI Signal Parser (primary) ===
        if self.ai_parser:
            parsed = await self.ai_parser.parse(content)

            # === TradeActionDetector 補充判斷 ===
            # 當 AI 無法判斷（返回 None）或判為 INFO 時，嘗試 TradeActionDetector 補助
            if parsed is None:
                # AI Parser 完全失敗，嘗試 TradeActionDetector
                logger.debug("AI Parser 返回 None，嘗試 TradeActionDetector 補救")
                if detector.detect_close(content):
                    parsed = {
                        'action': 'CLOSE',
                        'symbol': 'BTCUSDT',
                        '_detector_refinement': 'NONE→CLOSE by TradeActionDetector'
                    }
                    logger.info("TradeActionDetector 補救: NONE → CLOSE (content: %s)", content[:80])
                else:
                    # TradeActionDetector 也無法判斷，進入 regex fallback
                    logger.debug("AI Parser 失敗，TradeActionDetector 也無補救，進入 regex fallback")
                    parsed = None

            elif parsed.get("action") == "INFO":
                # AI 判為 INFO，嘗試 TradeActionDetector 補助
                if detector.detect_close(content):
                    logger.info("TradeActionDetector 補救: INFO → CLOSE (content: %s)", content[:80])
                    parsed['action'] = 'CLOSE'
                    parsed['_detector_refinement'] = 'INFO→CLOSE by TradeActionDetector'
                    # 確保有 symbol（預設為 BTCUSDT）
                    if not parsed.get('symbol'):
                        parsed['symbol'] = 'BTCUSDT'
                else:
                    logger.debug("AI identified as INFO, TradeActionDetector 無補救, skipping")
                    return

            # 如果有有效的 action（不是 INFO 也不是 UNKNOWN）
            if parsed and parsed.get("action") not in ("INFO", "UNKNOWN"):
                logger.info("AI parsed → %s %s %s", parsed.get("action"), parsed.get("symbol"), parsed.get("side", ""))
                # Record content hash for embed dedup (Layer 4 protection)
                self._record_content_hash(content)

                # TODO: Insert Agent 2 (risk assessment) here in future
                # TODO: Insert Agent 3 (arbitration) here in future

                # 附加 prompt 版本號（供後端交易追溯）
                if self.ai_parser and self.ai_parser.prompt_version:
                    parsed["prompt_version"] = self.ai_parser.prompt_version

                result = await self.api_client.send_trade(parsed, dry_run=self.dry_run, source=source)
                if result.success:
                    logger.info("AI trade OK: %s", result.summary[:200])
                else:
                    logger.warning("AI trade FAILED (HTTP %d): %s", result.status_code, result.error)
                    # Queue on server-down failures (status_code=0 = 全部 retry 失敗)
                    # 4xx 是 client 錯誤不存（payload 問題，重播也會失敗）
                    if self.signal_queue and result.status_code == 0:
                        self.signal_queue.enqueue(
                            call_type="send_trade",
                            payload=parsed,
                            source=source,
                            dry_run=self.dry_run,
                            original_content=content,
                        )
                return
            elif parsed and parsed.get("action") == "INFO":
                logger.debug("AI identified as INFO (TradeActionDetector 未補救), skipping")
                return
            else:
                logger.warning("AI parsing failed, falling back to regex")

        # === Regex parsing (fallback) ===
        result = await self.api_client.send_signal(content, dry_run=self.dry_run, source=source)

        if result.success:
            logger.info("Regex API response OK: %s", result.summary[:200])
        else:
            logger.warning(
                "Regex API response FAILED (HTTP %d): %s",
                result.status_code,
                result.error,
            )
            # Queue on server-down failures (status_code=0 = 全部 retry 失敗)
            if self.signal_queue and result.status_code == 0:
                self.signal_queue.enqueue(
                    call_type="send_signal",
                    payload={"message": content},
                    source=source,
                    dry_run=self.dry_run,
                    original_content=content,
                )

    @staticmethod
    def _content_hash(text: str) -> str:
        """Generate a short hash of content for dedup (strip whitespace for robustness)."""
        normalized = re.sub(r"\s+", "", text)
        return hashlib.md5(normalized.encode()).hexdigest()[:12]

    def _record_content_hash(self, content: str) -> None:
        """Record the content hash of a successfully processed signal."""
        h = self._content_hash(content)
        self._content_hashes.add(h)
        self._trim_content_hashes()

    def _trim_dedup_set(self) -> None:
        """Prevent unbounded memory growth of dedup set."""
        if len(self._processed_ids) > self._max_dedup_size:
            to_keep = list(self._processed_ids)[self._max_dedup_size // 2:]
            self._processed_ids = set(to_keep)

    def _trim_content_hashes(self) -> None:
        """Prevent unbounded memory growth of content hash set."""
        if len(self._content_hashes) > self._max_content_hash_size:
            to_keep = list(self._content_hashes)[self._max_content_hash_size // 2:]
            self._content_hashes = set(to_keep)

    async def _append_analyst_message(self, author_name: str, channel_id: str, content: str) -> None:
        """Fire-and-forget helper to forward messages to analyst collection API."""
        try:
            await self.api_client.append_analyst_message(
                analyst_name=author_name,
                channel_id=channel_id,
                content=content,
            )
        except Exception as e:
            logger.debug("Analyst message append error (ignored): %s", e)
