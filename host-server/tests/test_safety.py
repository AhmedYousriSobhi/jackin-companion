from __future__ import annotations

from app import safety
from app.agent.tools import BY_NAME


def test_every_tool_has_a_tier():
    assert set(BY_NAME) <= set(safety.TOOL_TIERS)


def test_static_tiers():
    assert safety.classify("read_ui_tree") == "low"
    assert safety.classify("click_element") == "medium"
    assert safety.classify("reply_notification") == "high"


def test_sensitive_package_forces_high():
    assert safety.classify("scroll", foreground_package="com.android.vending") == "high"
    assert safety.classify("scroll", foreground_package="com.example.notes") == "low"


def test_keyword_on_target_forces_high():
    assert safety.classify("click_element", target_text=("Send",)) == "high"
    assert safety.classify("click_element", target_text=(None, "btn_pay")) == "high"
    assert safety.classify("click_element", target_text=("Cancel",)) == "medium"


def test_llm_can_only_raise():
    assert safety.classify("scroll", llm_label="high") == "high"
    assert safety.classify("reply_notification", llm_label="low") == "high"
