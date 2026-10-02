"""Risk tiers and lists (spec §6). Enforcement is wired into guard_node in M3."""

from __future__ import annotations

import re
from typing import Literal

Tier = Literal["low", "medium", "high"]
_ORDER: dict[str, int] = {"low": 0, "medium": 1, "high": 2}

# Static tier per tool (spec §4). `reply_notification` is always high.
TOOL_TIERS: dict[str, Tier] = {
    "wait": "low",
    "ask_confirmation": "low",
    "read_ui_tree": "low",
    "find_elements": "low",
    "open_app": "low",
    "click_element": "medium",
    "type_text": "medium",
    "scroll": "low",
    "swipe": "medium",
    "global_action": "low",
    "device_status": "low",
    "set_alarm": "medium",
    "set_timer": "low",
    "create_event": "medium",
    "dial_number": "medium",
    "compose_message": "medium",
    "list_notifications": "low",
    "reply_notification": "high",
    "schedule_reminder": "low",
    "run_chip": "medium",
    "sweep_scan": "low",
}

# Foreground packages that force `high` (banking/payments/password managers/installers/Settings).
SENSITIVE_PACKAGES: frozenset[str] = frozenset(
    {
        "com.android.vending",
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.revolut.revolut",
        "com.x8bit.bitwarden",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.google.android.apps.authenticator2",
        "org.keepassdroid",
        "com.kunzisoft.keepass.free",
    }
)

# Action keywords checked against a target node's text / desc / view_id (en + de; extend freely).
ACTION_KEYWORDS: tuple[str, ...] = (
    "send", "pay", "buy", "delete", "remove", "install", "allow", "grant", "confirm",
    "transfer", "subscribe",
    "senden", "bezahlen", "kaufen", "löschen", "entfernen", "installieren", "erlauben",
    "bestätigen", "überweisen", "abonnieren",
)  # fmt: skip

_KEYWORD_RE = re.compile(
    r"(?<![a-z])(" + "|".join(re.escape(k) for k in ACTION_KEYWORDS) + r")", re.IGNORECASE
)


def max_tier(*tiers: Tier) -> Tier:
    return max(tiers, key=lambda t: _ORDER[t])


def is_sensitive_package(package: str | None) -> bool:
    return bool(package) and package in SENSITIVE_PACKAGES


def matches_action_keyword(*fields: str | None) -> bool:
    return any(f and _KEYWORD_RE.search(f) for f in fields)


def classify(
    tool: str,
    *,
    foreground_package: str | None = None,
    target_text: tuple[str | None, ...] = (),
    llm_label: Tier | None = None,
) -> Tier:
    """tier = max(tool rule, package rule, keyword rule, LLM label). The LLM can only raise."""
    tiers: list[Tier] = [TOOL_TIERS.get(tool, "medium")]
    if is_sensitive_package(foreground_package) and tool not in ("wait", "ask_confirmation"):
        tiers.append("high")
    if matches_action_keyword(*target_text):
        tiers.append("high")
    if llm_label:
        tiers.append(llm_label)
    return max_tier(*tiers)
