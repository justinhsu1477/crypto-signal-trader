"""Tests for GrpcConfigClient — gRPC 設定同步客戶端。"""
from __future__ import annotations

import asyncio
from unittest.mock import MagicMock, AsyncMock, patch

import pytest

from src.grpc_config_client import GrpcConfigClient


class FakeSignalRouter:
    """模擬 SignalRouter，追蹤 channel_ids 變更。"""

    def __init__(self):
        self.channel_ids = {"old-ch-1", "old-ch-2"}
        self.guild_ids = set()
        self.author_ids = set()
        self.ignore_keywords = []


class FakeConfig:
    """模擬 gRPC MonitorConfig protobuf message。"""

    def __init__(self, channel_ids=None, guild_ids=None, author_ids=None, ignore_keywords=None, version=1):
        self.channel_ids = channel_ids or []
        self.guild_ids = guild_ids or []
        self.author_ids = author_ids or []
        self.ignore_keywords = ignore_keywords or []
        self.version = version


class TestApplyConfig:
    """_apply_config — 熱更新 SignalRouter。"""

    def setup_method(self):
        self.router = FakeSignalRouter()
        self.client = GrpcConfigClient(
            grpc_target="localhost:9090",
            api_key="test-key",
            signal_router=self.router,
        )

    def test_update_channel_ids(self):
        """新頻道清單 — 替換 channel_ids。"""
        config = FakeConfig(channel_ids=["new-ch-1", "new-ch-2", "new-ch-3"])
        self.client._apply_config(config, "test")

        assert self.router.channel_ids == {"new-ch-1", "new-ch-2", "new-ch-3"}

    def test_same_channel_ids_no_change(self):
        """相同頻道清單 — 不觸發更新。"""
        self.router.channel_ids = {"ch-1", "ch-2"}
        config = FakeConfig(channel_ids=["ch-1", "ch-2"])
        self.client._apply_config(config, "test")

        assert self.router.channel_ids == {"ch-1", "ch-2"}

    def test_update_guild_ids(self):
        """帶 guild_ids — 同步更新。"""
        config = FakeConfig(channel_ids=["ch-1"], guild_ids=["g-1"])
        self.client._apply_config(config, "test")

        assert self.router.guild_ids == {"g-1"}

    def test_update_author_ids(self):
        """帶 author_ids — 同步更新。"""
        config = FakeConfig(channel_ids=["ch-1"], author_ids=["a-1", "a-2"])
        self.client._apply_config(config, "test")

        assert self.router.author_ids == {"a-1", "a-2"}

    def test_update_ignore_keywords(self):
        """帶 ignore_keywords — 同步更新。"""
        config = FakeConfig(channel_ids=["ch-1"], ignore_keywords=["spam", "ad"])
        self.client._apply_config(config, "test")

        assert self.router.ignore_keywords == ["spam", "ad"]

    def test_empty_guild_ids_no_overwrite(self):
        """guild_ids 為空 — 不覆蓋原值。"""
        self.router.guild_ids = {"existing-guild"}
        config = FakeConfig(channel_ids=["ch-1"], guild_ids=[])
        self.client._apply_config(config, "test")

        assert self.router.guild_ids == {"existing-guild"}


class TestMakeMetadata:
    """_make_metadata — gRPC 認證 metadata。"""

    def test_with_api_key(self):
        """有 API Key — 包含 x-api-key。"""
        client = GrpcConfigClient("localhost:9090", "my-secret", MagicMock())
        metadata = client._make_metadata()

        assert ("x-api-key", "my-secret") in metadata

    def test_without_api_key(self):
        """無 API Key — metadata 為空。"""
        client = GrpcConfigClient("localhost:9090", "", MagicMock())
        metadata = client._make_metadata()

        assert len(metadata) == 0


class TestStartStop:
    """start / stop — 生命週期管理。"""

    def test_start_creates_task(self):
        """start() — 建立背景 task。"""
        router = FakeSignalRouter()
        client = GrpcConfigClient("localhost:9090", "key", router)

        # Mock asyncio.create_task to avoid actual gRPC connection
        with patch("asyncio.create_task") as mock_create:
            mock_create.return_value = MagicMock()
            client.start()
            assert client._task is not None

    @pytest.mark.asyncio
    async def test_stop_sets_flag(self):
        """stop() — 設定 _should_stop 為 True。"""
        router = FakeSignalRouter()
        client = GrpcConfigClient("localhost:9090", "key", router)
        client._task = None  # No running task

        await client.stop()

        assert client._should_stop is True
