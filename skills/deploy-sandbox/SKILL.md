---
name: deploy-sandbox
description: Install the Nubian Python controller into an Ubuntu desktop sandbox so the Java agent can drive it. Works with happysixd/osworld-docker on GCP, Hetzner, or local Docker. Run when a fresh Ubuntu desktop boots but /agent/* endpoints return 502/404.
---

# Install the Nubian sandbox controller

## When to use
- You ran `gcloud-spot-sandbox.sh up` (or `docker compose up sandbox`) and the noVNC desktop renders, but `curl http://<host>:<port>/agent/health` returns 404 or 502.
- The OSWorld container is up but `/agent/eyes/screenshot` doesn't exist yet.

## What it does
Uploads 9 controller files + 6 systemd unit files into the guest VM, runs the install (apt + pip + systemctl enable + start), and verifies `127.0.0.1:6090/health` responds with `"ok": true`.

## How to run

```bash
# 1) Copy the controller package to the sandbox host
gcloud compute scp --recurse deployment/sandbox-controller <vm-name>:/tmp/    # GCP
# or:
scp -r deployment/sandbox-controller user@<host>:/tmp/                        # any SSH host

# 2) From the host, run the installer (uses OSWorld /setup/upload + /execute)
bash scripts/install-into-guest.sh <osworld-api-url>
# example: bash scripts/install-into-guest.sh http://127.0.0.1:5000
```

The script:
1. Posts every `.py` + `.sh` + `.service` to `/setup/upload` (puts them in `/home/user/.local/share/nubian-universal-computer-agent/` and `/home/user/.config/systemd/user/`).
2. Posts `install-command.sh` to `/execute` (apt-installs xdotool, wmctrl, xinput, at-spi2, etc., pips pyautogui/duckdb/python-xlib, enables + starts the 6 systemd user units, calls `/health` to verify).

## After installing
The controller answers on the guest's `127.0.0.1:6090` only — it's not yet reachable from outside the container. See [`wire-agent`](../wire-agent/) for the nginx `/agent/` proxy patch and the port-advertisement fix.

## References
- [`references/file-list.md`](references/file-list.md) — the exact set of files installed
- [`references/troubleshooting.md`](references/troubleshooting.md) — common install failures
