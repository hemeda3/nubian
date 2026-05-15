---
name: wire-agent
description: Wire the local Java agent to a remote Ubuntu sandbox where the Nubian controller is already installed. Patches container nginx to expose /agent/, aligns the controller's advertised public port with the actual Docker mapping, sets the agent's env vars, and verifies end-to-end. Run after deploy-sandbox.
---

# Wire the agent to a remote sandbox

## When to use
- `deploy-sandbox` finished and `127.0.0.1:6090/health` answers in the guest VM.
- You need the Java agent (running locally) to drive that sandbox.
- The noVNC iframe shows a black frame because the URL is wrong, or `/agent/health` returns 404.

## Two tricky alignments

Three port numbers must agree. Forks blow up when they don't.

| Layer | What | Default | Set how |
|-------|------|---------|---------|
| Docker mapping (container ↔ host) | `-p <HOST>:8006` | `8006:8006` (GCP) or `28006:8006` (Hetzner) | docker-compose.yml |
| Container nginx `/agent/` proxy → guest `:6090` | inline awk patch | not present | scripts/patch-nginx.sh |
| Controller `PUBLIC_AGENT_PORT` / `PUBLIC_VNC_PORT` | env in systemd unit | `28006` | scripts/patch-public-ports.sh |
| Java agent `NUBIAN_SANDBOX_COMPUTER_AGENT_NOVNC_PORT` | env on the laptop | `28006` | `.env` |

The last three **must equal the host-side Docker port** (`8006` on GCP, `28006` on Hetzner).

## Run

```bash
# 1. Add /agent/ → guest:6090 reverse-proxy in container nginx
bash scripts/patch-nginx.sh <vm-host> <container-name>
# e.g. for GCP: bash scripts/patch-nginx.sh nubian-sandbox-spot nubian-sandbox

# 2. Make the controller advertise the right public port
bash scripts/patch-public-ports.sh <vm-host> 8006
# (use 28006 if your docker-compose maps :28006 instead)

# 3. Wire the local agent
cat >> .env <<'EOF'
NUBIAN_SANDBOX_COMPUTER_AGENT_HOST=<vm-external-ip>
NUBIAN_SANDBOX_COMPUTER_AGENT_AGENT_PORT=8006
NUBIAN_SANDBOX_COMPUTER_AGENT_BASE_PATH=/agent
NUBIAN_SANDBOX_COMPUTER_AGENT_NOVNC_PORT=8006
EOF
./start.sh
```

## Verify

```bash
curl -fsS http://<vm-external-ip>:8006/agent/health | jq .ok
# expect: true

open http://localhost:7070/demo/computer
# noVNC iframe should show the 1024×1024 Ubuntu desktop, no black frame
```

## References
- [`references/port-matrix.md`](references/port-matrix.md) — every port that has to align, by deployment target
- [`references/nginx-block.conf`](references/nginx-block.conf) — the exact location block the patch script injects
