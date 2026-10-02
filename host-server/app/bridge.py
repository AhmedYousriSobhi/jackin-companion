"""RemoteToolBridge: LangGraph tools await a Future keyed by call_id (spec §2, decision 3)."""

from __future__ import annotations

import asyncio
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from app import protocol as p


@dataclass
class ToolOutcome:
    ok: bool
    data: Any = None
    error_code: str | None = None
    error_message: str = ""

    @classmethod
    def failure(cls, code: str, message: str = "") -> ToolOutcome:
        return cls(ok=False, error_code=code, error_message=message)


SendFn = Callable[[p.Payload], Awaitable[None]]


class RemoteToolBridge:
    def __init__(self, send: SendFn, default_timeout_s: float = 10.0) -> None:
        self._send = send
        self._timeout = default_timeout_s
        self._pending: dict[str, asyncio.Future[ToolOutcome]] = {}

    @property
    def pending_ids(self) -> list[str]:
        return list(self._pending)

    async def call(
        self, name: str, args: dict[str, Any], timeout_s: float | None = None
    ) -> ToolOutcome:
        timeout = self._timeout if timeout_s is None else timeout_s
        call_id = uuid.uuid4().hex
        fut: asyncio.Future[ToolOutcome] = asyncio.get_running_loop().create_future()
        self._pending[call_id] = fut
        try:
            await self._send(
                p.ToolCall(call_id=call_id, name=name, args=args, deadline_ms=int(timeout * 1000))
            )
            return await asyncio.wait_for(fut, timeout)
        except TimeoutError:
            await self._safe_cancel(call_id)
            return ToolOutcome.failure(p.ERR_TIMEOUT, f"{name} timed out after {timeout:g}s")
        except asyncio.CancelledError:
            # The turn task was cancelled: tell the phone to abort, then propagate.
            await self._safe_cancel(call_id)
            raise
        except Exception:  # send failed -> socket is gone
            return ToolOutcome.failure(p.ERR_DEVICE_DISCONNECTED, "device unreachable")
        finally:
            self._pending.pop(call_id, None)

    def resolve(self, result: p.ToolResult) -> bool:
        """Complete the Future for a phone `tool_result`. Unknown/late call_ids are ignored."""
        fut = self._pending.get(result.call_id)
        if fut is None or fut.done():
            return False
        if result.ok:
            fut.set_result(ToolOutcome(ok=True, data=result.data))
        else:
            err = result.error or p.ToolError(code="unknown")
            fut.set_result(ToolOutcome.failure(err.code, err.message))
        return True

    def fail_all(self, code: str, message: str = "") -> int:
        """Fail every in-flight call (e.g. `device_disconnected`). No messages are sent."""
        n = 0
        for fut in self._pending.values():
            if not fut.done():
                fut.set_result(ToolOutcome.failure(code, message))
                n += 1
        return n

    async def cancel_all(self) -> int:
        """Fail all pending futures with `cancelled` and send `tool_cancel` for each."""
        ids = [cid for cid, f in self._pending.items() if not f.done()]
        for cid in ids:
            self._pending[cid].set_result(ToolOutcome.failure(p.ERR_CANCELLED, "cancelled"))
            await self._safe_cancel(cid)
        return len(ids)

    async def _safe_cancel(self, call_id: str) -> None:
        try:
            await self._send(p.ToolCancel(call_id=call_id))
        except Exception:
            pass


def monotonic_ms() -> int:
    return int(time.monotonic() * 1000)
