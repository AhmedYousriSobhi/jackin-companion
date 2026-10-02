# Prompts for Claude Code

**Current target: M0 + M1** (update this line as milestones close).

Paste the kickoff into Claude Code from an empty repo root containing `spec.md`, `CLAUDE.md` and this file. Later milestones each have a short prompt below; paste one at a time after the previous milestone is done.

---

## Kickoff (M0 + M1, stubs for M2–M4)

Read `spec.md` (rev 2) and `CLAUDE.md` fully before writing anything. They are the source of truth; if something in them looks wrong, tell me before deviating.

Get **M0 and M1** fully working. Add compiling stubs (return `unsupported`) for every M2–M4 tool on both sides so the protocol and tool registry are complete from day one.

### Host server (`host-server/`)
1. `pyproject.toml` (Python 3.11+): fastapi, uvicorn[standard], websockets, orjson, langgraph, langgraph-checkpoint-sqlite, aiosqlite, langchain-core, langchain-anthropic, langchain-openai, langchain-ollama, pydantic v2, pydantic-settings; dev: pytest, pytest-asyncio, ruff.
2. Files from spec §8: `config.py`, `protocol.py` (all §3 messages incl. `cancel`, `tool_cancel`, `hello_ack.config`), `session.py` (registry, `4409` replacement, missed-ping drop), `bridge.py` (Future per call_id, timeout, fail-fast on disconnect, cancel-all), `safety.py` (tier table, sensitive packages, keyword lists — enforcement wiring comes in M3), `usage.py` (record `usage_metadata`; budgets can be no-ops for now), `router.py` (empty stub), `agent/{graph,context,tools,prompts,llm}.py`, `main.py` (`/ws`, `/health`, `/metrics` on localhost).
3. LangGraph per spec §5.1: `context_node -> llm_node -> guard_node -> tools_node` loop, **AsyncSqliteSaver** with WAL, thread id `device_id:conversation_id`, max 15 tool calls, identical-call guard, `astream_events(version="v2")` with deltas coalesced every `delta_flush_ms`, `navi_state` transitions, task cancellation on `cancel`.
4. Tools from spec §4 with pydantic arg schemas and packs; remote ones go through `RemoteToolBridge`; `wait` and `ask_confirmation` are server-side. One-line descriptions.
5. `LLM_PROVIDER=fake`: deterministic fake that, for a prompt containing "screen", emits one `read_ui_tree` call, then a fixed streamed reply. M0 must run with no API key.
6. Tests: `tests/test_protocol.py` (round-trip every message type), `tests/test_bridge.py` (timeout, disconnect fail-fast, cancel), `tests/fake_phone.py` (hello -> user_text -> answer `read_ui_tree` with a canned compact tree -> print streamed reply; `--cancel` flag sends `cancel` mid-turn).

### Android client (`mobile-client/`)
1. Gradle Kotlin DSL + version catalog. minSdk 26, compile/target latest stable, Compose BOM, OkHttp, kotlinx-serialization-json, coroutines, DataStore.
2. Manifest: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE (+ `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`), POST_NOTIFICATIONS; accessibility service with `accessibility_service_config.xml` (`canRetrieveWindowContent`, `canPerformGestures`, report view ids, eventTypes limited to window state/content changed, `notificationTimeout` 300). `network_security_config.xml`: cleartext only for `10.0.2.2` (CIDR is not supported — do not try `100.64.0.0/10`); production uses `wss` via `tailscale serve`.
3. `MainActivity` (Compose): host URL + token in DataStore; buttons for Accessibility settings, App info (restricted settings hint), Battery settings; Start/Stop; live connection status.
4. `WsClient` + `ConnectionPolicy`: envelopes matching §3, `hello` with screen/locale/tz, single app heartbeat (25 s screen on / 120 s screen off, OkHttp pingInterval off), backoff 1–30 s full jitter, reconnect on network-available, StateFlow state, SharedFlow inbound, call_id LRU dedupe.
5. `NaviForegroundService` (type `specialUse`) owns `WsClient` and the notification (with a Stop action); `NaviBus` bridges to the accessibility service.
6. `NaviAccessibilityService`: `TYPE_ACCESSIBILITY_OVERLAY` with a `ComposeView` and proper Lifecycle/SavedState/ViewModelStore owners; lifecycle to STOPPED when screen off. Draggable. Long-press = kill switch (sends `cancel`, refuses tool calls with `kill_switch`, tap to re-arm). `NaviAvatar`: idle/processing/talking/disconnected drawn with Canvas, original design, animations via `graphicsLayer`.
7. `UiTreeSerializer` and `ActionExecutor`: stubs that compile and return `unsupported`, with the compact row data classes from spec §5.3 already defined.

