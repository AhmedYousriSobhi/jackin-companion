#!/usr/bin/env bash
# Expose the localhost-only host server to the tailnet as wss://<host>.<tailnet>.ts.net/ws.
# TLS is terminated by tailscale; uvicorn keeps listening on 127.0.0.1 only.
set -euo pipefail

PORT="${PORT:-8765}"

command -v tailscale >/dev/null || { echo "tailscale CLI not found" >&2; exit 1; }

tailscale serve --bg --https=443 "http://127.0.0.1:${PORT}"
tailscale serve status

name="$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
echo
echo "Phone host URL:  wss://${name}/ws"
echo "Stop sharing:    tailscale serve reset"
