"""End-to-end through the real FastAPI app, LangGraph agent and fake LLM (no network, no keys)."""

from __future__ import annotations

import contextlib

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app import protocol as p
from app.main import create_app

CANNED_TREE = {
    "pkg": "com.example.home",
    "hash": "h1",
    "cols": ["ref", "cls", "text"],
    "rows": [[1, "Txt", "Hello"]],
}


def hello(token: str = "secret-token", device: str = "dev1", programs=("core", "eyes")) -> p.Hello:
    return p.Hello(
        token=token, device_id=device, app_version="0.1.0", programs=list(programs),
        screen=p.Screen(w=1080, h=2400, dpi=420),
    )  # fmt: skip


class Phone:
    def __init__(self, ws) -> None:
        self.ws = ws

    def send(self, payload: p.Payload) -> None:
        self.ws.send_bytes(p.encode(payload))

    def recv(self) -> p.Payload:
        return p.decode(self.ws.receive_bytes(), allowed=p.SERVER_MESSAGES)

    def recv_until(self, kind: type[p.Payload]) -> list[p.Payload]:
        seen: list[p.Payload] = []
        while True:
            msg = self.recv()
            seen.append(msg)
            if isinstance(msg, kind):
                return seen


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings)) as c:
        yield c


@contextlib.contextmanager
def connect(client: TestClient, **kw):
    with client.websocket_connect("/ws") as ws:
        phone = Phone(ws)
        phone.send(hello(**kw))
        yield phone


def test_hello_ack_and_tools(client):
    with connect(client) as phone:
        ack = phone.recv()
        assert isinstance(ack, p.HelloAck)
        assert ack.config.delta_flush_ms == 20
        assert ack.tools_enabled == ["wait", "ask_confirmation", "read_ui_tree", "find_elements"]


def test_bad_token_closes_4401(client):
    with client.websocket_connect("/ws") as ws:
        ws.send_bytes(p.encode(hello(token="nope")))
        with pytest.raises(WebSocketDisconnect) as ei:
            ws.receive_bytes()
        assert ei.value.code == p.CLOSE_BAD_TOKEN


def test_client_too_old_closes_4426(client):
    old = hello()
    old.app_version = "0.0.1"
    with client.websocket_connect("/ws") as ws:
        ws.send_bytes(p.encode(old))
        with pytest.raises(WebSocketDisconnect) as ei:
            ws.receive_bytes()
        assert ei.value.code == p.CLOSE_CLIENT_TOO_OLD


def test_ping_pong(client):
    with connect(client) as phone:
        phone.recv()
        phone.send(p.Ping(next_s=120))
        assert isinstance(phone.recv(), p.Pong)


def test_plain_turn_streams_reply(client):
    with connect(client) as phone:
        phone.recv()
        phone.send(p.UserText(text="hello there"))
        msgs = phone.recv_until(p.AssistantDone)
        states = [m.state for m in msgs if isinstance(m, p.NaviState)]
        deltas = [m for m in msgs if isinstance(m, p.AssistantDelta)]
        done = msgs[-1]
        assert states[:2] == ["processing", "talking"]
        assert len(deltas) >= 2  # coalesced, but streamed in more than one flush
        assert "".join(d.text for d in deltas) == done.text
        assert not done.cancelled
        assert isinstance(phone.recv(), p.NaviState)  # idle


def test_screen_turn_calls_tool_then_replies(client):
    with connect(client) as phone:
        phone.recv()
        phone.send(p.UserText(text="what's on my screen?"))
        msgs = phone.recv_until(p.ToolCall)
        call = msgs[-1]
        assert call.name == "read_ui_tree" and call.deadline_ms == 2000
        phone.send(p.ToolResult(call_id=call.call_id, ok=True, data=CANNED_TREE))
        rest = phone.recv_until(p.AssistantDone)
        assert rest[-1].text.startswith("I can see your screen")


def test_tool_timeout_is_reported_to_llm_not_fatal(client):
    with connect(client) as phone:
        phone.recv()
        phone.send(p.UserText(text="screen please"))
        call = phone.recv_until(p.ToolCall)[-1]
        # never answer: deadline (2 s) elapses -> tool_cancel, then the turn still completes
        rest = phone.recv_until(p.AssistantDone)
        assert any(isinstance(m, p.ToolCancel) and m.call_id == call.call_id for m in rest)


def test_cancel_mid_stream_ends_turn(client):
    with connect(client) as phone:
        phone.recv()
        phone.send(p.UserText(text="tell me something"))
        phone.recv_until(p.AssistantDelta)
        phone.send(p.Cancel())
        msgs = phone.recv_until(p.AssistantDone)
        assert msgs[-1].cancelled is True
        assert isinstance(phone.recv(), p.NaviState)
        # the conversation is still usable afterwards
        phone.send(p.UserText(text="again"))
        assert phone.recv_until(p.AssistantDone)[-1].cancelled is False


def test_cancel_during_pending_tool_call(client):
    with connect(client) as phone:
        phone.recv()
        phone.send(p.UserText(text="read the screen"))
        call = phone.recv_until(p.ToolCall)[-1]
        phone.send(p.Cancel())
        msgs = phone.recv_until(p.AssistantDone)
        assert any(isinstance(m, p.ToolCancel) and m.call_id == call.call_id for m in msgs)
        assert msgs[-1].cancelled is True
        # history was repaired (dangling tool call closed): next turn works
        phone.recv()
        phone.send(p.UserText(text="hi"))
        assert phone.recv_until(p.AssistantDone)[-1].cancelled is False


def test_second_connection_replaces_first_with_4409(client):
    with connect(client) as phone1:
        phone1.recv()
        with connect(client) as phone2:
            assert isinstance(phone2.recv(), p.HelloAck)
            with pytest.raises(WebSocketDisconnect) as ei:
                phone1.recv()
            assert ei.value.code == p.CLOSE_REPLACED


def test_user_text_while_busy_is_rejected(client):
    with connect(client) as phone:
        phone.recv()
        phone.send(p.UserText(text="one"))
        phone.recv_until(p.AssistantDelta)
        phone.send(p.UserText(text="two"))
        msgs = phone.recv_until(p.ErrorMsg)
        assert msgs[-1].code == "busy"


def test_health_and_metrics_local_only(client):
    assert client.get("/health").json()["ok"] is True
    assert client.get("/metrics").status_code == 404  # TestClient peer is not 127.0.0.1


def test_usage_recorded(client):
    with connect(client) as phone:
        phone.recv()
        phone.send(p.UserText(text="hello"))
        phone.recv_until(p.AssistantDone)
    totals = client.portal.call(client.app.state.usage.totals)
    assert totals["calls"] >= 1 and totals["output_tokens"] > 0
