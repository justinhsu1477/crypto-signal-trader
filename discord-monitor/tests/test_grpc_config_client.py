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
        self.source_metadata_map = {}
        self.ai_parser = None


class FakeSourceConfig:
    """模擬 gRPC SourceConfig protobuf message。"""

    def __init__(self, id=0, channel_id="", name="", display_name="",
                 routing_mode="GLOBAL", trade_mode="AUTO", risk_multiplier=1.0,
                 custom_prompt="", custom_prompt_version=0):
        self.id = id
        self.channel_id = channel_id
        self.name = name
        self.display_name = display_name
        self.routing_mode = routing_mode
        self.trade_mode = trade_mode
        self.risk_multiplier = risk_multiplier
        self.custom_prompt = custom_prompt
        self.custom_prompt_version = custom_prompt_version


class FakeConfig:
    """模擬 gRPC MonitorConfig protobuf message。"""

    def __init__(self, channel_ids=None, guild_ids=None, author_ids=None,
                 ignore_keywords=None, sources=None, version=1,
                 active_prompt="", active_prompt_version=0, active_model=""):
        self.channel_ids = channel_ids or []
        self.guild_ids = guild_ids or []
        self.author_ids = author_ids or []
        self.ignore_keywords = ignore_keywords or []
        self.sources = sources or []
        self.version = version
        self.active_prompt = active_prompt
        self.active_prompt_version = active_prompt_version
        self.active_model = active_model


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


class TestSourceMetadata:
    """_apply_config — per-source metadata 解析。"""

    def setup_method(self):
        self.router = FakeSignalRouter()
        self.client = GrpcConfigClient(
            grpc_target="localhost:9090",
            api_key="test-key",
            signal_router=self.router,
        )

    def test_sources_build_metadata_map(self):
        """帶 sources — 建立 channel_id → metadata 映射。"""
        sources = [
            FakeSourceConfig(id=1, channel_id="ch-1", name="s1",
                             display_name="Source 1", trade_mode="SHADOW", risk_multiplier=1.5),
            FakeSourceConfig(id=2, channel_id="ch-2", name="s2",
                             display_name="Source 2", trade_mode="AUTO", risk_multiplier=1.0),
        ]
        config = FakeConfig(channel_ids=["ch-1", "ch-2"], sources=sources)
        self.client._apply_config(config, "test")

        assert len(self.router.source_metadata_map) == 2
        assert self.router.source_metadata_map["ch-1"]["name"] == "s1"
        assert self.router.source_metadata_map["ch-1"]["trade_mode"] == "SHADOW"
        assert self.router.source_metadata_map["ch-1"]["risk_multiplier"] == 1.5
        assert self.router.source_metadata_map["ch-2"]["trade_mode"] == "AUTO"

    def test_empty_sources_no_change(self):
        """sources 為空 — 不更新 metadata map。"""
        self.router.source_metadata_map = {"old": {"name": "old"}}
        config = FakeConfig(channel_ids=["ch-1"], sources=[])
        self.client._apply_config(config, "test")

        # 空 sources 不會覆蓋既有 map
        assert self.router.source_metadata_map == {"old": {"name": "old"}}

    def test_source_without_channel_id_skipped(self):
        """source 無 channel_id — 跳過不加入 map。"""
        sources = [
            FakeSourceConfig(id=1, channel_id="", name="no-channel"),
            FakeSourceConfig(id=2, channel_id="ch-2", name="with-channel"),
        ]
        config = FakeConfig(channel_ids=["ch-2"], sources=sources)
        self.client._apply_config(config, "test")

        assert len(self.router.source_metadata_map) == 1
        assert "ch-2" in self.router.source_metadata_map


class FakeAiParser:
    """模擬 AiParser，追蹤 prompt / model 更新。"""

    def __init__(self, prompt_version=0, model="gemini-2.0-flash"):
        self._prompt_version = prompt_version
        self._model = model

    @property
    def prompt_version(self):
        return self._prompt_version

    @property
    def model(self):
        return self._model

    def update_model(self, new_model):
        self._model = new_model

    def update_system_prompt(self, new_prompt, version):
        self._system_prompt = new_prompt
        self._prompt_version = version


