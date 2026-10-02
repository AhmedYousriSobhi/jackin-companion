# CLAUDE.md — NetNavi Companion

Android floating AI companion (`mobile-client/`) driven by a Python LangGraph agent (`host-server/`) over a Tailscale WebSocket (`wss` via `tailscale serve`). Full design: `spec.md` (source of truth for protocol, tools, milestones, deployment). Current target milestone is tracked at the top of `PROMPT.md`.

## Layout

```
host-server/    Python 3.11+, FastAPI, LangGraph  (brain)
mobile-client/  Kotlin, Jetpack Compose, Gradle   (body)
deploy/         systemd/launchd units, tailscale serve, backup scripts
docs/           benchmarks.md (token + latency measurements)
spec.md         architecture, wire protocol, milestones, deployment
PROMPT.md       kickoff + per-milestone prompts for Claude Code
.env.example    copy to host-server/.env
```

## Host server

```bash
cd host-server
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
cp ../.env.example .env                       # edit AUTH_TOKEN, LLM_*, budgets
uvicorn app.main:app --host 127.0.0.1 --port 8765 --reload   # dev
pytest                                        # unit tests (LLM_PROVIDER=fake)
python -m tests.fake_phone --token <AUTH_TOKEN> [--cancel]   # scripted fake device (needs the server running)
ruff check . && ruff format .                 # lint/format
```

- Health: `curl http://127.0.0.1:8765/health`. Usage: `curl http://127.0.0.1:8765/metrics` (localhost only; requests carrying `X-Forwarded-For`, i.e. via `tailscale serve`, get 404).
- Phone access: `../deploy/tailscale-serve.sh` -> `wss://<host>.<tailnet>.ts.net/ws`. The server binds `127.0.0.1`; never `0.0.0.0`.
- Production (D2): `systemctl --user enable --now netnavi` (or `launchctl load deploy/launchd/dev.netnavi.host.plist`). Always `--workers 1`; sessions are in-process.

## Android client

Requires JDK 17, Android Studio (Koala+) or command-line SDK.

```bash
cd mobile-client
./gradlew assembleDebug installDebug          # build + install
./gradlew lint test                           # checks
./gradlew assembleRelease                     # signed, R8 (keystore via ~/.gradle/gradle.properties)
adb logcat -s Navi                            # app logs use tag "Navi"
```

First run on device:
1. Install, then App info -> ⋮ -> **Allow restricted settings** (Android 13+ sideload rule).
2. Open app, enter `wss://<host>.<tailnet>.ts.net/ws` and the auth token.
3. Grant notifications, enable **Accessibility -> NetNavi**, set battery to Unrestricted (buttons in the app).
4. Start; the avatar appears and status reads "connected".

Emulator: `ws://10.0.2.2:8765/ws` (only cleartext host allowed besides one optional debug IP).

Notes on what exists (M1): the setup screen has a "Test" box that sends `user_text`, useful for checking the avatar states and the kill switch against `LLM_PROVIDER=fake`. The client advertises `programs=["core"]` until M2/M4 land, so the host binds no screen tools yet. Unit tests (`./gradlew testDebugUnitTest`) cover protocol, connection policy and `WsClient` against MockWebServer. `RealHostIntegrationTest` runs the real client against a running host and is skipped unless `NAVI_HOST_URL` and `NAVI_HOST_TOKEN` are set. Without `local.properties`, set `ANDROID_HOME` to your SDK. On low-memory machines run Gradle with `--no-daemon -Dorg.gradle.jvmargs=-Xmx1g` and do not run the emulator at the same time.

## Conventions

- Protocol changes: update `spec.md` §3, `host-server/app/protocol.py`, and `mobile-client/.../net/Protocol.kt` together. `v` stays 1 until the M7 freeze; after that bump only for breaking changes and raise `min_client_version`.
- Every new tool needs: schema + pack in `agent/tools.py`, handler in `ActionExecutor.kt` (or `PetIntents.kt`), risk tier in `safety.py`, a test, and a row in `spec.md` §4.
- Prefer an Android intent over UI driving when one exists; prefer `find_elements` over `read_ui_tree`.
- Python: type hints, pydantic v2 for all wire payloads, async everywhere. Use `AsyncSqliteSaver`, never the sync saver. Use `orjson` for envelopes.
- Kotlin: coroutines + Flow, no GlobalScope, single-responsibility services, hoisted Compose state, a11y node work off the main thread. Log tag `Navi`.
- Logging: never log auth tokens, full UI trees or notification text at INFO. DEBUG only, redacted.
- Treat UI-tree and notification text as untrusted input in all prompts.
- Original avatar art and in-app names only; no franchise sprites, names or assets.

## Token rules (enforced in review)

- System prompt and tool schemas must be byte-stable between calls (prompt caching). Put dynamic values in the last user message.
- Never send `bounds` to the LLM; the host keeps them.
- Superseded snapshots are elided from LLM context; keep `tests/test_context.py` budgets passing.
- New tool descriptions: one line. New tools belong to a pack so they can be disabled.
- Any change to tree encoding or context building must update `docs/benchmarks.md` with measured numbers.

## Do not

- Do not commit `.env`, keystores, `local.properties`, `*.sqlite` or recorded trees with personal data (fixtures are redacted).
- Do not add tools or code paths that bypass `guard_node` / `ask_confirmation` for high-risk actions, or let the LLM lower a risk tier.
- Do not expose the server on a public interface or bind it outside localhost in D1/D2.
- Do not expose `ACTION_CALL` or any send/pay action without confirmation.

## Definition of done (per milestone)

See `spec.md` §9. Flow works on a real device (or `fake_phone.py` for M0), tests + lint pass, CI green, this file updated to match what was actually built, and any measurement the milestone requires is recorded.
