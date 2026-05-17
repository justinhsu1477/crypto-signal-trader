"""Signal router — filters messages by channel, identifies signal type, forwards to API."""
from __future__ import annotations

import asyncio
import hashlib
import logging
import re
import time
from typing import Optional

from .api_client import ApiClient
from .config import DiscordConfig, ImageSignalConfig
from .image_utils import ImageFetchError, fetch_image
from .trade_action_detector import detector

logger = logging.getLogger(__name__)

# Pattern: embed title/description containing a quoted timestamp like "2026-03-03 00:48"
_QUOTED_TIMESTAMP_RE = re.compile(r"\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}")


#: 圖片 path 允許觸發的 action 白名單。
#:
#: 設計理由：陳哥/三馬哥等訊號源的 CLOSE / MOVE_SL / CANCEL 訊號 **都是純文字**
#: （✅止盈出局✅ / 止損上移至成本 / 限價取消），從未以圖片形式發布。但會員會在
#: 同一頻道貼「盈利反饋」圖（含「止盈」「平倉」「全部出局」等字眼），Gemini multimodal
#: 容易誤判為 CLOSE → 系統把實盤倉位提早平掉。
#:
#: 因此凡圖片解析結果不是 ENTRY，一律改 INFO + archive `IMAGE_NON_ENTRY_BLOCKED`，
#: 不送 broadcast-trade。這不會誤殺任何已知合法訊號，但完全擋住盈利圖誤平倉。
IMAGE_ALLOWED_ACTIONS = frozenset({"ENTRY"})


