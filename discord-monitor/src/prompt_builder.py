"""Prompt composition helpers for the Discord signal parser.

This module keeps the prompt architecture explicit:
- base schema: output contract and invariant JSON requirements
- action rules: parser decision rules
- source-specific override: optional per-channel dialect hints
- eval examples: few-shot / regression examples
"""
from __future__ import annotations

from dataclasses import dataclass


RULES_MARKER = "## 規則"
EXAMPLES_MARKER = "## 範例"
COMPOUND_MARKER = "## 複合動作識別"
SOURCE_OVERRIDE_HEADING = "## 來源專屬規則（Source-specific Override）"


@dataclass(frozen=True)
class SignalPromptSections:
    """Structured prompt sections for signal parsing."""

    base_schema: str
    action_rules: str
    eval_examples: str
    compound_rules: str = ""

    @classmethod
    def from_legacy_prompt(cls, prompt: str) -> "SignalPromptSections":
        """Split an existing monolithic prompt into structured sections.

        The old prompt already had stable markdown headings, so we reuse those
        headings as boundaries. If an admin-provided prompt does not contain the
        headings, it is treated as a base prompt and still renders safely.
        """
        if not prompt:
            return cls(base_schema="", action_rules="", eval_examples="")

        base_schema = prompt.strip()
        action_rules = ""
        eval_examples = ""
        compound_rules = ""

        if RULES_MARKER in base_schema:
            base_schema, rest = base_schema.split(RULES_MARKER, 1)
            action_rules = RULES_MARKER + rest

        if EXAMPLES_MARKER in action_rules:
            action_rules, rest = action_rules.split(EXAMPLES_MARKER, 1)
            eval_examples = EXAMPLES_MARKER + rest

        if COMPOUND_MARKER in eval_examples:
            eval_examples, rest = eval_examples.split(COMPOUND_MARKER, 1)
            compound_rules = COMPOUND_MARKER + rest

        return cls(
            base_schema=base_schema.strip(),
            action_rules=action_rules.strip(),
            eval_examples=eval_examples.strip(),
            compound_rules=compound_rules.strip(),
        )

    def render(self, source_override: str | None = None, source_name: str | None = None) -> str:
        """Render the final system prompt.

        Source overrides are intentionally inserted after the generic action
        rules and before examples. This lets source-specific dialect hints guide
        interpretation while preserving the base schema/output contract.
        """
        sections = [
            self.base_schema,
            self.action_rules,
            self._render_source_override(source_override, source_name),
            self.eval_examples,
            self.compound_rules,
        ]
        return "\n\n".join(section.strip() for section in sections if section and section.strip())

    @staticmethod
    def _render_source_override(source_override: str | None, source_name: str | None) -> str:
        if not source_override or not source_override.strip():
            return ""

        label = (source_name or "unknown").strip() or "unknown"
        override = source_override.strip()

        return f"""{SOURCE_OVERRIDE_HEADING}
來源：{label}

以下規則只適用於此 Discord 訊號來源，用於補充該來源的語氣、格式或特殊用詞。
- 不可覆蓋「輸出 JSON Schema」與「只輸出 JSON」等基礎契約
- 若與通用 action 判斷衝突，以更明確的來源專屬語意作為解析依據
- 不可新增 schema 未定義欄位，除非既有規則明確允許 optional 欄位

{override}"""
