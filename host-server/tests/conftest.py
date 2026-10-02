from __future__ import annotations

import pytest

from app.config import Settings


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        _env_file=None,
        auth_token="secret-token",
        llm_provider="fake",
        checkpoint_db=str(tmp_path / "checkpoints.sqlite"),
        usage_db=str(tmp_path / "usage.sqlite"),
        fake_token_delay_ms=40,
        delta_flush_ms=20,
        tool_timeout_s=2.0,
    )
