from __future__ import annotations

import asyncio

import pytest

from app import protocol as p
from app.bridge import RemoteToolBridge


class Wire:
    def __init__(self) -> None:
        self.sent: list[p.Payload] = []

    async def send(self, payload: p.Payload) -> None:
        self.sent.append(payload)

    def of(self, kind: type[p.Payload]) -> list:
        return [m for m in self.sent if isinstance(m, kind)]


async def _first_call(wire: Wire) -> p.ToolCall:
    for _ in range(100):
        if wire.of(p.ToolCall):
            return wire.of(p.ToolCall)[0]
        await asyncio.sleep(0.005)
    raise AssertionError("no tool_call sent")


async def test_resolve_ok():
    wire = Wire()
    bridge = RemoteToolBridge(wire.send, 1)
    task = asyncio.create_task(bridge.call("read_ui_tree", {}))
    call = await _first_call(wire)
    assert call.deadline_ms == 1000
    assert bridge.resolve(p.ToolResult(call_id=call.call_id, ok=True, data={"x": 1}))
    out = await task
    assert out.ok and out.data == {"x": 1}
    assert bridge.pending_ids == []


async def test_error_result_maps_code():
    wire = Wire()
    bridge = RemoteToolBridge(wire.send, 1)
    task = asyncio.create_task(bridge.call("click_element", {}))
    call = await _first_call(wire)
    bridge.resolve(
        p.ToolResult(call_id=call.call_id, ok=False, error=p.ToolError(code="stale_ref"))
    )
    out = await task
    assert not out.ok and out.error_code == p.ERR_STALE_REF


async def test_timeout_sends_tool_cancel():
    wire = Wire()
    bridge = RemoteToolBridge(wire.send, 0.05)
    out = await bridge.call("read_ui_tree", {})
    assert out.error_code == p.ERR_TIMEOUT
    assert [c.call_id for c in wire.of(p.ToolCancel)] == [wire.of(p.ToolCall)[0].call_id]
    assert bridge.pending_ids == []


async def test_late_result_after_timeout_is_ignored():
    wire = Wire()
    bridge = RemoteToolBridge(wire.send, 0.05)
    await bridge.call("read_ui_tree", {})
    late = p.ToolResult(call_id=wire.of(p.ToolCall)[0].call_id, ok=True)
    assert bridge.resolve(late) is False


async def test_disconnect_fails_fast():
    wire = Wire()
    bridge = RemoteToolBridge(wire.send, 30)
    tasks = [asyncio.create_task(bridge.call("read_ui_tree", {})) for _ in range(3)]
    for _ in range(100):
        if len(wire.of(p.ToolCall)) == 3:
            break
        await asyncio.sleep(0.005)
    assert bridge.fail_all(p.ERR_DEVICE_DISCONNECTED) == 3
    outs = await asyncio.wait_for(asyncio.gather(*tasks), 1)
    assert all(o.error_code == p.ERR_DEVICE_DISCONNECTED for o in outs)
    assert wire.of(p.ToolCancel) == []  # socket is gone; nothing to send


async def test_send_failure_reports_device_disconnected():
    async def broken(_: p.Payload) -> None:
        raise ConnectionError

    out = await RemoteToolBridge(broken, 1).call("read_ui_tree", {})
    assert out.error_code == p.ERR_DEVICE_DISCONNECTED


async def test_cancel_all_resolves_and_notifies_phone():
    wire = Wire()
    bridge = RemoteToolBridge(wire.send, 30)
    task = asyncio.create_task(bridge.call("read_ui_tree", {}))
    call = await _first_call(wire)
    assert await bridge.cancel_all() == 1
    out = await asyncio.wait_for(task, 1)
    assert out.error_code == p.ERR_CANCELLED
    assert [c.call_id for c in wire.of(p.ToolCancel)] == [call.call_id]


async def test_task_cancellation_notifies_phone_and_propagates():
    wire = Wire()
    bridge = RemoteToolBridge(wire.send, 30)
    task = asyncio.create_task(bridge.call("read_ui_tree", {}))
    call = await _first_call(wire)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert [c.call_id for c in wire.of(p.ToolCancel)] == [call.call_id]
    assert bridge.pending_ids == []
