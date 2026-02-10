"""Signal router — filters messages by channel, identifies signal type, forwards to API."""
from __future__ import annotations

import logging

from .api_client import ApiClient
from .config import DiscordConfig

logger = logging.getLogger(__name__)

# Signal type identification by emoji prefix
SIGNAL_TYPES = {
    "\U0001f4e2": "ENTRY",     # 📢 交易訊號發布
    "\u26a0\ufe0f": "CANCEL",  # ⚠️ 掛單取消
    "\U0001f680": "INFO",      # 🚀 訊號成交
    "\U0001f6d1": "INFO",      # 🛑 止損出場
    "\U0001f4b0": "INFO",      # 💰 盈虧更新
}

# Types that should be forwarded to the API
ACTIONABLE_TYPES = {"ENTRY", "CANCEL"}


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
    ):
        self.channel_ids = set(discord_config.channel_ids) if discord_config.channel_ids else set()
        self.guild_ids = set(discord_config.guild_ids) if discord_config.guild_ids else None
        self.author_ids = set(discord_config.author_ids) if discord_config.author_ids else None
        self.api_client = api_client
        self.dry_run = dry_run
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

        # Build content (message text + embeds)
        content = msg.get("content", "")
        if not content:
            embeds = msg.get("embeds", [])
            embed_parts = []
            for embed in embeds:
                if embed.get("description"):
                    embed_parts.append(embed["description"])
                if embed.get("title"):
                    embed_parts.append(embed["title"])
            content = "\n".join(embed_parts)

        if not content.strip():
            return

        # Dedup
        if message_id in self._processed_ids:
            return
        self._processed_ids.add(message_id)
        self._trim_dedup_set()

        # Identify signal type
        signal_type = self._identify_type(content)

        logger.info(
            "[%s] #%s @%s: %s",
            signal_type,
            channel_id[-6:],
            author_name,
            content[:120].replace("\n", " | "),
        )

        # Only forward actionable signals
        if signal_type not in ACTIONABLE_TYPES:
            logger.debug("Signal type %s is info-only, skipping API call", signal_type)
            return

        await self._forward_signal(content)

    def _identify_type(self, content: str) -> str:
        """Identify signal type by emoji prefix."""
        stripped = content.strip()
        for emoji, sig_type in SIGNAL_TYPES.items():
            if stripped.startswith(emoji):
                return sig_type
        return "UNKNOWN"

    async def _forward_signal(self, content: str) -> None:
        """Forward the signal to the Spring Boot API."""
        result = await self.api_client.send_signal(content, dry_run=self.dry_run)

        if result.success:
            logger.info("API response OK: %s", result.summary[:200])
        else:
            logger.warning(
                "API response FAILED (HTTP %d): %s",
                result.status_code,
                result.error,
            )

    def _trim_dedup_set(self) -> None:
        """Prevent unbounded memory growth of dedup set."""
        if len(self._processed_ids) > self._max_dedup_size:
            to_keep = list(self._processed_ids)[self._max_dedup_size // 2:]
            self._processed_ids = set(to_keep)
