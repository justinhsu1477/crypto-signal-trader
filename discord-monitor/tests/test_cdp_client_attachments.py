"""驗證 CDP JS hook 注入的訊息 dict 包含 attachments + embed_images 欄位。

注意：此測試不啟動真實 CDP，僅驗證 INJECT_JS 字串中包含必要的程式碼片段。
真實的 JS 執行驗證放在 integration test。
"""
from __future__ import annotations

from src.cdp_client import INJECT_JS


def test_inject_js_extracts_attachments():
    """JS 必須將 msg.attachments 攤平成 Python 端可消費的 dict 陣列。"""
    assert "msg.attachments" in INJECT_JS
    assert "attachments:" in INJECT_JS
    # 必要欄位
    assert "filename" in INJECT_JS
    assert "url" in INJECT_JS
    assert "content_type" in INJECT_JS


def test_inject_js_extracts_embed_images():
    """JS 必須抽取 embed.image / embed.thumbnail 的 URL。"""
    assert "embed_images" in INJECT_JS
    # embed.image 或 thumbnail 任一存在即抽
    assert "e.image" in INJECT_JS or "e.thumbnail" in INJECT_JS


def test_inject_js_attachment_failure_is_safe():
    """JS 抽 attachments 失敗時必須回空陣列，不能讓整則訊息掉。"""
    # 必須有 try/catch 包住 attachment 抽取（fallback 到空陣列）
    # 這個檢查比較鬆散：找 attachments 後面的 catch 或預設值
    assert "|| []" in INJECT_JS  # 存在 fallback 模式
