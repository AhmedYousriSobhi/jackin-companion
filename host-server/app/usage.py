"""UsageMeter: per-call token accounting into SQLite (spec §5.3). Budgets are no-ops until M5."""

from __future__ import annotations

import time
from typing import Any

import aiosqlite

_SCHEMA = """
CREATE TABLE IF NOT EXISTS usage (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  model TEXT NOT NULL,
  input_tokens INTEGER NOT NULL,
  output_tokens INTEGER NOT NULL,
  cache_read_tokens INTEGER NOT NULL DEFAULT 0
)
"""


class UsageMeter:
    def __init__(self, path: str) -> None:
        self._path = path
        self._db: aiosqlite.Connection | None = None

    async def open(self) -> None:
        self._db = await aiosqlite.connect(self._path)
        await self._db.execute("PRAGMA journal_mode=WAL")
        await self._db.execute(_SCHEMA)
        await self._db.commit()

    async def close(self) -> None:
        if self._db:
            await self._db.close()
            self._db = None

    async def record(
        self, device_id: str, model: str, usage_metadata: dict[str, Any] | None
    ) -> None:
        if not usage_metadata or self._db is None:
            return
        details = usage_metadata.get("input_token_details") or {}
        await self._db.execute(
            "INSERT INTO usage (ts, device_id, model, input_tokens, output_tokens,"
            " cache_read_tokens) VALUES (?, ?, ?, ?, ?, ?)",
            (
                int(time.time()),
                device_id,
                model,
                int(usage_metadata.get("input_tokens", 0)),
                int(usage_metadata.get("output_tokens", 0)),
                int(details.get("cache_read", 0)),
            ),
        )
        await self._db.commit()

    async def totals(self) -> dict[str, int]:
        if self._db is None:
            return {"calls": 0, "input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0}
        async with self._db.execute(
            "SELECT COUNT(*), COALESCE(SUM(input_tokens),0), COALESCE(SUM(output_tokens),0),"
            " COALESCE(SUM(cache_read_tokens),0) FROM usage"
        ) as cur:
            row = await cur.fetchone()
        assert row is not None
        return {
            "calls": row[0],
            "input_tokens": row[1],
            "output_tokens": row[2],
            "cache_read_tokens": row[3],
        }

    def over_budget(self, _device_id: str) -> bool:
        """Daily/turn budgets are enforced in M5; always False for now."""
        return False
