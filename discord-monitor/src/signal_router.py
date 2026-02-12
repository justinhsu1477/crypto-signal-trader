"""Signal router — filters messages by channel, identifies signal type, forwards to API."""
from __future__ import annotations

import logging

from .api_client import ApiClient
from .config import DiscordConfig

logger = logging.getLogger(__name__)

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
    ):
        self.channel_ids = set(discord_config.channel_ids) if discord_config.channel_ids else set()
        self.guild_ids = set(discord_config.guild_ids) if discord_config.guild_ids else None
        self.author_ids = set(discord_config.author_ids) if discord_config.author_ids else None
        self.api_client = api_client
        self.dry_run = dry_run
        self.ai_parser = ai_parser
        self._processed_ids: set[str] = set()
        self._max_dedup_size = 10000

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

        # Guild filter
        if self.guild_ids and guild_id not in self.guild_ids:
            return

        # Author filter
        if self.author_ids and msg.get("author_id", "") not in self.author_ids:
            return

        # Build content (message text + embeds combined)
        parts = []
        content_text = msg.get("content", "")
        if content_text:
            parts.append(content_text)
        for embed in msg.get("embeds", []):
            if embed.get("title"):
                parts.append(embed["title"])
            if embed.get("description"):
                parts.append(embed["description"])
        content = "\n".join(parts)

        if not content.strip():
            return

        # Dedup
        if message_id in self._processed_ids:
            return
        self._processed_ids.add(message_id)
        self._trim_dedup_set()

        if self.ai_parser:
            # AI 模式：所有訊息都丟 AI 判斷，由 AI 決定 action
            logger.info(
                "#%s @%s: %s",
                channel_id[-6:],
                author_name,
                content[:120].replace("\n", " | "),
            )
            await self._forward_signal(content)
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
            await self._forward_signal(content)

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

    async def _forward_signal(self, content: str) -> None:
        """Forward the signal to the Spring Boot API.

        Strategy: AI-first, regex-fallback.
        1. If AI parser is available, try AI parsing first
        2. On AI success → send structured JSON to /api/execute-trade
        3. On AI failure → fallback to raw text /api/execute-signal (regex)
        """
        # === AI parsing (primary) ===
        if self.ai_parser:
            parsed = await self.ai_parser.parse(content)
            if parsed and parsed.get("action") not in ("INFO", "UNKNOWN"):
                logger.info("AI parsed → %s %s %s", parsed.get("action"), parsed.get("symbol"), parsed.get("side", ""))
                result = await self.api_client.send_trade(parsed, dry_run=self.dry_run)
                if result.success:
                    logger.info("AI trade OK: %s", result.summary[:200])
                else:
                    logger.warning("AI trade FAILED (HTTP %d): %s", result.status_code, result.error)
                return
            elif parsed and parsed.get("action") == "INFO":
                logger.debug("AI identified as INFO, skipping")
                return
            else:
                logger.warning("AI parsing failed, falling back to regex")

        # === Regex parsing (fallback) ===
        result = await self.api_client.send_signal(content, dry_run=self.dry_run)

        if result.success:
            logger.info("Regex API response OK: %s", result.summary[:200])
        else:
            logger.warning(
                "Regex API response FAILED (HTTP %d): %s",
                result.status_code,
                result.error,
            )

    def _trim_dedup_set(self) -> None:
        """Prevent unbounded memory growth of dedup set."""
        if len(self._processed_ids) > self._max_dedup_size:
            to_keep = list(self._processed_ids)[self._max_dedup_size // 2:]
            self._processed_ids = set(to_keep)
