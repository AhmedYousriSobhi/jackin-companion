"""Tool registry (spec §4): pydantic arg schemas, packs, one-line descriptions.

Remote tools go through the RemoteToolBridge; `wait` and `ask_confirmation` run on the server.
Anything the phone has not implemented yet returns `unsupported` (M2-M4 stubs on the Android side).
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app import protocol as p
from app.bridge import RemoteToolBridge, ToolOutcome


class _Args(BaseModel):
    model_config = ConfigDict(extra="forbid")


class WaitArgs(_Args):
    ms: int = Field(ge=0, le=5000)


class AskConfirmationArgs(_Args):
    summary: str


class ReadUiTreeArgs(_Args):
    max_nodes: int | None = Field(default=None, ge=1, le=250)
    since: str | None = None


class FindElementsArgs(_Args):
    text: str | None = None
    view_id: str | None = None
    cls: str | None = None
    limit: int = Field(default=5, ge=1, le=5)


class OpenAppArgs(_Args):
    package_name: str | None = None
    app_label: str | None = None


class ClickElementArgs(_Args):
    ref: int | None = None
    view_id: str | None = None
    text: str | None = None
    observe: bool = False


class TypeTextArgs(_Args):
    text: str
    ref: int | None = None
    clear_first: bool = False
    observe: bool = False


class ScrollArgs(_Args):
    direction: Literal["up", "down", "left", "right"]
    ref: int | None = None
    observe: bool = False


class SwipeArgs(_Args):
    x1: int
    y1: int
    x2: int
    y2: int
    duration_ms: int = Field(default=300, ge=50, le=3000)


class GlobalActionArgs(_Args):
    action: Literal["back", "home", "recents", "notifications"]


class EmptyArgs(_Args):
    pass


class SetAlarmArgs(_Args):
    hour: int = Field(ge=0, le=23)
    minute: int = Field(ge=0, le=59)
    label: str | None = None
    days: list[int] | None = None


class SetTimerArgs(_Args):
    seconds: int = Field(ge=1)
    label: str | None = None


class CreateEventArgs(_Args):
    title: str
    start: str
    end: str | None = None
    location: str | None = None


class DialNumberArgs(_Args):
    number: str


class ComposeMessageArgs(_Args):
    to: str | None = None
    text: str
    app: str | None = None


class ListNotificationsArgs(_Args):
    package: str | None = None
    limit: int = Field(default=10, ge=1, le=10)


class ReplyNotificationArgs(_Args):
    key: str
    text: str


class ScheduleReminderArgs(_Args):
    at: str
    text: str


class RunChipArgs(_Args):
    chip_id: str


class SweepScanArgs(_Args):
    scope: Literal["screen", "notifications", "apps"]


Handler = Callable[[dict[str, Any], "ToolContext"], Awaitable[ToolOutcome]]


@dataclass
class ToolContext:
    bridge: RemoteToolBridge
    timeout_s: float


@dataclass(frozen=True)
class ToolSpec:
    name: str
    pack: str
    description: str
    args_model: type[BaseModel]
    since: str
    server_side: bool = False

    def llm_schema(self) -> dict[str, Any]:
        """Terse JSON schema for bind_tools: pydantic `title` noise stripped to save tokens."""
        params = _strip_titles(self.args_model.model_json_schema())
        return {"name": self.name, "description": self.description, "parameters": params}


def _strip_titles(node: Any) -> Any:
    if isinstance(node, dict):
        return {k: _strip_titles(v) for k, v in node.items() if k != "title"}
    if isinstance(node, list):
        return [_strip_titles(v) for v in node]
    return node


def _t(name, pack, desc, model, since, server_side=False) -> ToolSpec:  # noqa: ANN001
    return ToolSpec(name, pack, desc, model, since, server_side)


# Order is the bind order and must stay stable (prompt caching).
TOOLS: tuple[ToolSpec, ...] = (
    _t("wait", "core", "Pause up to 5000 ms.", WaitArgs, "M0", True),
    _t(
        "ask_confirmation",
        "core",
        "Ask the user to approve an action.",
        AskConfirmationArgs,
        "M3",
        True,
    ),
    _t("read_ui_tree", "eyes", "Read the current screen as compact rows.", ReadUiTreeArgs, "M2"),
    _t("find_elements", "eyes", "Find up to 5 matching screen elements.", FindElementsArgs, "M2"),
    _t("open_app", "hands", "Open an app by package or label.", OpenAppArgs, "M4"),
    _t(
        "click_element",
        "hands",
        "Click an element by ref, view_id or text.",
        ClickElementArgs,
        "M4",
    ),
    _t("type_text", "hands", "Type text into a field.", TypeTextArgs, "M4"),
    _t("scroll", "hands", "Scroll the screen or an element.", ScrollArgs, "M4"),
    _t("swipe", "hands", "Swipe between two screen points.", SwipeArgs, "M4"),
    _t(
        "global_action",
        "hands",
        "Press back, home, recents or open notifications.",
        GlobalActionArgs,
        "M4",
    ),
    _t("device_status", "pet", "Get battery, network, DND and time.", EmptyArgs, "M8"),
    _t("set_alarm", "pet", "Set an alarm.", SetAlarmArgs, "M8"),
    _t("set_timer", "pet", "Start a timer.", SetTimerArgs, "M8"),
    _t("create_event", "pet", "Open a prefilled calendar event.", CreateEventArgs, "M8"),
    _t("dial_number", "pet", "Open the dialer with a number.", DialNumberArgs, "M8"),
    _t("compose_message", "pet", "Open a prefilled message composer.", ComposeMessageArgs, "M8"),
    _t(
        "list_notifications",
        "pet",
        "List recent allowed notifications.",
        ListNotificationsArgs,
        "M8",
    ),
    _t("reply_notification", "pet", "Reply to a notification.", ReplyNotificationArgs, "M8"),
    _t("schedule_reminder", "pet", "Schedule a spoken reminder.", ScheduleReminderArgs, "M8", True),
    _t("run_chip", "chips", "Replay a saved chip.", RunChipArgs, "M9"),
    _t("sweep_scan", "sweep", "Scan for phishing and scam patterns.", SweepScanArgs, "M9"),
)

BY_NAME: dict[str, ToolSpec] = {t.name: t for t in TOOLS}
ALL_PACKS: tuple[str, ...] = ("core", "eyes", "hands", "pet", "chips", "sweep")


def enabled_tools(
    device_programs: list[str], server_packs: tuple[str, ...] = ALL_PACKS
) -> list[ToolSpec]:
    """Tools bound to the LLM: `core` always, plus packs the device advertises and the server allows."""
    packs = {"core"} | (set(device_programs) & set(server_packs))
    return [t for t in TOOLS if t.pack in packs]


async def _wait(args: dict[str, Any], _ctx: ToolContext) -> ToolOutcome:
    await asyncio.sleep(args["ms"] / 1000)
    return ToolOutcome(ok=True, data={})


async def _unsupported(_args: dict[str, Any], _ctx: ToolContext) -> ToolOutcome:
    return ToolOutcome.failure(p.ERR_UNSUPPORTED, "not implemented yet")


_SERVER_HANDLERS: dict[str, Handler] = {
    "wait": _wait,
    "ask_confirmation": _unsupported,  # M3
    "schedule_reminder": _unsupported,  # M8
}


async def execute_tool(
    name: str, raw_args: dict[str, Any], ctx: ToolContext, enabled: set[str]
) -> ToolOutcome:
    """Validate args, then run the tool server-side or via the phone bridge."""
    spec = BY_NAME.get(name)
    if spec is None or name not in enabled:
        return ToolOutcome.failure(p.ERR_UNSUPPORTED, f"unknown tool {name!r}")
    try:
        args = spec.args_model.model_validate(raw_args).model_dump(exclude_none=True)
    except ValidationError as exc:
        return ToolOutcome.failure("bad_args", exc.errors()[0].get("msg", "invalid arguments"))
    if spec.server_side:
        return await _SERVER_HANDLERS[name](args, ctx)
    return await ctx.bridge.call(name, args, ctx.timeout_s)
