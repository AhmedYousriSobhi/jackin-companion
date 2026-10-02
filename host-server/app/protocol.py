"""Wire protocol v1 (spec §3): envelope + typed payload models."""

from __future__ import annotations

import time
import uuid
from typing import Any, Literal

import orjson
from pydantic import BaseModel, ConfigDict, Field, ValidationError

PROTOCOL_VERSION = 1

# Close codes
CLOSE_BAD_TOKEN = 4401
CLOSE_REPLACED = 4409
CLOSE_CLIENT_TOO_OLD = 4426
CLOSE_SHUTDOWN = 1001

# tool_result.error.code values
ERR_STALE_REF = "stale_ref"
ERR_NOT_FOUND = "not_found"
ERR_BLIND_WINDOW = "blind_window"
ERR_SECURE_WINDOW = "secure_window"
ERR_KILL_SWITCH = "kill_switch"
ERR_CANCELLED = "cancelled"
ERR_TIMEOUT = "timeout"
ERR_DEVICE_DISCONNECTED = "device_disconnected"
ERR_UNSUPPORTED = "unsupported"
ERR_DENIED = "denied"


class Payload(BaseModel):
    model_config = ConfigDict(extra="ignore")


class Envelope(BaseModel):
    v: int = PROTOCOL_VERSION
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    type: str
    ts: int = Field(default_factory=lambda: int(time.time() * 1000))
    payload: dict[str, Any] = Field(default_factory=dict)


class ToolError(Payload):
    code: str
    message: str = ""


class Screen(Payload):
    w: int
    h: int
    dpi: int


# ---- client -> server -------------------------------------------------------------------


class Hello(Payload):
    token: str
    device_id: str
    app_version: str
    capabilities: list[str] = []
    programs: list[str] = []
    screen: Screen
    locale: str = "en"
    tz: str = "UTC"


class UserText(Payload):
    text: str
    source: Literal["typed", "voice", "chip"] = "typed"


class Cancel(Payload):
    turn_id: str | None = None


class ForegroundApp(Payload):
    package: str
    activity: str | None = None


class ToolResult(Payload):
    call_id: str
    ok: bool
    data: Any | None = None
    error: ToolError | None = None


class ConfirmResult(Payload):
    call_id: str
    approved: bool


class Notification(Payload):
    key: str
    package: str
    title: str = ""
    text: str = ""
    actions: list[str] = []


class Ping(Payload):
    # Seconds until the next ping; lets the server scale its missed-ping deadline when the
    # phone slows down with the screen off (spec §7).
    next_s: int | None = None


# ---- server -> client -------------------------------------------------------------------


class HelloConfig(Payload):
    ping_s: int
    delta_flush_ms: int


class HelloAck(Payload):
    session_id: str
    server_version: str
    min_client_version: str
    config: HelloConfig
    tools_enabled: list[str] = []


class NaviState(Payload):
    state: Literal["idle", "processing", "talking"]


class NaviEmotion(Payload):
    emotion: Literal["neutral", "happy", "worried", "sleepy", "alert"]


class AssistantDelta(Payload):
    turn_id: str
    text: str


class AssistantDone(Payload):
    turn_id: str
    text: str
    cancelled: bool = False


class NaviSay(Payload):
    text: str
    reason: Literal["reminder", "notification", "system"]


class ToolCall(Payload):
    call_id: str
    name: str
    args: dict[str, Any] = {}
    deadline_ms: int


class ToolCancel(Payload):
    call_id: str


class ConfirmRequest(Payload):
    call_id: str
    summary: str
    risk: Literal["low", "medium", "high"]
    reason: str = ""


class ErrorMsg(Payload):
    code: str
    message: str = ""


class Pong(Payload):
    pass


CLIENT_MESSAGES: dict[str, type[Payload]] = {
    "hello": Hello,
    "user_text": UserText,
    "cancel": Cancel,
    "foreground_app": ForegroundApp,
    "tool_result": ToolResult,
    "confirm_result": ConfirmResult,
    "notification": Notification,
    "ping": Ping,
}

SERVER_MESSAGES: dict[str, type[Payload]] = {
    "hello_ack": HelloAck,
    "navi_state": NaviState,
    "navi_emotion": NaviEmotion,
    "assistant_delta": AssistantDelta,
    "assistant_done": AssistantDone,
    "navi_say": NaviSay,
    "tool_call": ToolCall,
    "tool_cancel": ToolCancel,
    "confirm_request": ConfirmRequest,
    "error": ErrorMsg,
    "pong": Pong,
}

ALL_MESSAGES = {**CLIENT_MESSAGES, **SERVER_MESSAGES}
_TYPE_BY_MODEL = {m: t for t, m in ALL_MESSAGES.items()}


class ProtocolError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def encode(payload: Payload, *, msg_id: str | None = None) -> bytes:
    env = Envelope(
        type=_TYPE_BY_MODEL[type(payload)],
        payload=payload.model_dump(mode="json", exclude_none=True),
    )
    if msg_id:
        env.id = msg_id
    return orjson.dumps(env.model_dump(mode="json"))


def decode(raw: bytes | str, *, allowed: dict[str, type[Payload]] | None = None) -> Payload:
    """Parse one envelope into its typed payload. Raises ProtocolError on any problem."""
    table = allowed if allowed is not None else ALL_MESSAGES
    try:
        env = Envelope.model_validate(orjson.loads(raw))
    except (orjson.JSONDecodeError, ValidationError) as exc:
        raise ProtocolError("bad_envelope", "malformed envelope") from exc
    if env.v != PROTOCOL_VERSION:
        raise ProtocolError("bad_version", f"unsupported protocol version {env.v}")
    model = table.get(env.type)
    if model is None:
        raise ProtocolError("unknown_type", f"unknown message type {env.type!r}")
    try:
        return model.model_validate(env.payload)
    except ValidationError as exc:
        raise ProtocolError("bad_payload", f"invalid payload for {env.type}") from exc
