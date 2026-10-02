"""DeviceSession + registry (spec §3 close codes, §7 heartbeat)."""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from fastapi import WebSocket

from app import protocol as p
from app.bridge import RemoteToolBridge

log = logging.getLogger("navi.session")


@dataclass
class DeviceInfo:
    device_id: str
    app_version: str
    programs: list[str]
    capabilities: list[str]
    locale: str = "en"
    tz: str = "UTC"
    foreground_package: str | None = None


class DeviceSession:
    def __init__(
        self,
        ws: WebSocket,
        hello: p.Hello,
        *,
        tool_timeout_s: float,
        ping_s: int,
        missed_pings: int,
    ) -> None:
        self.session_id = uuid.uuid4().hex
        self.ws = ws
        self.info = DeviceInfo(
            device_id=hello.device_id,
            app_version=hello.app_version,
            programs=list(hello.programs),
            capabilities=list(hello.capabilities),
            locale=hello.locale,
            tz=hello.tz,
        )
        self.bridge = RemoteToolBridge(self.send, tool_timeout_s)
        self.turn_task: asyncio.Task[Any] | None = None
        self.turn_id: str | None = None
        self.conversation_id = uuid.uuid4().hex[:8]
        self.last_activity = time.monotonic()
        self.last_seen = time.monotonic()
        self.ping_s = ping_s
        self.next_ping_s = ping_s
        self.missed_pings = missed_pings
        self.closed = False
        self._send_lock = asyncio.Lock()

    @property
    def device_id(self) -> str:
        return self.info.device_id

    @property
    def thread_id(self) -> str:
        return f"{self.device_id}:{self.conversation_id}"

    async def send(self, payload: p.Payload) -> None:
        if self.closed:
            raise ConnectionError("session closed")
        async with self._send_lock:
            await self.ws.send_bytes(p.encode(payload))

    def touch(self, next_s: int | None = None) -> None:
        self.last_seen = time.monotonic()
        if next_s:
            self.next_ping_s = max(next_s, 1)

    def heartbeat_expired(self) -> bool:
        return time.monotonic() - self.last_seen > self.next_ping_s * self.missed_pings

    def roll_conversation_if_idle(self, idle_min: int) -> None:
        """Start a new conversation after `idle_min` of silence (summary folding lands in M5)."""
        now = time.monotonic()
        if now - self.last_activity > idle_min * 60:
            self.conversation_id = uuid.uuid4().hex[:8]
        self.last_activity = now

    async def close(self, code: int, reason: str = "") -> None:
        if self.closed:
            return
        self.closed = True
        try:
            await self.ws.close(code=code, reason=reason)
        except Exception:
            pass


class SessionRegistry:
    def __init__(self) -> None:
        self._sessions: dict[str, DeviceSession] = {}

    def get(self, device_id: str) -> DeviceSession | None:
        return self._sessions.get(device_id)

    def __len__(self) -> int:
        return len(self._sessions)

    async def register(self, session: DeviceSession) -> None:
        """Install `session`; an existing one for the same device is closed with 4409."""
        old = self._sessions.get(session.device_id)
        self._sessions[session.device_id] = session
        if old is not None:
            log.info("replacing session for device %s", session.device_id)
            old.bridge.fail_all(p.ERR_DEVICE_DISCONNECTED, "replaced by newer connection")
            if old.turn_task and not old.turn_task.done():
                old.turn_task.cancel()
            # carry the conversation over so the new socket continues the same thread
            session.conversation_id = old.conversation_id
            await old.close(p.CLOSE_REPLACED, "replaced")

    def unregister(self, session: DeviceSession) -> None:
        if self._sessions.get(session.device_id) is session:
            del self._sessions[session.device_id]


@dataclass
class Stats:
    started: float = field(default_factory=time.time)
    turns: int = 0
