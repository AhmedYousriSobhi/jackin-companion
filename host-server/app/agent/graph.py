"""LangGraph agent (spec §5.1): context_node -> llm_node -> guard_node -> tools_node loop."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Annotated, Any, TypedDict

import orjson
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, AnyMessage, BaseMessage, ToolMessage
from langchain_core.runnables import RunnableConfig
from langgraph.channels.untracked_value import UntrackedValue
from langgraph.graph import END, StateGraph
from langgraph.graph.message import add_messages

from app.agent.context import build_context
from app.agent.prompts import LIMIT_APOLOGY, LOOP_APOLOGY
from app.agent.tools import ToolContext, enabled_tools, execute_tool
from app.config import Settings
from app.session import DeviceSession, SessionRegistry
from app.usage import UsageMeter

log = logging.getLogger("navi.graph")


class AgentState(TypedDict, total=False):
    messages: Annotated[list[AnyMessage], add_messages]
    turn_id: str
    dynamic: str
    tool_calls_this_turn: int
    recent_calls: list[str]
    abort: bool
    summary: str
    ctx: Annotated[list[BaseMessage], UntrackedValue]  # rebuilt every step; not checkpointed


@dataclass
class AgentDeps:
    settings: Settings
    registry: SessionRegistry
    usage: UsageMeter
    llm: BaseChatModel


def _session(deps: AgentDeps, config: RunnableConfig) -> DeviceSession:
    device_id = config["configurable"]["device_id"]
    session = deps.registry.get(device_id)
    if session is None:
        raise ConnectionError(f"no live session for {device_id}")
    return session


def _signature(call: dict[str, Any]) -> str:
    return f"{call['name']}:{orjson.dumps(call['args'], option=orjson.OPT_SORT_KEYS).decode()}"


def build_graph(deps: AgentDeps, checkpointer: Any):
    cfg = deps.settings

    async def context_node(state: AgentState) -> dict[str, Any]:
        ctx = build_context(state["messages"], state.get("dynamic", ""), cfg.context_max_tokens)
        return {"ctx": ctx}

    async def llm_node(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        session = _session(deps, config)
        tools = enabled_tools(session.info.programs)
        model = deps.llm.bind_tools([t.llm_schema() for t in tools])
        response = await model.ainvoke(state["ctx"], config)
        await deps.usage.record(
            session.device_id, cfg.llm_model or cfg.llm_provider, response.usage_metadata
        )
        return {"messages": [response]}

    async def guard_node(state: AgentState) -> dict[str, Any]:
        # M3 adds risk tiers + confirmation here; M0 enforces the loop guards.
        last = state["messages"][-1]
        assert isinstance(last, AIMessage)
        calls = last.tool_calls
        total = state.get("tool_calls_this_turn", 0) + len(calls)
        recent = [*state.get("recent_calls", []), *(_signature(c) for c in calls)]
        apology = None
        if total > cfg.max_tool_calls_per_turn:
            apology = LIMIT_APOLOGY
        elif any(recent.count(s) >= cfg.identical_call_limit for s in recent):
            apology = LOOP_APOLOGY
        if apology is None:
            return {"tool_calls_this_turn": total, "recent_calls": recent}
        aborted = [
            ToolMessage(
                content='{"ok":false,"error":{"code":"denied","message":"loop guard"}}',
                tool_call_id=c["id"],
                name=c["name"],
            )
            for c in calls
        ]
        return {"messages": [*aborted, AIMessage(content=apology)], "abort": True}

    async def tools_node(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
        session = _session(deps, config)
        last = state["messages"][-1]
        assert isinstance(last, AIMessage)
        enabled = {t.name for t in enabled_tools(session.info.programs)}
        tctx = ToolContext(bridge=session.bridge, timeout_s=cfg.tool_timeout_s)
        results: list[ToolMessage] = []
        for call in last.tool_calls:  # UI actions are sequential by nature
            outcome = await execute_tool(call["name"], call["args"], tctx, enabled)
            if outcome.ok:
                body: dict[str, Any] = {"ok": True, "data": outcome.data}
            else:
                body = {
                    "ok": False,
                    "error": {"code": outcome.error_code, "message": outcome.error_message},
                }
            results.append(
                ToolMessage(
                    content=orjson.dumps(body).decode(),
                    tool_call_id=call["id"],
                    name=call["name"],
                )
            )
        return {"messages": results}

    def after_llm(state: AgentState) -> str:
        last = state["messages"][-1]
        return "guard_node" if isinstance(last, AIMessage) and last.tool_calls else END

    def after_guard(state: AgentState) -> str:
        return END if state.get("abort") else "tools_node"

    g = StateGraph(AgentState)
    g.add_node("context_node", context_node)
    g.add_node("llm_node", llm_node)
    g.add_node("guard_node", guard_node)
    g.add_node("tools_node", tools_node)
    g.set_entry_point("context_node")
    g.add_edge("context_node", "llm_node")
    g.add_conditional_edges("llm_node", after_llm, {"guard_node": "guard_node", END: END})
    g.add_conditional_edges("guard_node", after_guard, {"tools_node": "tools_node", END: END})
    g.add_edge("tools_node", "context_node")
    return g.compile(checkpointer=checkpointer)
