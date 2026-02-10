"""Discord Gateway event filtering and message extraction from CDP WebSocket frames."""
from __future__ import annotations

import base64
import json
import logging
import zlib
from dataclasses import dataclass

from .config import DiscordConfig

logger = logging.getLogger(__name__)

ZLIB_SUFFIX = b"\x00\x00\xff\xff"


@dataclass
class DiscordMessage:
    """Extracted Discord message from a Gateway MESSAGE_CREATE event."""
    message_id: str
    channel_id: str
    guild_id: str | None
    author_id: str
    author_name: str
    content: str
    timestamp: str
    raw_event: dict


class DiscordGatewayFilter:
    """Parses CDP WebSocket frame payloads and extracts Discord messages
    from whitelisted channels."""

    def __init__(self, config: DiscordConfig):
        self.channel_ids = set(config.channel_ids) if config.channel_ids else set()
        self.guild_ids = set(config.guild_ids) if config.guild_ids else None
        self.author_ids = set(config.author_ids) if config.author_ids else None
        self._zlib_ctx = zlib.decompressobj()
        self._buffer = bytearray()

    def process_frame(self, payload_data: str) -> DiscordMessage | None:
        """Parse a WebSocket frame payload from CDP.

        Tries:
        1. Direct JSON parse (text frames)
        2. Base64 decode + zlib decompress (binary frames)

        Returns DiscordMessage if it's a MESSAGE_CREATE from a whitelisted channel.
        """
        if not payload_data:
            return None

        # Path 1: Direct JSON parse (text frames)
        event = self._try_json_parse(payload_data)
        if event:
            logger.debug("Frame parsed as JSON directly (text frame)")
            return self._extract_message(event)

        # Path 2: Base64 + zlib (binary frames)
        event = self._try_binary_decompress(payload_data)
        if event:
            logger.debug("Frame decoded via base64+zlib (binary frame)")
            return self._extract_message(event)

        # Debug: log what we received if both paths failed
        preview = payload_data[:80] if len(payload_data) > 80 else payload_data
        logger.debug("Frame not parseable: len=%d preview=%s", len(payload_data), preview)

        return None

    def _try_json_parse(self, data: str) -> dict | None:
        """Try to parse data as JSON directly."""
        try:
            return json.loads(data)
        except (json.JSONDecodeError, TypeError):
            return None

    def _try_binary_decompress(self, data: str) -> dict | None:
        """Try base64 decode + zlib decompress for binary WebSocket frames.

        Discord uses zlib-stream compression. CDP may deliver frames that:
        1. End with Z_SYNC_FLUSH (standard) — accumulate in buffer
        2. Are individually compressed — try each frame independently
        """
        try:
            raw_bytes = base64.b64decode(data)
        except Exception:
            return None

        # Strategy 1: Check for Z_SYNC_FLUSH suffix (streaming zlib)
        self._buffer.extend(raw_bytes)
        has_suffix = len(self._buffer) >= 4 and self._buffer[-4:] == ZLIB_SUFFIX

        if has_suffix:
            try:
                result = self._zlib_ctx.decompress(bytes(self._buffer))
                self._buffer.clear()
                decoded = result.decode("utf-8")
                logger.debug("zlib-stream decompressed: %s", decoded[:120])
                return json.loads(decoded)
            except (zlib.error, json.JSONDecodeError, UnicodeDecodeError) as e:
                logger.debug("zlib-stream failed: %s", e)
                self._zlib_ctx = zlib.decompressobj()
                self._buffer.clear()

        # Strategy 2: Try decompressing the single frame independently
        for wbits in [-15, 15, -zlib.MAX_WBITS, zlib.MAX_WBITS | 16]:
            try:
                result = zlib.decompress(raw_bytes, wbits)
                decoded = result.decode("utf-8")
                logger.debug("Single-frame zlib (wbits=%d): %s", wbits, decoded[:120])
                self._buffer.clear()
                return json.loads(decoded)
            except Exception:
                continue

        # Strategy 3: Try raw inflate on the accumulated buffer
        if len(self._buffer) > len(raw_bytes):
            for wbits in [-15, 15]:
                try:
                    result = zlib.decompress(bytes(self._buffer), wbits)
                    decoded = result.decode("utf-8")
                    logger.debug("Buffer zlib (wbits=%d): %s", wbits, decoded[:120])
                    self._buffer.clear()
                    return json.loads(decoded)
                except Exception:
                    continue

        # Prevent buffer from growing forever
        if len(self._buffer) > 1024 * 1024:
            logger.debug("Buffer too large (%d bytes), clearing", len(self._buffer))
            self._buffer.clear()
            self._zlib_ctx = zlib.decompressobj()

        return None

    def _extract_message(self, event: dict) -> DiscordMessage | None:
        """Extract a DiscordMessage from a Gateway event if it passes all filters."""
        op = event.get("op")
        t = event.get("t")

        # Log all Gateway events for debugging
        if op is not None:
            logger.debug("Gateway event: op=%s t=%s", op, t)

        # Must be a Dispatch event (op=0) with type MESSAGE_CREATE
        if op != 0 or t != "MESSAGE_CREATE":
            return None

        d = event.get("d", {})
        channel_id = d.get("channel_id", "")
        guild_id = d.get("guild_id")
        author = d.get("author", {})

        # Log MESSAGE_CREATE even if not in whitelist (for debugging)
        logger.debug(
            "MESSAGE_CREATE: channel=%s guild=%s author=%s content=%s",
            channel_id, guild_id, author.get("username", "?"),
            d.get("content", "")[:80],
        )

        # Channel whitelist
        if self.channel_ids and channel_id not in self.channel_ids:
            return None

        # Optional guild filter
        if self.guild_ids and guild_id not in self.guild_ids:
            return None

        # Optional author filter
        if self.author_ids and author.get("id") not in self.author_ids:
            return None

        # Extract content — check both message content and embeds
        content = d.get("content", "")
        if not content and d.get("embeds"):
            embed_parts = []
            for embed in d["embeds"]:
                if embed.get("description"):
                    embed_parts.append(embed["description"])
                if embed.get("title"):
                    embed_parts.append(embed["title"])
            content = "\n".join(embed_parts)

        if not content.strip():
            return None

        return DiscordMessage(
            message_id=d.get("id", ""),
            channel_id=channel_id,
            guild_id=guild_id,
            author_id=author.get("id", ""),
            author_name=author.get("username", "unknown"),
            content=content,
            timestamp=d.get("timestamp", ""),
            raw_event=event,
        )

    def reset_zlib(self) -> None:
        """Reset zlib context (call after CDP reconnection)."""
        self._zlib_ctx = zlib.decompressobj()
        self._buffer.clear()
