"""FastAPI entrypoint: /ws (phone link), /health, /metrics (localhost only)."""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import logging
import re
from contextlib import asynccontextmanager

import aiosqlite
from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from app import __version__
from app import protocol as p
from app.agent.graph import AgentDeps, build_graph
from app.agent.llm import build_llm
from app.agent.runner import run_turn
from app.agent.tools import enabled_tools
from app.config import Settings
from app.session import DeviceSession, SessionRegistry, Stats
from app.usage import UsageMeter

log = logging.getLogger("navi")

HELLO_TIMEOUT_S = 10


async def _recv(ws: WebSocket) -> bytes | str:
    event = await ws.receive()
    if event["type"] == "websocket.disconnect":
        raise WebSocketDisconnect(event.get("code", 1000))
    return event.get("bytes") or event.get("text") or b""


def _version_tuple(v: str) -> tuple[int, ...]:
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3]) or (0,)


def create_app(settings: Settings | None = None) -> FastAPI:
    cfg = settings or Settings()
    logging.basicConfig(
        level=cfg.log_level.upper(), format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if not cfg.auth_token:
            raise RuntimeError("AUTH_TOKEN is not set (copy .env.example to .env)")
        conn = await aiosqlite.connect(cfg.checkpoint_db)
        await conn.execute("PRAGMA journal_mode=WAL")
        saver = AsyncSqliteSaver(conn)
        await saver.setup()
        usage = UsageMeter(cfg.usage_db)
        await usage.open()
        registry = SessionRegistry()
        deps = AgentDeps(settings=cfg, registry=registry, usage=usage, llm=build_llm(cfg))
        app.state.cfg = cfg
        app.state.registry = registry
        app.state.usage = usage
        app.state.deps = deps
        app.state.graph = build_graph(deps, saver)
        app.state.stats = Stats()
        try:
            yield
        finally:
            for s in list(registry._sessions.values()):  # noqa: SLF001
                await s.close(p.CLOSE_SHUTDOWN, "server shutdown")
            await usage.close()
            await conn.close()

    app = FastAPI(title="NetNavi host", version=__version__, lifespan=lifespan)

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True, "version": cfg.server_version, "sessions": len(app.state.registry)}

    @app.get("/metrics")
    async def metrics(request: Request) -> dict:
        # `tailscale serve` proxies from 127.0.0.1 too; it always adds X-Forwarded-For.
        local = request.client is not None and request.client.host in ("127.0.0.1", "::1")
        if not local or "x-forwarded-for" in request.headers:
            raise HTTPException(status_code=404)
        return {
            "sessions": len(app.state.registry),
            "turns": app.state.stats.turns,
            "usage": await app.state.usage.totals(),
        }

    @app.websocket("/ws")
    async def ws_endpoint(ws: WebSocket) -> None:
        await ws.accept()
        session = await _handshake(ws, cfg, app.state.registry)
        if session is None:
            return
        monitor = asyncio.create_task(_heartbeat_monitor(session))
        try:
            await _serve(ws, session, app)
        except WebSocketDisconnect:
            pass
        finally:
            monitor.cancel()
            await _teardown(session, app.state.registry)

    return app


async def _handshake(
    ws: WebSocket, cfg: Settings, registry: SessionRegistry
) -> DeviceSession | None:
    try:
        raw = await asyncio.wait_for(_recv(ws), HELLO_TIMEOUT_S)
        msg = p.decode(raw, allowed=p.CLIENT_MESSAGES)
    except (TimeoutError, p.ProtocolError, WebSocketDisconnect):
        await ws.close(code=1002)
        return None
    if not isinstance(msg, p.Hello):
        await ws.close(code=1002)
        return None
    if not hmac.compare_digest(msg.token.encode(), cfg.auth_token.encode()):
        await ws.close(code=p.CLOSE_BAD_TOKEN, reason="bad token")
        return None
    if _version_tuple(msg.app_version) < _version_tuple(cfg.min_client_version):
        await ws.close(code=p.CLOSE_CLIENT_TOO_OLD, reason="client too old")
        return None
    session = DeviceSession(
        ws, msg, tool_timeout_s=cfg.tool_timeout_s, ping_s=cfg.ping_s, missed_pings=cfg.missed_pings
    )
    await registry.register(session)
    tools = [t.name for t in enabled_tools(session.info.programs)]
    await session.send(
        p.HelloAck(
            session_id=session.session_id,
            server_version=cfg.server_version,
            min_client_version=cfg.min_client_version,
            config=p.HelloConfig(ping_s=cfg.ping_s, delta_flush_ms=cfg.delta_flush_ms),
            tools_enabled=tools,
        )
    )
    log.info("device %s connected (app %s)", session.device_id, msg.app_version)
    return session


async def _heartbeat_monitor(session: DeviceSession) -> None:
    while not session.closed:
        await asyncio.sleep(max(1, session.next_ping_s / 2))
        if session.heartbeat_expired():
            log.info("device %s missed %d pings; dropping", session.device_id, session.missed_pings)
            await session.close(1011, "heartbeat timeout")
            return


async def _serve(ws: WebSocket, session: DeviceSession, app: FastAPI) -> None:
    cfg: Settings = app.state.cfg
    max_bytes = cfg.ws_max_message_kb * 1024
    while True:
        raw = await _recv(ws)
        if len(raw) > max_bytes:
            await session.send(p.ErrorMsg(code="too_large", message="message too large"))
            continue
        try:
            msg = p.decode(raw, allowed=p.CLIENT_MESSAGES)
        except p.ProtocolError as exc:
            await session.send(p.ErrorMsg(code=exc.code, message=exc.message))
            continue
        session.touch()
        await _dispatch(msg, session, app)


async def _dispatch(msg: p.Payload, session: DeviceSession, app: FastAPI) -> None:
    if isinstance(msg, p.Ping):
        session.touch(msg.next_s)
        await session.send(p.Pong())
    elif isinstance(msg, p.UserText):
        if session.turn_task and not session.turn_task.done():
            await session.send(p.ErrorMsg(code="busy", message="a turn is already running"))
            return
        app.state.stats.turns += 1
        session.turn_task = asyncio.create_task(
            run_turn(app.state.deps, app.state.graph, session, msg.text)
        )
    elif isinstance(msg, p.Cancel):
        await _cancel_turn(session)
    elif isinstance(msg, p.ToolResult):
        session.bridge.resolve(msg)
    elif isinstance(msg, p.ForegroundApp):
        session.info.foreground_package = msg.package
    elif isinstance(msg, (p.ConfirmResult, p.Notification)):
        log.debug("%s ignored until its milestone", type(msg).__name__)
    # Hello after handshake is ignored.


async def _cancel_turn(session: DeviceSession) -> None:
    task = session.turn_task
    if task and not task.done():
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await task
    await session.bridge.cancel_all()


async def _teardown(session: DeviceSession, registry: SessionRegistry) -> None:
    session.closed = True
    session.bridge.fail_all(p.ERR_DEVICE_DISCONNECTED, "device disconnected")
    if session.turn_task and not session.turn_task.done():
        session.turn_task.cancel()
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await session.turn_task
    registry.unregister(session)
    log.info("device %s disconnected", session.device_id)


app = create_app()