class TestPromptHotUpdate:
    """_apply_config — AI prompt 熱更新。"""

    def setup_method(self):
        self.router = FakeSignalRouter()
        self.router.ai_parser = FakeAiParser(prompt_version=0)
        self.client = GrpcConfigClient(
            grpc_target="localhost:9090",
            api_key="test-key",
            signal_router=self.router,
        )

    def test_prompt_update_applied(self):
        """帶 active_prompt — 更新 ai_parser 的 system prompt。"""
        config = FakeConfig(
            channel_ids=["ch-1"],
            active_prompt="你是交易分析師",
            active_prompt_version=3,
        )
        self.client._apply_config(config, "prompt_activated:v3")

        assert self.router.ai_parser.prompt_version == 3
        assert self.router.ai_parser._system_prompt == "你是交易分析師"

    def test_same_version_no_update(self):
        """相同版本號 — 不觸發更新。"""
        self.router.ai_parser = FakeAiParser(prompt_version=3)
        config = FakeConfig(
            channel_ids=["ch-1"],
            active_prompt="same prompt",
            active_prompt_version=3,
        )
        self.client._apply_config(config, "test")

        # prompt_version 不變，不應呼叫 update
        assert self.router.ai_parser.prompt_version == 3
        assert not hasattr(self.router.ai_parser, "_system_prompt")

    def test_no_ai_parser_no_error(self):
        """ai_parser 為 None — 不報錯。"""
        self.router.ai_parser = None
        config = FakeConfig(
            channel_ids=["ch-1"],
            active_prompt="some prompt",
            active_prompt_version=1,
        )
        # Should not raise
        self.client._apply_config(config, "test")

    def test_empty_prompt_no_update(self):
        """active_prompt 為空 — 不觸發更新。"""
        config = FakeConfig(
            channel_ids=["ch-1"],
            active_prompt="",
            active_prompt_version=1,
        )
        self.client._apply_config(config, "test")

        assert self.router.ai_parser.prompt_version == 0


class TestModelHotUpdate:
    """_apply_config — AI model 中央熱更新（gRPC 推送）。

    用途：Google 下架某 model 時，server 改 MONITOR_AI_MODEL 即可全體切換，免動每台 monitor。
    """

    def setup_method(self):
        self.router = FakeSignalRouter()
        self.router.ai_parser = FakeAiParser(model="gemini-2.0-flash")
        self.client = GrpcConfigClient(
            grpc_target="localhost:9090",
            api_key="test-key",
            signal_router=self.router,
        )

    def test_model_update_applied(self):
        """帶 active_model — 覆蓋 ai_parser 的 model。"""
        config = FakeConfig(channel_ids=["ch-1"], active_model="gemini-2.5-flash")
        self.client._apply_config(config, "test")

        assert self.router.ai_parser.model == "gemini-2.5-flash"

    def test_empty_model_no_overwrite(self):
        """active_model 為空 — 沿用本地 config.yml 的 model 不覆蓋。"""
        config = FakeConfig(channel_ids=["ch-1"], active_model="")
        self.client._apply_config(config, "test")

        assert self.router.ai_parser.model == "gemini-2.0-flash"

    def test_same_model_no_error(self):
        """相同 model — 不報錯，維持原值。"""
        config = FakeConfig(channel_ids=["ch-1"], active_model="gemini-2.0-flash")
        self.client._apply_config(config, "test")

        assert self.router.ai_parser.model == "gemini-2.0-flash"

    def test_no_ai_parser_no_error(self):
        """ai_parser 為 None — 不報錯。"""
        self.router.ai_parser = None
        config = FakeConfig(channel_ids=["ch-1"], active_model="gemini-2.5-flash")
        # Should not raise
        self.client._apply_config(config, "test")