### Config, CI, docs
- `.env.example`: `HOST_BIND=127.0.0.1`, `PORT=8765`, `AUTH_TOKEN`, `LLM_PROVIDER=fake`, `LLM_MODEL`, `LLM_MODEL_FAST`, `LLM_MAX_OUTPUT_TOKENS=512`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `OLLAMA_BASE_URL`, `OLLAMA_KEEP_ALIVE=30m`, `PROMPT_CACHE=true`, `TOOL_TIMEOUT_S=10`, `MAX_TOOL_CALLS_PER_TURN=15`, `CONTEXT_MAX_TOKENS=12000`, `TURN_MAX_TOKENS=40000`, `DAILY_TOKEN_BUDGET=300000`, `CONVERSATION_IDLE_MIN=30`, `DELTA_FLUSH_MS=50`, `WS_MAX_MESSAGE_KB=512`, `CHECKPOINT_DB=./checkpoints.sqlite`, `USAGE_DB=./usage.sqlite`, `LOG_LEVEL=INFO`.
- `.gitignore` for both stacks (+ `*.sqlite`, keystores). `.github/workflows/ci.yml` per spec §11. `deploy/tailscale-serve.sh`.
- Keep `CLAUDE.md` accurate to what you built.

### Working agreement
- Order: server + fake_phone (M0) -> Android connect + avatar + kill switch (M1) -> stubs.
- After each step run what can run (pytest, ruff, `./gradlew assembleDebug` if an SDK exists) and report honestly, including what you could not verify.
- Nothing beyond M1 functionality yet (no tree reading, actions, voice, vision).
- Finish with the manual phone + laptop checklist to test the bridge over `tailscale serve`.

---

## M2 — Eyes
Implement `read_ui_tree` and `find_elements` per spec §4/§5.3: compact rows, short class names, stripped view-id prefixes, flag letters, wrapper-chain collapse, 80-char text, ≤250 nodes, password/`FLAG_SECURE` redaction, `since` hash. Serialize off the main thread. Host: `context.py` renders rows for the LLM without bounds and elides superseded snapshots. Add 5+ redacted fixtures in `tests/fixtures/trees/` and `test_context.py` asserting a token ceiling per fixture. Report the measured token counts.

## M3 — Safety gate
Wire `guard_node`: tier = max(package rule, keyword rule on target node, LLM label); LLM can only raise. High tier -> `confirm_request`, overlay `ConfirmSheet` with 30 s auto-deny, result gates the exact call_id. Return `blind_window` / `secure_window`. Tests: no high-risk call executes without approval, lowered-tier attempts are ignored, injected screen text cannot skip the gate.

## M4 — Hands
Implement click (action -> ancestor -> gesture), type (`ACTION_SET_TEXT`, `clear_first`), scroll, swipe, global_action, open_app (package or label lookup), all with `observe`. `stale_ref` recovery test. Demo: Settings -> Display -> dark mode, with confirmation.

## M5 — Efficiency
Rolling summary + conversation windowing, Anthropic `cache_control` / stable prefix, tool packs bound from `hello.programs`, `IntentRouter` for timers/alarms/open-app/back/home, `CONTEXT_MAX_TOKENS`/`TURN_MAX_TOKENS`/`DAILY_TOKEN_BUDGET` enforcement with fast-model fallback, permessage-deflate check, `/metrics`. Android: idle animation frame budget, screen-off pause, a11y event filtering. Write `docs/benchmarks.md` with before/after tokens per turn, cache-hit rate, p50/p95 turn latency, overnight idle battery drain.

## M6 — Voice
`SpeechRecognizer` push-to-talk on avatar, `TextToSpeech` on `assistant_done`, mic FGS type added only during a user-started session, tap interrupts TTS and sends `cancel`.

## M7 — Production v1.0
Execute spec §11 D2: systemd/launchd units, `tailscale serve`, ACL snippet in docs, release signing, R8 + baseline profile, versionCode from git tag, `min_client_version` + `4426`, backup script + tested restore, log rotation, update procedure. Freeze protocol v1. Produce a release checklist and tag `v1.0.0`.

## M8 — PET functions (v1.1)
`pet` pack per spec §4: alarms, timers, calendar, dial, compose, device_status, notification inbox (opt-in allow-list, `NotificationListenerService`), `reply_notification` (high), `schedule_reminder` -> `navi_say`, offline mode, QS tile + widget, `navi_emotion` rules. None of these may read the UI tree.

## M9 — Chips & Programs (v1.2)
Chip folder (≤25), record from a successful turn's tool sequence, drag onto avatar, deterministic replay by `view_id` with LLM fallback, every step still passes `guard_node`. Programs screen toggling packs/persona. Link mode (app scope lock). Sweep scan with local heuristics first. Record chip replay token savings in `docs/benchmarks.md`.
