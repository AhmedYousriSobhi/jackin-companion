"""guard_node loop protections (spec §5.1) using a fake LLM that never stops calling tools."""

from __future__ import annotations

import itertools

import pytest
from fastapi.testclient import TestClient

from app import protocol as p
from app.agent import llm as llm_mod
from app.agent.prompts import LIMIT_APOLOGY, LOOP_APOLOGY
from app.main import create_app
from tests.test_e2e import connect

_COUNTER = itertools.count(1)


class Looping(llm_mod.FakeChatModel):
    vary: bool = False

    def _wants_tree(self, messages) -> bool:
        return True

    def _tool_call(self):
        call = super()._tool_call()
        if self.vary:
            call["args"] = {"max_nodes": next(_COUNTER)}
        return call


def run_until_done(phone, answer) -> p.AssistantDone:
    while True:
        msg = phone.recv()
        if isinstance(msg, p.ToolCall):
            answer(phone, msg)
        elif isinstance(msg, p.AssistantDone):
            return msg


def ok(phone, call: p.ToolCall) -> None:
    phone.send(p.ToolResult(call_id=call.call_id, ok=True, data={}))


@pytest.mark.parametrize(
    ("vary", "apology"), [(False, LOOP_APOLOGY), (True, LIMIT_APOLOGY)], ids=["identical", "limit"]
)
def test_loop_guards(settings, monkeypatch, vary, apology):
    monkeypatch.setattr("app.main.build_llm", lambda _s: Looping(vary=vary))
    settings.max_tool_calls_per_turn = 4
    with TestClient(create_app(settings)) as client, connect(client) as phone:
        phone.recv()
        phone.send(p.UserText(text="look at the screen forever"))
        done = run_until_done(phone, ok)
        assert done.text == apology
        assert not done.cancelled
        # the thread is still healthy afterwards (aborted calls were closed with tool results)
        phone.recv()
        phone.send(p.UserText(text="again"))
        assert run_until_done(phone, ok).text == apology
