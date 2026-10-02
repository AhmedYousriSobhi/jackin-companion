"""IntentRouter: no-LLM fast path (timers, open app, back/home). Implemented in M5."""

from __future__ import annotations


class IntentRouter:
    def route(self, _text: str) -> None:
        """Return None until M5: every request goes to the agent."""
        return None
