"""Runs one conversational turn: streams deltas, drives navi_state, handles cancellation."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import uuid
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from app import protocol as p
from app.agent.context import dangling_tool_calls, dynamic_context
from app.agent.graph import AgentDeps
from app.session import DeviceSession

log = logging.getLogger("navi.turn")


class DeltaCoalescer:
    """Buffers text and flushes it every `interval_ms` (spec §3 `assistant_delta`)."""

    def __init__(self, session: DeviceSession, turn_id: str, interval_ms: int) -> None:
        self._session = session
        self._turn_id = turn_id
        self._interval = interval_ms / 1000
        self._buf: list[str] = []
        self._task: asyncio.Task[None] | None = None
        self.sent: list[str] = []

    def add(self, text: str) -> None:
        self._buf.append(text)
        if self._task is None:
            self._task = asyncio.create_task(self._flush_later())

    async def _flush_later(self) -> None:
        await asyncio.sleep(self._interval)
        self._task = None
        await self.flush()

    async def flush(self) -> None:
        if not self._buf:
            return
        text, self._buf = "".join(self._buf), []
        self.sent.append(text)
        await self._session.send(p.AssistantDelta(turn_id=self._turn_id, text=text))

    async def aclose(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        # Anything unflushed on cancel is still part of the visible partial reply.
        self.sent.extend(["".join(self._buf)] if self._buf else [])
        self._buf = []

    @property
    def partial(self) -> str:
        return "".join(self.sent)


async def repair_dangling(graph: Any, config: dict[str, Any]) -> None:
    """Close tool calls left open by a cancelled/crashed turn so providers accept the history."""
    snap = await graph.aget_state(config)
    msgs = (snap.values or {}).get("messages", [])
    missing = dangling_tool_calls(msgs)
    if missing:
        fixes = [
            ToolMessage(
                content='{"ok":false,"error":{"code":"cancelled","message":"turn cancelled"}}',
                tool_call_id=cid,
                name=name,
            )
            for cid, name in missing
        ]
        await graph.aupdate_state(config, {"messages": fixes}, as_node="tools_node")


async def run_turn(deps: AgentDeps, graph: Any, session: DeviceSession, text: str) -> None:
    turn_id = uuid.uuid4().hex[:12]
    session.turn_id = turn_id
    session.roll_conversation_if_idle(deps.settings.conversation_idle_min)
    config = {
        "configurable": {"thread_id": session.thread_id, "device_id": session.device_id},
        "recursion_limit": 3 * deps.settings.max_tool_calls_per_turn + 10,
    }
    coalescer = DeltaCoalescer(session, turn_id, deps.settings.delta_flush_ms)
    talking = False
    cancelled = False
    failed: str | None = None
    try:
        await session.send(p.NaviState(state="processing"))
        await repair_dangling(graph, config)
        inputs = {
            "messages": [HumanMessage(content=text)],
            "turn_id": turn_id,
            "dynamic": dynamic_context(session.info.foreground_package, session.info.tz),
            "tool_calls_this_turn": 0,
            "recent_calls": [],
            "abort": False,
        }
        async for event in graph.astream_events(inputs, config, version="v2"):
            if event["event"] != "on_chat_model_stream":
                continue
            if event.get("metadata", {}).get("langgraph_node") != "llm_node":
                continue
            chunk_text = event["data"]["chunk"].content
            if isinstance(chunk_text, list):  # Anthropic content blocks
                chunk_text = "".join(b.get("text", "") for b in chunk_text if isinstance(b, dict))
            if not chunk_text:
                continue
            if not talking:
                talking = True
                await session.send(p.NaviState(state="talking"))
            coalescer.add(chunk_text)
    except asyncio.CancelledError:
        cancelled = True
    except ConnectionError:
        log.info("turn %s ended: device gone", turn_id)
        return
    except Exception:
        log.exception("turn %s failed", turn_id)
        failed = "The brain hit an error. Please try again."
    finally:
        # Finalisation must run even when the task was cancelled.
        await _finish(deps, graph, session, config, coalescer, turn_id, cancelled, failed)
    if cancelled:
        raise asyncio.CancelledError


async def _finish(
    deps: AgentDeps,
    graph: Any,
    session: DeviceSession,
    config: dict[str, Any],
    coalescer: DeltaCoalescer,
    turn_id: str,
    cancelled: bool,
    failed: str | None,
) -> None:
    try:
        await coalescer.flush()
        await coalescer.aclose()
        await session.bridge.cancel_all()
        if cancelled:
            await repair_dangling(graph, config)
            final = coalescer.partial
        elif failed:
            final = failed
        else:
            snap = await graph.aget_state(config)
            msgs = (snap.values or {}).get("messages", [])
            last_ai = next((m for m in reversed(msgs) if isinstance(m, AIMessage)), None)
            final = str(last_ai.content) if last_ai else ""
        await session.send(p.AssistantDone(turn_id=turn_id, text=final, cancelled=cancelled))
        await session.send(p.NaviState(state="idle"))
    except Exception:
        log.debug("could not deliver final turn messages", exc_info=True)
    finally:
        if session.turn_id == turn_id:
            session.turn_id = None
