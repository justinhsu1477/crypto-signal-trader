"""Signal router — filters messages by channel, identifies signal type, forwards to API."""
from __future__ import annotations

import asyncio
import hashlib
import logging
import re
import time

from .api_client import ApiClient
from .config import DiscordConfig, ImageSignalConfig
from .image_utils import ImageFetchError, fetch_image
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

    def _collect_image_urls(self, msg: dict) -> list[dict]:
        """從 attachments + embed_images 蒐集所有可處理的圖片 URL。

        Returns:
            list of {"url": str, "filename": str, "content_type": str, "source": "attachment"|"embed"}
        """
        result = []
        for att in msg.get("attachments", []):
            ctype = (att.get("content_type") or "").lower()
            if not ctype.startswith("image/"):
                continue
            result.append({
                "url": att.get("url", ""),
                "filename": att.get("filename", ""),
                "content_type": ctype,
                "source": "attachment",
            })
        for emb in msg.get("embed_images", []):
            url = emb.get("url", "")
            if url:
                result.append({
                    "url": url,
                    "filename": "",
                    "content_type": "image/*",
                    "source": "embed",
                })
        return result

    async def _handle_image_signal(self, msg: dict, source: dict) -> tuple[str | None, str | None]:
        """Image-first signal path. 解析圖片 → 過濾白名單 → 送 Java。

        失敗策略：任何步驟失敗（下載、parse、validate）都 silently skip 並 log。
        不應該 raise — 讓 handle_message 主流程不被一張壞圖搞掛。

        Returns:
            (parser_action, parser_skipped_reason) for audit archive. Examples:
              - ("ENTRY", None) — parsed and forwarded successfully
              - ("COMPOUND", None) — compound action forwarded
              - ("INFO", None) — AI judged INFO, not forwarded
              - (None, "IMAGE_FETCH_FAILED") / "IMAGE_PARSE_FAILED" / "FILTERED" / "NO_IMAGE"
        """
        cfg = self.image_signal_config
        message_id = msg.get("id", "")
        text_content = msg.get("content", "")

        images = self._collect_image_urls(msg)
        if not images:
            return (None, "NO_IMAGE")

        # 取第一張圖（陳哥訊號通常一張，多張先簡單處理）
        first = images[0]
        url = first["url"]
        if not url:
            logger.warning("image path: empty URL in attachment, msg=%s", message_id)
            return (None, "IMAGE_FETCH_FAILED")

        # 下載
        try:
            image_bytes, mime, sha = await fetch_image(
                self.api_client._session,
                url,
                max_bytes=cfg.max_image_bytes,
            )
        except ImageFetchError as e:
            logger.warning("image path: fetch failed for msg=%s: %s", message_id, e)
            return (None, "IMAGE_FETCH_FAILED")
        except AttributeError:
            # api_client._session 可能在某些測試 mock 中不存在 — fallback
            logger.error("image path: api_client._session not available, cannot fetch image")
            return (None, "IMAGE_FETCH_FAILED")

        logger.info(
            "image path: fetched msg=%s sha=%s mime=%s size=%d",
            message_id, sha[:12], mime, len(image_bytes),
        )

        # AI 解析
        if not self.ai_parser:
            logger.warning("image path: ai_parser not configured, skipping image msg=%s", message_id)
            return (None, "IMAGE_PARSE_FAILED")

        parsed = await self.ai_parser.parse_with_image(
            text_content=text_content,
            image_bytes=image_bytes,
            mime_type=mime,
        )
        if not parsed:
            logger.warning("image path: parse failed for msg=%s", message_id)
            return (None, "IMAGE_PARSE_FAILED")

        # Compound action（list）— 圖片回 [CLOSE, MOVE_SL]，與文字流相同處理
        # 注意：白名單過濾在 _forward_compound 之前直接做（取第一筆 symbol 即可，
        # 因為 _is_compound_close_movesl 保證同 symbol），確保非 BTC 不會偷渡
        if isinstance(parsed, list):
            logger.info(
                "Image compound action: %d sub-actions for msg=%s",
                len(parsed), message_id,
            )
            # 白名單檢查（list 中所有子動作 symbol 都一致，取第一筆即可）
            compound_symbol = (parsed[0].get("symbol") or "").upper() if parsed else ""
            if compound_symbol not in self.image_signal_config.allowed_symbols:
                logger.info(
                    "image path: compound symbol %s not in allowed_symbols %s, skipping msg=%s",
                    compound_symbol, self.image_signal_config.allowed_symbols, message_id,
                )
                return (None, "FILTERED")
            await self._forward_compound(parsed, source)
            return ("COMPOUND", None)

        # BTC 白名單過濾
        symbol = (parsed.get("symbol") or "").upper()
        if symbol not in cfg.allowed_symbols:
            logger.info(
                "image path: symbol %s not in allowed_symbols %s, skipping msg=%s",
                symbol, cfg.allowed_symbols, message_id,
            )
            return (None, "FILTERED")

        # INFO action 不送下游（與 text path 行為一致）
        if parsed.get("action") == "INFO":
            logger.info("image path: INFO action, not forwarding msg=%s", message_id)
            return ("INFO", None)

        # 附件 metadata 加入 source
        enriched_source = dict(source)
        enriched_source["attachment"] = {
            "url": url,
            "filename": first.get("filename", ""),
            "content_type": mime,
            "sha256": sha,
            "size": len(image_bytes),
        }

        # Dry-run 模式只 log 不送
        if cfg.dry_run:
            logger.info(
                "[IMAGE DRY RUN] would send: action=%s symbol=%s side=%s entry=%s SL=%s",
                parsed.get("action"), parsed.get("symbol"), parsed.get("side"),
                parsed.get("entry_price"), parsed.get("stop_loss"),
            )
            return (parsed.get("action"), None)

        # 真送
        prompt_version = self.ai_parser.prompt_version if hasattr(self.ai_parser, "prompt_version") else 0
        if prompt_version:
            parsed["prompt_version"] = prompt_version

        try:
            result = await self.api_client.send_trade(
                trade_request=parsed,
                dry_run=False,
                source=enriched_source,
            )
            logger.info(
                "image path: send_trade result msg=%s status=%s success=%s",
                message_id, result.status_code, result.success,
            )
        except Exception as e:
            logger.exception("image path: send_trade error msg=%s: %s", message_id, e)
        return (parsed.get("action"), None)

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

        # === Image-first branch (新增 — 文字流不受影響)===
        # 條件：feature flag on AND 訊息含可處理圖片
        # 通過後直接走 image path 並 return，不進入下方文字流
        if self.image_signal_config.enabled and self._has_processable_image(msg):
            # message_id dedup 也在這裡先擋一次（避免同訊息走兩次）
            if message_id in self._processed_ids:
                # archive 即使是 dedup 也記，便於 audit
                self._archive_message_async(
                    msg, parser_action=None, parser_skipped_reason="DEDUP_MESSAGE_ID",
                )
                return
            self._processed_ids.add(message_id)
            self._trim_dedup_set()

            # 建構 source（與文字流相同欄位）
            image_source = {
                "platform": "DISCORD",
                "channel_id": channel_id,
                "guild_id": guild_id,
                "author_name": author_name,
                "message_id": message_id,
            }
            metadata = self.source_metadata_map.get(channel_id)
            if metadata:
                image_source["source_name"] = metadata.get("name", "")
                image_source["display_name"] = metadata.get("display_name", "")
                image_source["trade_mode"] = metadata.get("trade_mode", "AUTO")
                image_source["risk_multiplier"] = metadata.get("risk_multiplier", 1.0)

            logger.info(
                "image path triggered: #%s @%s msg=%s (text=%r)",
                channel_id[-6:], author_name, message_id, msg.get("content", "")[:60],
            )
            img_action, img_skip = await self._handle_image_signal(msg, image_source)
            self._archive_message_async(
                msg, parser_action=img_action, parser_skipped_reason=img_skip,
            )
            return  # 不進入文字流
        # === Image-first branch end ===

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
            # archive：空訊息也記，便於 audit
            self._archive_message_async(msg, parser_action=None, parser_skipped_reason="EMPTY")
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
                    self._archive_message_async(
                        msg, parser_action=None, parser_skipped_reason="BLACKLIST",
                    )
                    return

        # Dedup
        if message_id in self._processed_ids:
            self._archive_message_async(
                msg, parser_action=None, parser_skipped_reason="DEDUP_MESSAGE_ID",
            )
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
            resolved_action = await self._forward_signal(content, source=source)
            self._archive_message_async(msg, parser_action=resolved_action, parser_skipped_reason=None)
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
                self._archive_message_async(
                    msg, parser_action=signal_type, parser_skipped_reason="FILTERED",
                )
                return
            await self._forward_signal(content, source=source)
            self._archive_message_async(msg, parser_action=signal_type, parser_skipped_reason=None)

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

    async def _forward_signal(self, content: str, source: dict | None = None) -> str | None:
        """Forward the signal to the Spring Boot API.

        Strategy: AI-first, regex-fallback.
        1. If AI parser is available, try AI parsing first (Agent 1: Signal Parser)
        2. On AI success → send structured JSON to /api/execute-trade
        3. On AI failure → fallback to raw text /api/execute-signal (regex)

        Returns:
            The resolved parser action string (e.g. "ENTRY"/"CLOSE"/"MOVE_SL"/"CANCEL"/"INFO"/
            "COMPOUND") used by the caller for audit archive. None if pre-AI fallback.

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

            # 複合動作（list）— 例如「止盈50%做成本保護」回 [CLOSE, MOVE_SL]
            # 各子動作獨立送 API，用 suffixed message_id 避開 Java L1 dedup
            if isinstance(parsed, list):
                logger.info(
                    "Compound action: %d sub-actions for msg=%s",
                    len(parsed), source.get("message_id") if source else None,
                )
                # 與單動作路徑一致：記錄 content hash，避免引用/轉發再觸發
                self._record_content_hash(content)
                await self._forward_compound(parsed, source or {})
                return "COMPOUND"

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
                    return "INFO"

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
                return parsed.get("action")
            elif parsed and parsed.get("action") == "INFO":
                logger.debug("AI identified as INFO (TradeActionDetector 未補救), skipping")
                return "INFO"
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
        return None

    async def _forward_compound(self, actions: list, base_source: dict) -> None:
        """執行複合動作（list of trade_requests）。

        每個 sub-action 用 suffixed message_id（base_msg__close / base_msg__move_sl）
        避開 Java 端 L1（source_message_id）永久 dedup。

        順序由 ai_parser.parse() 排好（CLOSE 先，MOVE_SL 後）。
        """
        base_msg_id = base_source.get("message_id", "")
        prompt_version = getattr(self.ai_parser, "prompt_version", 0)

        for action in actions:
            sub_source = dict(base_source)
            action_name = (action.get("action") or "unknown").lower()
            sub_source["message_id"] = f"{base_msg_id}__{action_name}"

            # 帶上 prompt_version（與既有 _forward_signal 行為一致）
            trade_req = dict(action)
            if prompt_version:
                trade_req["prompt_version"] = prompt_version

            try:
                result = await self.api_client.send_trade(
                    trade_request=trade_req,
                    dry_run=self.dry_run,
                    source=sub_source,
                )
                logger.info(
                    "Compound sub-action sent: %s msg=%s status=%s success=%s",
                    action_name, sub_source["message_id"],
                    result.status_code, result.success,
                )
            except Exception as e:
                # 一個 sub-action 失敗不該影響下一個（隔離）
                logger.exception(
                    "Compound sub-action failed: %s msg=%s err=%s",
                    action_name, sub_source["message_id"], e,
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

    def _archive_message_async(
        self,
        msg: dict,
        parser_action: str | None = None,
        parser_skipped_reason: str | None = None,
    ) -> None:
        """Fire-and-forget per-message archive POST.

        以 asyncio.create_task 包起來，不阻塞 handle_message 主流程。
        失敗由 api_client.send_discord_message 內吞下，這裡再加一層保險。

        Server 端用 message_id UPSERT，所以多次呼叫（例如先記 DEDUP 再記 BLACKLIST）安全。
        只記錄 server schema 需要的 snake_case 欄位。
        """
        try:
            attachments = msg.get("attachments", []) or []
            embed_images = msg.get("embed_images", []) or []
            # 第一張圖的 sha256（若 attachment 已含 sha256；否則為 None）
            first_image_sha = None
            for att in attachments:
                ctype = (att.get("content_type") or "").lower()
                if ctype.startswith("image/"):
                    first_image_sha = att.get("sha256")
                    break

            payload = {
                "message_id": msg.get("id", ""),
                "channel_id": msg.get("channel_id", ""),
                "channel_name": msg.get("channel_name"),
                "guild_id": msg.get("guild_id"),
                "author_name": msg.get("author_name"),
                "message_timestamp": msg.get("timestamp"),
                "content": msg.get("content", ""),
                "has_attachments": bool(attachments),
                "attachment_count": len(attachments),
                "attachment_sha256": first_image_sha,
                "has_embed_images": bool(embed_images),
                "has_reference": bool(msg.get("has_reference", False)),
                "parser_action": parser_action,
                "parser_skipped_reason": parser_skipped_reason,
            }
            asyncio.create_task(self.api_client.send_discord_message(payload))
        except Exception as e:
            logger.debug("archive_message_async build payload failed (ignored): %s", e)
