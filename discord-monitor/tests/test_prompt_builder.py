"""Tests for modular signal parser prompt composition."""
from __future__ import annotations

from src.ai_parser import SYSTEM_PROMPT
from src.prompt_builder import (
    EXAMPLES_MARKER,
    RULES_MARKER,
    SOURCE_OVERRIDE_HEADING,
    SignalPromptSections,
)


def test_legacy_prompt_splits_into_expected_sections():
    sections = SignalPromptSections.from_legacy_prompt(SYSTEM_PROMPT)

    assert "輸出 JSON Schema" in sections.base_schema
    assert RULES_MARKER in sections.action_rules
    assert "ENTRY（開倉）判斷規則" in sections.action_rules
    assert EXAMPLES_MARKER in sections.eval_examples
    assert "ENTRY 範例" in sections.eval_examples
    assert "複合動作識別" in sections.compound_rules


def test_source_override_renders_between_rules_and_examples():
    sections = SignalPromptSections.from_legacy_prompt(SYSTEM_PROMPT)

    rendered = sections.render(
        source_override="此來源說「保護」時一律代表移動止損到成本。",
        source_name="chenge",
    )

    assert SOURCE_OVERRIDE_HEADING in rendered
    assert "來源：chenge" in rendered
    assert "此來源說「保護」" in rendered
    assert rendered.index(RULES_MARKER) < rendered.index(SOURCE_OVERRIDE_HEADING)
    assert rendered.index(SOURCE_OVERRIDE_HEADING) < rendered.index(EXAMPLES_MARKER)


def test_blank_source_override_is_omitted():
    sections = SignalPromptSections.from_legacy_prompt(SYSTEM_PROMPT)

    rendered = sections.render(source_override="  ", source_name="chenge")

    assert SOURCE_OVERRIDE_HEADING not in rendered


def test_prompt_without_markers_still_renders_safely():
    sections = SignalPromptSections.from_legacy_prompt("只輸出 JSON")

    assert sections.base_schema == "只輸出 JSON"
    assert sections.action_rules == ""
    assert sections.eval_examples == ""
    assert sections.render() == "只輸出 JSON"

