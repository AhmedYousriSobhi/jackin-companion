from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration; every field maps to an env var of the same upper-case name."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore", case_sensitive=False)

    host_bind: str = "127.0.0.1"
    port: int = 8765
    auth_token: str = ""

    llm_provider: str = "fake"  # fake | anthropic | openai | ollama
    llm_model: str = ""
    llm_model_fast: str = ""
    llm_max_output_tokens: int = 512
    anthropic_api_key: str = ""
    openai_api_key: str = ""
    ollama_base_url: str = "http://127.0.0.1:11434"
    ollama_keep_alive: str = "30m"
    prompt_cache: bool = True
    fake_token_delay_ms: int = 20

    tool_timeout_s: float = 10.0
    max_tool_calls_per_turn: int = 15
    identical_call_limit: int = 3
    context_max_tokens: int = 12000
    turn_max_tokens: int = 40000
    daily_token_budget: int = 300000
    conversation_idle_min: int = 30
    delta_flush_ms: int = 50
    ws_max_message_kb: int = 512
    ping_s: int = 25
    missed_pings: int = 3

    checkpoint_db: str = "./checkpoints.sqlite"
    usage_db: str = "./usage.sqlite"
    log_level: str = "INFO"

    server_version: str = "0.1.0"
    min_client_version: str = "0.1.0"
