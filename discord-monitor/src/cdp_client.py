"""Chrome DevTools Protocol client for connecting to Discord's Electron process.

Uses Runtime.evaluate to hook into Discord's JavaScript Dispatcher,
intercepting MESSAGE_CREATE events directly at the application layer.
This avoids the complexity of decoding Discord's binary WebSocket frames (ETF format).
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Awaitable, Callable

import aiohttp
import websockets

from .config import CdpConfig

logger = logging.getLogger(__name__)

# JavaScript to inject into Discord's page.
# This hooks into Discord's internal Flux Dispatcher to capture MESSAGE_CREATE events.
# When a message arrives, it stores it in a queue that we poll from Python.
#
# Key insight: Discord has TWO webpack runtimes. The main runtime (with ~19k modules)
# contains the Flux Dispatcher at module 73153.h. The secondary runtime (~6k modules)
# cannot access it. webpackChunkdiscord_app.push() gives us the main require function
# as the FIRST callback argument.
INJECT_JS = """
(() => {
    if (window.__signalMonitorActive) return 'already_active';

    // Collect all webpack require functions from the chunk push.
    // Discord has multiple webpack runtimes; the first one is the main runtime
    // that contains the Flux Dispatcher.
    var requires = [];
    try {
        window.webpackChunkdiscord_app.push([
            [Symbol('signalMonitor')],
            {},
            function(req) { requires.push(req); }
        ]);
    } catch(e) {
        return 'chunk_push_error: ' + e.message;
    }

    if (requires.length === 0) {
        return 'webpack_not_found';
    }

    // Find the Dispatcher across all webpack runtimes.
    // The Dispatcher is exported as module 73153.h in the main runtime,
    // but we also search generically in case module IDs change after Discord updates.
    var Dispatcher = null;

    for (var r = 0; r < requires.length && !Dispatcher; r++) {
        var req = requires[r];

        // Strategy 1: Try the known module ID (73153) — fast path
        try {
            var fluxMod = req(73153);
            if (fluxMod && fluxMod.h && typeof fluxMod.h.subscribe === 'function') {
                Dispatcher = fluxMod.h;
            }
        } catch(e) {}

        // Strategy 2: Search loaded modules for an object with subscribe + dispatch
        if (!Dispatcher) {
            var cKeys = Object.keys(req.c);
            for (var i = 0; i < cKeys.length; i++) {
                try {
                    var mod = req.c[cKeys[i]];
                    if (!mod || !mod.exports) continue;
                    var exp = mod.exports;

                    // Check enumerable properties (webpack getters)
                    for (var p in exp) {
                        try {
                            var val = exp[p];
                            if (val && typeof val === 'object' &&
                                typeof val.subscribe === 'function' &&
                                typeof val.dispatch === 'function') {
                                Dispatcher = val;
                                break;
                            }
                        } catch(e2) {}
                    }
                    if (Dispatcher) break;

                    // Check default export
                    if (exp.default && typeof exp.default === 'object' &&
                        typeof exp.default.subscribe === 'function' &&
                        typeof exp.default.dispatch === 'function') {
                        Dispatcher = exp.default;
                        break;
                    }
                } catch(e) {}
            }
        }
    }

    if (!Dispatcher) {
        return 'dispatcher_not_found (checked ' + requires.length + ' runtimes)';
    }

    window.__signalMonitorQueue = [];
    window.__signalMonitorActive = true;

    // Build a Python-side data dict from a Discord internal message object.
    // 共用 builder：MESSAGE_CREATE / MESSAGE_UPDATE 共用同一份欄位 schema，差異
    // 僅在後續 push 時把 is_edit / edit_revision 加上去（避免 30+ 行重複）。
    function buildMessageData(msg, event) {
        return {
            id: msg.id,
            channel_id: msg.channel_id,
            guild_id: event.guildId || msg.guild_id || '',
            author_id: msg.author ? msg.author.id : '',
            author_name: msg.author ? msg.author.username : '',
            content: msg.content || '',
            timestamp: msg.timestamp || '',
            embeds: (msg.embeds || []).map(function(e) {
                return {
                    title: e.title || '',
                    description: e.description || ''
                };
            }),
            // 附件（圖片、影片、檔案） — 失敗時 fallback 到空陣列
            attachments: (function() {
                try {
                    return (msg.attachments || []).map(function(a) {
                        return {
                            id: a.id || '',
                            filename: a.filename || '',
                            url: a.url || '',
                            proxy_url: a.proxy_url || '',
                            content_type: a.content_type || '',
                            size: a.size || 0,
                            width: a.width || 0,
                            height: a.height || 0
                        };
                    });
                } catch (e) {
                    return [];
                }
            })(),
            // embed 中的圖片（image / thumbnail）
            embed_images: (function() {
                try {
                    var imgs = [];
                    (msg.embeds || []).forEach(function(e) {
                        if (e.image && e.image.url) {
                            imgs.push({url: e.image.url, width: e.image.width || 0, height: e.image.height || 0});
                        } else if (e.thumbnail && e.thumbnail.url) {
                            imgs.push({url: e.thumbnail.url, width: e.thumbnail.width || 0, height: e.thumbnail.height || 0});
                        }
                    });
                    return imgs;
                } catch (e) {
                    return [];
                }
            })(),
            has_reference: !!msg.message_reference,
            has_snapshots: !!(msg.message_snapshots && msg.message_snapshots.length > 0),
            referenced_content: (msg.referenced_message && msg.referenced_message.content) || ''
        };
    }

    function enqueueData(data) {
        window.__signalMonitorQueue.push(JSON.stringify(data));
        // Keep queue bounded
        if (window.__signalMonitorQueue.length > 100) {
            window.__signalMonitorQueue.shift();
        }
    }

    // Subscribe to MESSAGE_CREATE events
    Dispatcher.subscribe('MESSAGE_CREATE', function(event) {
        try {
            var msg = event.message || event;
            if (!msg.id || !msg.channel_id) return;
            enqueueData(buildMessageData(msg, event));
        } catch(e) {
            // silently ignore
        }
    });

    // Subscribe to MESSAGE_UPDATE events
    // Scenario: 陳哥 posts placeholder, then edits to add full signal.
    // signal_router 會用 is_edit 旗標 + edit_revision 後綴 message_id 避開 L1 dedup。
    Dispatcher.subscribe('MESSAGE_UPDATE', function(event) {
        try {
            var msg = event.message || event;
            if (!msg.id || !msg.channel_id) return;
            var data = buildMessageData(msg, event);
            // 時間優先取 edited_timestamp（如果 Discord 有給），否則退回原 timestamp
            data.timestamp = msg.edited_timestamp || msg.timestamp || '';
            data.is_edit = true;
            // 用 epoch ms 做 revision counter，signal_router 取末 6 碼當 suffix
            data.edit_revision = Date.now();
            enqueueData(data);
        } catch(e) {
            // silently ignore
        }
    });

    return 'ok';
})()
"""

# JavaScript to drain the message queue
DRAIN_JS = """
(() => {
    var q = window.__signalMonitorQueue || [];
    var items = q.splice(0, q.length);
    return JSON.stringify(items);
})()
"""

# JavaScript to clear stale state before re-injection (used on reconnect)
CLEAR_JS = """
delete window.__signalMonitorActive;
delete window.__signalMonitorQueue;
'cleared';
"""


class CdpClient:
    """Connects to Discord via CDP and polls for messages via JS injection."""

    def __init__(self, config: CdpConfig):
        self.config = config
        self._ws = None
        self._msg_id = 0

    async def connect(self) -> None:
        """Discover CDP targets, connect, and inject the message hook."""
        targets = await self._discover_targets()
        target = self._select_discord_target(targets)

        ws_url = target["webSocketDebuggerUrl"]
        # CDP 回傳的 ws URL 可能是 ws://localhost/...，容器裡需替換為實際 host
        ws_url = ws_url.replace("ws://localhost", f"ws://{self.config.host}:{self.config.port}")
        logger.info("Connecting to CDP target: %s (%s)", target.get("title", ""), ws_url)

        self._ws = await websockets.connect(
            ws_url,
            max_size=2**24,
            additional_headers={"Host": "localhost"},  # CDP Host 檢查
        )

        # Clear any stale state from previous connection attempts
        await self._evaluate_js(CLEAR_JS)

        # Inject the message hook into Discord's page
        result = await self._evaluate_js(INJECT_JS)
        logger.info("JS hook injection result: %s", result)

        if result not in ("ok", "already_active"):
            raise ConnectionError(f"Failed to inject message hook: {result}")

    async def disconnect(self) -> None:
        """Close the CDP WebSocket connection."""
        if self._ws:
            try:
                await self._ws.close()
            except Exception:
                pass
            self._ws = None

    async def listen(self, callback: Callable[[dict], Awaitable[None]]) -> None:
        """Poll for new messages and dispatch them via callback.

        Instead of intercepting raw WebSocket frames, we poll a JavaScript
        queue that collects MESSAGE_CREATE events from Discord's Dispatcher.

        Args:
            callback: async function called with each message dict containing
                      id, channel_id, guild_id, author_id, author_name,
                      content, timestamp, embeds.
        """
        if not self._ws:
            raise ConnectionError("Not connected. Call connect() first.")

        while True:
            try:
                raw = await self._evaluate_js(DRAIN_JS)
                if raw:
                    messages = json.loads(raw)
                    for msg_json in messages:
                        try:
                            msg = json.loads(msg_json)
                            await callback(msg)
                        except Exception:
                            logger.exception("Error processing message")
            except websockets.exceptions.ConnectionClosed:
                raise
            except Exception:
                logger.exception("Error in poll loop")

            # Poll every 500ms for responsive signal detection
            await asyncio.sleep(0.5)

    async def _discover_targets(self) -> list[dict]:
        """GET http://{host}:{port}/json to list CDP targets."""
        url = f"http://{self.config.host}:{self.config.port}/json"
        logger.debug("Discovering CDP targets at %s", url)

        async with aiohttp.ClientSession() as session:
            # Host header 必須是 localhost，否則 CDP 會拒絕非本機連線
            headers = {"Host": "localhost"}
            async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=5)) as resp:
                if resp.status != 200:
                    raise ConnectionError(f"CDP discovery failed: HTTP {resp.status}")
                targets = await resp.json()

        logger.debug("Found %d CDP targets", len(targets))
        return targets

    def _select_discord_target(self, targets: list[dict]) -> dict:
        """Find the main Discord renderer page target."""
        for t in targets:
            if t.get("type") == "page" and "discord" in t.get("url", "").lower():
                return t
        for t in targets:
            if t.get("type") == "page" and "discord" in t.get("title", "").lower():
                return t
        for t in targets:
            if t.get("type") == "page":
                return t
        raise ConnectionError(
            "No suitable Discord CDP target found. "
            "Make sure Discord is running with --remote-debugging-port"
        )

    async def _evaluate_js(self, expression: str) -> str | None:
        """Execute JavaScript in the Discord page context via CDP Runtime.evaluate."""
        self._msg_id += 1
        msg_id = self._msg_id
        payload = {
            "id": msg_id,
            "method": "Runtime.evaluate",
            "params": {
                "expression": expression,
                "returnByValue": True,
            },
        }
        await self._ws.send(json.dumps(payload))

        deadline = asyncio.get_event_loop().time() + 10
        while asyncio.get_event_loop().time() < deadline:
            try:
                raw = await asyncio.wait_for(self._ws.recv(), timeout=5)
                resp = json.loads(raw)
                if resp.get("id") == msg_id:
                    if "error" in resp:
                        logger.error("CDP eval error: %s", resp["error"])
                        return None
                    result = resp.get("result", {}).get("result", {})
                    return result.get("value")
            except asyncio.TimeoutError:
                break

        return None
