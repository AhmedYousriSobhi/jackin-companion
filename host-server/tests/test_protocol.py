from __future__ import annotations

import orjson
import pytest

from app import protocol as p

SAMPLES: dict[type[p.Payload], dict] = {
    p.Hello: {
        "token": "t", "device_id": "d1", "app_version": "0.1.0", "capabilities": ["a11y"],
        "programs": ["core", "eyes"], "screen": {"w": 1080, "h": 2400, "dpi": 420},
        "locale": "en-US", "tz": "Europe/Berlin",
    },
    p.UserText: {"text": "hi", "source": "typed"},
    p.Cancel: {"turn_id": "abc"},
    p.ForegroundApp: {"package": "com.example", "activity": ".Main"},
    p.ToolResult: {"call_id": "c1", "ok": False, "error": {"code": "timeout", "message": "x"}},
    p.ConfirmResult: {"call_id": "c1", "approved": True},
    p.Notification: {"key": "k", "package": "com.x", "title": "t", "text": "b", "actions": ["Reply"]},
    p.Ping: {"next_s": 120},
    p.HelloAck: {
        "session_id": "s", "server_version": "0.1.0", "min_client_version": "0.1.0",
        "config": {"ping_s": 25, "delta_flush_ms": 50}, "tools_enabled": ["wait"],
    },
    p.NaviState: {"state": "talking"},
    p.NaviEmotion: {"emotion": "happy"},
    p.AssistantDelta: {"turn_id": "t1", "text": "hel"},
    p.AssistantDone: {"turn_id": "t1", "text": "hello", "cancelled": True},
    p.NaviSay: {"text": "hey", "reason": "reminder"},
    p.ToolCall: {"call_id": "c1", "name": "read_ui_tree", "args": {"since": "h"}, "deadline_ms": 10000},
    p.ToolCancel: {"call_id": "c1"},
    p.ConfirmRequest: {"call_id": "c1", "summary": "Pay?", "risk": "high", "reason": "keyword"},
    p.ErrorMsg: {"code": "busy", "message": "m"},
    p.Pong: {},
}  # fmt: skip


def test_every_message_type_has_a_sample():
    assert set(SAMPLES) == set(p.ALL_MESSAGES.values())


@pytest.mark.parametrize("model", list(SAMPLES), ids=lambda m: m.__name__)
def test_round_trip(model):
    original = model.model_validate(SAMPLES[model])
    wire = p.encode(original)
    env = orjson.loads(wire)
    assert env["v"] == 1 and env["type"] and isinstance(env["ts"], int) and env["id"]
    assert p.decode(wire) == original


def test_decode_rejects_bad_input():
    for raw, code in [
        (b"not json", "bad_envelope"),
        (orjson.dumps({"v": 2, "type": "ping", "payload": {}}), "bad_version"),
        (orjson.dumps({"v": 1, "type": "nope", "payload": {}}), "unknown_type"),
        (orjson.dumps({"v": 1, "type": "user_text", "payload": {}}), "bad_payload"),
    ]:
        with pytest.raises(p.ProtocolError) as ei:
            p.decode(raw)
        assert ei.value.code == code


def test_direction_filter():
    wire = p.encode(p.Pong())
    with pytest.raises(p.ProtocolError):
        p.decode(wire, allowed=p.CLIENT_MESSAGES)
