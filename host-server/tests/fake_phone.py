"""Scripted fake device: hello -> user_text -> answer tool calls -> print the streamed reply.

    python -m tests.fake_phone [--url ws://127.0.0.1:8765/ws] [--token T] [--text "..."] [--cancel]

`--cancel` sends `cancel` as soon as the first assistant_delta arrives.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys

import websockets

from app import protocol as p

CANNED_TREE = {
    "pkg": "com.example.home",
    "hash": "a1b2c3",
    "cols": ["ref", "cls", "id", "text", "flags"],
    "rows": [
        [1, "Txt", "clock", "12:30", ""],
        [2, "Btn", "search_btn", "Search", "c"],
        [3, "Edit", "address", "", "ef"],
    ],
}


async def run(url: str, token: str, text: str, cancel: bool, quiet: bool = False) -> int:
    log = (lambda *a: None) if quiet else (lambda *a: print(*a, flush=True))
    async with websockets.connect(url, max_size=512 * 1024) as ws:
        hello = p.Hello(
            token=token,
            device_id="fake-phone",
            app_version="0.1.0",
            programs=["core", "eyes"],
            screen=p.Screen(w=1080, h=2400, dpi=420),
        )
        await ws.send(p.encode(hello))
        await ws.send(p.encode(p.UserText(text=text)))
        sent_cancel = False
        async for raw in ws:
            msg = p.decode(raw, allowed=p.SERVER_MESSAGES)
            if isinstance(msg, p.HelloAck):
                log(f"[ack] tools={msg.tools_enabled}")
            elif isinstance(msg, p.NaviState):
                log(f"[state] {msg.state}")
            elif isinstance(msg, p.AssistantDelta):
                log(f"[delta] {msg.text!r}")
                if cancel and not sent_cancel:
                    sent_cancel = True
                    log("[fake_phone] sending cancel")
                    await ws.send(p.encode(p.Cancel()))
            elif isinstance(msg, p.ToolCall):
                log(f"[tool_call] {msg.name} {msg.args}")
                result = p.ToolResult(call_id=msg.call_id, ok=True, data=CANNED_TREE)
                await ws.send(p.encode(result))
            elif isinstance(msg, p.ToolCancel):
                log(f"[tool_cancel] {msg.call_id}")
            elif isinstance(msg, p.AssistantDone):
                log(f"[done] cancelled={msg.cancelled} text={msg.text!r}")
                return 0
            elif isinstance(msg, p.ErrorMsg):
                log(f"[error] {msg.code}: {msg.message}")
                return 1
    return 1


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--url", default="ws://127.0.0.1:8765/ws")
    ap.add_argument("--token", default=os.environ.get("AUTH_TOKEN", ""))
    ap.add_argument("--text", default="what is on my screen?")
    ap.add_argument("--cancel", action="store_true")
    a = ap.parse_args()
    sys.exit(asyncio.run(run(a.url, a.token, a.text, a.cancel)))


if __name__ == "__main__":
    main()
