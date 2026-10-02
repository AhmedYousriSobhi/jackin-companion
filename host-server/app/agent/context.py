"""context_node helpers: build the trimmed message list sent to the LLM (spec §5.3).

M0/M1 scope: stable system prompt, oldest-first trimming to CONTEXT_MAX_TOKENS, dynamic values
in the last user message. Snapshot rendering/elision arrives with M2, summaries with M5.
"""

from __future__ import annotations

import time
from collections.abc import Sequence

from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)

from app.agent.prompts import SYSTEM_PROMPT


def estimate_tokens(text: str) -> int:
    return max(1, len(text) // 4)


def _msg_tokens(m: BaseMessage) -> int:
    n = estimate_tokens(str(m.content))
    if isinstance(m, AIMessage):
        n += sum(estimate_tokens(str(c.get("args", ""))) + 4 for c in m.tool_calls)
    return n


def dynamic_context(foreground_package: str | None, tz_name: str = "UTC") -> str:
    """Per-turn dynamic facts. Computed once at turn start so it is stable within the turn."""
    stamp = time.strftime("%Y-%m-%d %H:%M", time.localtime())
    return f"[context: app={foreground_package or 'unknown'} time={stamp} tz={tz_name}]"


def build_context(
    messages: Sequence[BaseMessage], dynamic: str, max_tokens: int
) -> list[BaseMessage]:
    kept = list(messages)
    total = sum(_msg_tokens(m) for m in kept)
    while kept and total > max_tokens and len(kept) > 1:
        total -= _msg_tokens(kept.pop(0))
    # Never start mid tool exchange: drop leading tool results / dangling tool-call messages.
    while kept and not isinstance(kept[0], HumanMessage):
        kept.pop(0)
    out: list[BaseMessage] = [SystemMessage(content=SYSTEM_PROMPT)]
    last_human = max((i for i, m in enumerate(kept) if isinstance(m, HumanMessage)), default=None)
    for i, m in enumerate(kept):
        if i == last_human and dynamic:
            m = HumanMessage(content=f"{m.content}\n{dynamic}", id=m.id)
        out.append(m)
    return out


def dangling_tool_calls(messages: Sequence[BaseMessage]) -> list[tuple[str, str]]:
    """(tool_call_id, name) of tool calls that never got a ToolMessage (cancelled/crashed turn)."""
    answered = {m.tool_call_id for m in messages if isinstance(m, ToolMessage)}
    missing: list[tuple[str, str]] = []
    for m in messages:
        if isinstance(m, AIMessage):
            missing += [(c["id"], c["name"]) for c in m.tool_calls if c["id"] not in answered]
    return missing
