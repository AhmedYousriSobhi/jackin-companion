"""LLM provider factory and the deterministic fake used by M0 and tests."""

from __future__ import annotations

import asyncio
import json
import uuid
from collections.abc import AsyncIterator, Iterator, Sequence
from typing import Any

from langchain_core.callbacks import AsyncCallbackManagerForLLMRun, CallbackManagerForLLMRun
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import (
    AIMessage,
    AIMessageChunk,
    BaseMessage,
    HumanMessage,
    ToolMessage,
)
from langchain_core.outputs import ChatGeneration, ChatGenerationChunk, ChatResult

from app.config import Settings

FAKE_REPLY = "I can see your screen. It looks like a simple home screen with a few apps."


def _est_tokens(text: str) -> int:
    return max(1, len(text) // 4)


class FakeChatModel(BaseChatModel):
    """For a prompt containing "screen": one `read_ui_tree` call, then a fixed streamed reply."""

    token_delay_ms: int = 0
    reply: str = FAKE_REPLY

    @property
    def _llm_type(self) -> str:
        return "fake-navi"

    def bind_tools(self, tools: Sequence[Any], **_: Any) -> FakeChatModel:  # type: ignore[override]
        return self

    # -- decision ---------------------------------------------------------------------------
    def _wants_tree(self, messages: list[BaseMessage]) -> bool:
        last = messages[-1] if messages else None
        if isinstance(last, ToolMessage):
            return False
        humans = [m for m in messages if isinstance(m, HumanMessage)]
        return bool(humans) and "screen" in str(humans[-1].content).lower()

    def _usage(self, messages: list[BaseMessage], out: str) -> dict[str, int]:
        i = sum(_est_tokens(str(m.content)) for m in messages)
        o = _est_tokens(out)
        return {"input_tokens": i, "output_tokens": o, "total_tokens": i + o}

    def _tool_call(self) -> dict[str, Any]:
        return {"name": "read_ui_tree", "args": {}, "id": f"fake_{uuid.uuid4().hex[:8]}"}

    # -- non-streaming ----------------------------------------------------------------------
    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        if self._wants_tree(messages):
            msg = AIMessage(content="", tool_calls=[self._tool_call()])
        else:
            msg = AIMessage(content=self.reply)
        msg.usage_metadata = self._usage(messages, str(msg.content))  # type: ignore[assignment]
        return ChatResult(generations=[ChatGeneration(message=msg)])

    # -- streaming --------------------------------------------------------------------------
    def _stream(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> Iterator[ChatGenerationChunk]:
        raise NotImplementedError("FakeChatModel streams asynchronously only")

    async def _astream(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: AsyncCallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> AsyncIterator[ChatGenerationChunk]:
        if self._wants_tree(messages):
            call = self._tool_call()
            yield ChatGenerationChunk(
                message=AIMessageChunk(
                    content="",
                    tool_call_chunks=[
                        {
                            "name": call["name"],
                            "args": json.dumps(call["args"]),
                            "id": call["id"],
                            "index": 0,
                        }
                    ],
                    usage_metadata=self._usage(messages, ""),  # type: ignore[arg-type]
                )
            )
            return
        words = self.reply.split(" ")
        for i, word in enumerate(words):
            if self.token_delay_ms:
                await asyncio.sleep(self.token_delay_ms / 1000)
            text = word + (" " if i < len(words) - 1 else "")
            chunk = AIMessageChunk(content=text)
            if i == len(words) - 1:
                chunk.usage_metadata = self._usage(messages, self.reply)  # type: ignore[assignment]
            yield ChatGenerationChunk(message=chunk)


def build_llm(settings: Settings, *, fast: bool = False) -> BaseChatModel:
    provider = settings.llm_provider.lower()
    model = settings.llm_model_fast if fast and settings.llm_model_fast else settings.llm_model
    if provider == "fake":
        return FakeChatModel(token_delay_ms=settings.fake_token_delay_ms)
    if provider == "anthropic":
        from langchain_anthropic import ChatAnthropic

        return ChatAnthropic(
            model=model,
            max_tokens=settings.llm_max_output_tokens,
            api_key=settings.anthropic_api_key or None,
            streaming=True,
        )
    if provider == "openai":
        from langchain_openai import ChatOpenAI

        return ChatOpenAI(
            model=model,
            max_completion_tokens=settings.llm_max_output_tokens,
            api_key=settings.openai_api_key or None,
            streaming=True,
            stream_usage=True,
        )
    if provider == "ollama":
        from langchain_ollama import ChatOllama

        return ChatOllama(
            model=model,
            base_url=settings.ollama_base_url,
            keep_alive=settings.ollama_keep_alive,
            num_predict=settings.llm_max_output_tokens,
        )
    raise ValueError(f"unknown LLM_PROVIDER {settings.llm_provider!r}")