def _attach_custom_prompt_audit(payload: dict, source: dict | None) -> None:
    """把 per-source custom_prompt 的 audit 識別碼塞進 trade payload。

    payload 收到後送到 Java，Java 寫進 signals.custom_prompt_version / sha256
    做訊號層級的 audit chain。沒設 custom_prompt 的 source 不會送任何欄位。

    - effective_custom_prompt_version: 直接 echo gRPC 推下來的 version（snapshot）
    - effective_custom_prompt_sha256:  對「實際送進 Gemini 的 custom_prompt 原文」
                                       算 SHA-256 前 16 hex
    """
    if not source:
        return
    prompt = source.get("custom_prompt") or ""
    version = source.get("custom_prompt_version")
    if not prompt:
        # 來源沒設 custom_prompt — 不送任何 audit 欄位（signals 表留 null）
        return
    payload["effective_custom_prompt_version"] = int(version) if version else 0
    digest = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
    payload["effective_custom_prompt_sha256"] = digest[:16]

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
        # Layer 1: 任何頻道、任何訊息（連被 channel filter 擋下的都算）的最後 wall-clock 時間。
        # 用來偵測「CDP 完全沒在送訊息」的 capture stall 情境 — Python heartbeat 還活著，
        # 但已經很久沒從 Dispatcher 收到任何事件了。None 表示啟動到現在還沒收過任何訊息。
        self._last_message_time: Optional[float] = None
        self._processed_ids: set[str] = set()
        self._max_dedup_size = 10000
        self._content_hashes: set[str] = set()
        self._max_content_hash_size = 5000

    def seconds_since_any_message(self) -> Optional[float]:
        """Layer 1 watchdog: 距離上一次 CDP 送來任何訊息已過幾秒。

        - 啟動後從未收過任何訊息 → None（不要錯誤地報「stalled」，等收到第一筆再說）
        - 否則回傳 monotonic-ish 的秒數差（用 time.time() 即可，這裡不需要避免 NTP 抖動）

        Java 端拿到後若 > 14400（4 小時）就判定 capture: DEGRADED。
        """
        if self._last_message_time is None:
            return None
        return time.time() - self._last_message_time

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

    async def _handle_image_signal(
        self, msg: dict, source: dict,
    ) -> tuple[str | None, str | None, str | None]:
        """Image-first signal path. 解析圖片 → 過濾白名單 → 送 Java。

        失敗策略：任何步驟失敗（下載、parse、validate）都 silently skip 並 log。
        不應該 raise — 讓 handle_message 主流程不被一張壞圖搞掛。

        Returns:
            (parser_action, parser_skipped_reason, attachment_sha256) for audit archive.

            第三個欄位是 fetch_image() 計算出的真實 SHA-256（hex string）。
            Discord 的 attachment metadata 不含 sha256，必須走這條路才能讓
            discord_raw_messages.attachment_sha256 不為 NULL。

            Examples:
              - ("ENTRY", None, "abc123…") — parsed and forwarded successfully
              - ("COMPOUND", None, "abc123…") — compound action forwarded
              - ("INFO", None, "abc123…") — AI judged INFO, not forwarded
              - (None, "IMAGE_FETCH_FAILED", None) — fetch_image 失敗，沒算 sha
              - (None, "IMAGE_PARSE_FAILED", "abc123…") — fetch 成功但 parse 失敗，仍回傳 sha
              - (None, "FILTERED", "abc123…") — 白名單擋掉，仍回傳 sha
        """
        cfg = self.image_signal_config
        message_id = msg.get("id", "")
        text_content = msg.get("content", "")

        images = self._collect_image_urls(msg)
        if not images:
            return (None, "NO_IMAGE", None)

        # 取第一張圖（陳哥訊號通常一張，多張先簡單處理）
        first = images[0]
        url = first["url"]
        if not url:
            logger.warning("image path: empty URL in attachment, msg=%s", message_id)
            return (None, "IMAGE_FETCH_FAILED", None)

        # 下載
        try:
            image_bytes, mime, sha = await fetch_image(
                self.api_client._session,
                url,
                max_bytes=cfg.max_image_bytes,
            )
        except ImageFetchError as e:
            logger.warning("image path: fetch failed for msg=%s: %s", message_id, e)
            return (None, "IMAGE_FETCH_FAILED", None)
        except AttributeError:
            # api_client._session 可能在某些測試 mock 中不存在 — fallback
            logger.error("image path: api_client._session not available, cannot fetch image")
            return (None, "IMAGE_FETCH_FAILED", None)

        logger.info(
            "image path: fetched msg=%s sha=%s mime=%s size=%d",
            message_id, sha[:12], mime, len(image_bytes),
        )

        # AI 解析
        if not self.ai_parser:
            logger.warning("image path: ai_parser not configured, skipping image msg=%s", message_id)
            return (None, "IMAGE_PARSE_FAILED", sha)

        parsed = await self.ai_parser.parse_with_image(
            text_content=text_content,
            image_bytes=image_bytes,
            mime_type=mime,
            source_prompt=source.get("custom_prompt"),
            source_name=source.get("source_name") or source.get("display_name"),
        )
        if not parsed:
            logger.warning("image path: parse failed for msg=%s", message_id)
            return (None, "IMAGE_PARSE_FAILED", sha)

        # === P0a: Image action gate ===
        # 圖片 path 只允許 ENTRY 通過。CLOSE / MOVE_SL / CANCEL / COMPOUND 全擋
        # （理由見模組頂部 IMAGE_ALLOWED_ACTIONS docstring）。
        if isinstance(parsed, list):
            # Compound action（CLOSE + MOVE_SL）— 圖片絕不允許，因為盈利圖含「止盈 X%
            # 做成本保護」這類字眼會誤觸發兩個動作一起執行。
            blocked_actions = [item.get("action") for item in parsed if item.get("action")]
            logger.warning(
                "image path: compound action blocked by gate msg=%s actions=%s",
                message_id, blocked_actions,
            )
            return (None, "IMAGE_NON_ENTRY_BLOCKED", sha)

        action = parsed.get("action")

        # INFO action 直接 archive，不送下游（與 text path 行為一致）
        if action == "INFO":
            logger.info("image path: INFO action, not forwarding msg=%s", message_id)
            return ("INFO", None, sha)

        # 非 ENTRY → 一律改 INFO（archive 標 IMAGE_NON_ENTRY_BLOCKED 便於審計）
        if action not in IMAGE_ALLOWED_ACTIONS:
            logger.warning(
                "image path: %s action blocked by gate msg=%s "
                "(only ENTRY allowed from image source) text=%r",
                action, message_id, text_content[:120],
            )
            return (None, "IMAGE_NON_ENTRY_BLOCKED", sha)

        # BTC 白名單過濾（只對 ENTRY 做，CLOSE/MOVE_SL 已被前面 gate 擋）
        symbol = (parsed.get("symbol") or "").upper()
        if symbol not in cfg.allowed_symbols:
            logger.info(
                "image path: symbol %s not in allowed_symbols %s, skipping msg=%s",
                symbol, cfg.allowed_symbols, message_id,
            )
            return (None, "FILTERED", sha)

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
            return (parsed.get("action"), None, sha)

        # 真送
        prompt_version = self.ai_parser.prompt_version if hasattr(self.ai_parser, "prompt_version") else 0
        if prompt_version:
            parsed["prompt_version"] = prompt_version

        # per-source custom_prompt audit（signals 表 audit chain）
        _attach_custom_prompt_audit(parsed, source)

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
        return (parsed.get("action"), None, sha)

    async def handle_message(self, msg: dict) -> None:
        """Called by CdpClient for each MESSAGE_CREATE event.

        Args:
            msg: dict with keys: id, channel_id, guild_id, author_id,
                 author_name, content, timestamp, embeds
        """
        channel_id = msg.get("channel_id", "")
        guild_id = msg.get("guild_id", "")
        author_name = msg.get("author_name", "?")
        raw_message_id = msg.get("id", "")

        # Layer 1 (capture watchdog): 在所有 filter 之前先記錄「收到任何訊息」的時間，
        # 因為 capture stall 偵測的核心問題是「CDP 是不是還在送訊息」，被 channel / guild /
        # author filter 擋下的訊息也代表 CDP 還活著。
        self._last_message_time = time.time()

        # === MESSAGE_UPDATE 處理 ===
        # 真實場景：陳哥先發 placeholder「等等」→ 編輯成「BTC 60000 long SL 58000」。
        # Java L1 dedup 用 source.message_id 永久去重，原始 message_id 已處理過。
        # 對策：把 message_id 加 __edit-N 後綴（N = edit_revision 末 6 碼），讓 Java 視為新訊號。
        # 同訊號編輯多次只會走進 Python L1 dedup（不會重打 API）— 因為 edit_revision 不同所以
        # message_id 也不同 — 視為新訊號正確。
        # Archive 端會把 parser_action 加 EDIT: 前綴標記為編輯來源。
        is_edit = bool(msg.get("is_edit", False))
        if is_edit:
            edit_rev = msg.get("edit_revision", 0)
            short_rev = str(edit_rev)[-6:] if edit_rev else "000000"
            message_id = f"{raw_message_id}__edit-{short_rev}"
        else:
            message_id = raw_message_id

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
                image_source["custom_prompt"] = metadata.get("custom_prompt", "")

            logger.info(
                "image path triggered: #%s @%s msg=%s (text=%r)",
                channel_id[-6:], author_name, message_id, msg.get("content", "")[:60],
            )
            img_action, img_skip, img_sha = await self._handle_image_signal(msg, image_source)
            self._archive_message_async(
                msg,
                parser_action=img_action,
                parser_skipped_reason=img_skip,
                image_sha_override=img_sha,
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
            source["custom_prompt"] = metadata.get("custom_prompt", "")

        if self.ai_parser:
            # AI 模式：所有訊息都丟 AI 判斷，由 AI 決定 action
            logger.info(
                "#%s @%s: %s",
                channel_id[-6:],
                author_name,
                content[:120].replace("\n", " | "),
            )
            resolved_action = await self._forward_signal(content, source=source)
            # AI 失敗走 regex fallback 時 _forward_signal 回 None。明確標 skip_reason
            # 才能跟「壓根沒進 parser」區分（後者 parser_action / skip_reason 都 null）。
            skip_reason = None if resolved_action is not None else "AI_PARSE_FAILED"
            self._archive_message_async(msg, parser_action=resolved_action, parser_skipped_reason=skip_reason)
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
            source_for_prompt = source or {}
            parsed = await self.ai_parser.parse(
                content,
                source_prompt=source_for_prompt.get("custom_prompt"),
                source_name=source_for_prompt.get("source_name") or source_for_prompt.get("display_name"),
            )

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

                # per-source custom_prompt audit（signals 表 audit chain）
                _attach_custom_prompt_audit(parsed, source)

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

            # per-source custom_prompt audit — 每個 sub-action 都對應同一個 source 用的同一份 prompt
            _attach_custom_prompt_audit(trade_req, base_source)

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
        image_sha_override: str | None = None,
    ) -> None:
        """Fire-and-forget per-message archive POST.

        以 asyncio.create_task 包起來，不阻塞 handle_message 主流程。
        失敗由 api_client.send_discord_message 內吞下，這裡再加一層保險。

        Server 端用 message_id UPSERT，所以多次呼叫（例如先記 DEDUP 再記 BLACKLIST）安全。
        只記錄 server schema 需要的 snake_case 欄位。

        若 msg["is_edit"] 為 True：
          - parser_action 自動加 "EDIT:" 前綴（例如 ENTRY → EDIT:ENTRY），標記為編輯來源訊號。
          - message_id 用 __edit-N 後綴版本（與 handle_message 算出的 source.message_id 一致），
            這樣同一筆原始訊息的不同編輯版本會各自有 row（audit trail 完整）。

        Args:
            image_sha_override: 若提供，優先寫入 attachment_sha256（image path 計算出的真實
                sha256）。Discord 的 attachment metadata 並不會帶 sha256，所以走 image
                path 時必須靠 fetch_image() 回傳的值，否則 attachment_sha256 永遠 NULL。
        """
        try:
            attachments = msg.get("attachments", []) or []
            embed_images = msg.get("embed_images", []) or []

            # 優先使用 image path 計算出的真實 sha256；fallback 才去 dict 撈
            # （非 image-first 路徑，attachments[0].sha256 通常為 None — Discord 不給）
            attachment_sha256 = image_sha_override
            attachment_url = None
            for att in attachments:
                ctype = (att.get("content_type") or "").lower()
                if ctype.startswith("image/"):
                    if attachment_sha256 is None:
                        attachment_sha256 = att.get("sha256")
                    # Discord CDN URL — 給 Java mirror webhook embed.image.url 用
                    # 注意：URL 24h 後過期（Discord 強制簽章）
                    attachment_url = att.get("url") or None
                    break

            # MESSAGE_UPDATE 處理：
            # - parser_action 加 EDIT: 前綴
            # - message_id 帶上 __edit-N 後綴版本（與 send_trade 用的 source.message_id 一致）
            is_edit = bool(msg.get("is_edit", False))
            archive_action = parser_action
            archive_message_id = msg.get("id", "")
            if is_edit:
                if parser_action:
                    archive_action = f"EDIT:{parser_action}"
                edit_rev = msg.get("edit_revision", 0)
                short_rev = str(edit_rev)[-6:] if edit_rev else "000000"
                archive_message_id = f"{archive_message_id}__edit-{short_rev}"

            payload = {
                "message_id": archive_message_id,
                "channel_id": msg.get("channel_id", ""),
                "channel_name": msg.get("channel_name"),
                "guild_id": msg.get("guild_id"),
                "author_name": msg.get("author_name"),
                "message_timestamp": msg.get("timestamp"),
                "content": msg.get("content", ""),
                "has_attachments": bool(attachments),
                "attachment_count": len(attachments),
                "attachment_sha256": attachment_sha256,
                "attachment_url": attachment_url,
                "has_embed_images": bool(embed_images),
                "has_reference": bool(msg.get("has_reference", False)),
                "parser_action": archive_action,
                "parser_skipped_reason": parser_skipped_reason,
            }
            asyncio.create_task(self.api_client.send_discord_message(payload))
        except Exception as e:
            logger.debug("archive_message_async build payload failed (ignored): %s", e)
