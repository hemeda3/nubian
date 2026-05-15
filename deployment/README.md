# Deployment

Three pieces need to be running before the agent can do anything:

1. **Sandbox** — the Ubuntu desktop the agent drives (any flavor)
2. **Grounder** — a GPU serving UI-TARS-1.7B so target descriptions become `(x, y)` pixel coordinates
3. **Planner LLM** — Gemini / OpenRouter / vLLM that picks each next action

The Java app (`./start.sh`) talks to all three over HTTP. Below is what to deploy where, with no secrets in the repo.

---

## Quick chooser

| Use case | Sandbox | Grounder | Guide |
|----------|---------|----------|-------|
| First-time local test, Linux x86 host | local Docker | local Docker / remote | [GROUNDER_DEPLOY.md](GROUNDER_DEPLOY.md) |
| First-time local test, Apple Silicon Mac | remote (Hetzner / GCP) | remote | [GCLOUD_DEPLOY.md](GCLOUD_DEPLOY.md) (sandbox) + [GROUNDER_DEPLOY.md](GROUNDER_DEPLOY.md) (grounder) |
| Persistent demo | GCP on-demand or Hetzner | rented GPU on-demand | both guides |
| Quick spot / cheap | GCP spot VM (`gcloud-spot-sandbox.sh`) | spot GPU | both guides |

---

## What to set, what NEVER to commit

Two files hold all your runtime configuration. **Both are already in `.gitignore`.** Never `git add` them.

### `.env` — shell-side env vars (read by `start.sh`)

Copy-paste this template, fill in your own values:

```bash
# LLM provider (at least one)
GEMINI_API_KEY=               # direct Google AI Studio key (AIza...)
GCP_API_KEY=                  # same as GEMINI_API_KEY, alias
OPENROUTER_API_KEY=           # sk-or-v1-... if you prefer OpenRouter

# Sandbox — point at your running controller
NUBIAN_SANDBOX_COMPUTER_AGENT_HOST=        # e.g. 1.2.3.4
NUBIAN_SANDBOX_COMPUTER_AGENT_AGENT_PORT=  # default 6090, set 28006 if behind nginx
NUBIAN_SANDBOX_COMPUTER_AGENT_BASE_PATH=   # default /agent

# Grounder — point at your GPU running UI-TARS
NUBIAN_UGROUND_BASE_URL=      # e.g. http://1.2.3.4:9090/v1
NUBIAN_UGROUND_MODEL=         # model id reported by the grounder

# Optional: FlyVM provider (only if you're using firecracker microVMs)
NUBIAN_FLYVM_API_BASE=        # e.g. http://1.2.3.4:19191
NUBIAN_FLYVM_STATIC_VM_ID=    # uuid of a pre-provisioned VM
FLYVM_JWT_SECRET=             # shared secret for FlyVM auth
FLYVM_TENANT_ID=              # uuid
```

### `config/application-dev.properties` — Spring side

Copy `config/application-dev.properties.example` → `config/application-dev.properties` and fill in the same values via `${NUBIAN_FOO_BAR:default}` placeholders. The `.env` values are picked up automatically by `start.sh` (which `set -a` + `source ".env"`).

If you want one source of truth, just use `.env` and leave the properties file referencing `${...}` placeholders.

---

## Provider matrix

The Java app supports several sandbox providers. Pick **one** via `NUBIAN_SANDBOX_PROVIDER`.

| `NUBIAN_SANDBOX_PROVIDER` | Talks to | When to use |
|---------------------------|----------|-------------|
| `computer-agent` | a Nubian Python controller running on a Linux host (port 6090) | running against any Ubuntu desktop |
| `firecracker` | a FlyVM API that provisions microVMs on demand | private/multi-tenant cloud |
| `docker-cli` | local Docker daemon, spins up `happysixd/osworld-docker` | OSWorld benchmark replay on a Linux x86 host |
| `local` | localhost X11 (your actual screen) | debugging on a Linux dev box |

Default in the example config: `computer-agent`. That's the simplest path — boot any Ubuntu desktop, install the Python controller from `deployment/sandbox-controller/`, point `NUBIAN_SANDBOX_COMPUTER_AGENT_HOST` at it.

---

## Sandbox controller (the Python service the agent talks to)

The `deployment/sandbox-controller/` directory contains the Python controller that runs **inside** the Ubuntu sandbox VM. It exposes:

- `GET  /eyes/screenshot` — current PNG
- `GET  /eyes/evidence` — window list, fs state
- `POST /hands/action` — click / type / scroll / hotkey / activate-window / ...
- `GET  /memory/files/list?path=<dir>` — list files in the guest
- `POST /memory/files/...` — read / write / upload

Deploy it to any Ubuntu 22.04 host with `deployment/sandbox-controller/deploy_to_osworld_ubuntu.sh`. That script:

1. Installs Python + system deps
2. Sets up an Xvfb 1024×1024×24 display
3. Installs systemd units for the controller + trackers
4. Smoke-tests the agent endpoint

See `deployment/sandbox-controller/README.md` for the deploy walkthrough.

---

## Sanity-check checklist before running `./start.sh`

```bash
# 1. Sandbox controller answers
curl -fs http://<host>:6090/eyes/evidence | head -c 200
# (or http://<host>:28006/agent/eyes/evidence if behind the nginx proxy)

# 2. Grounder answers
curl -fs http://<gpu-host>:9090/v1/models | jq .data[0].id

# 3. LLM key is set
env | grep -E 'GEMINI_API_KEY|GCP_API_KEY|OPENROUTER_API_KEY' | sed 's/=.*/=<set>/'
```

If all three return cleanly, `./start.sh` will boot and the demo UI at <http://localhost:7070/demo/computer> will be usable.

---

## Security boundaries

- **Never commit `config/application-dev.properties` or `.env`.** Both are in `.gitignore`. Verify with `git check-ignore .env config/application-dev.properties`.
- **Lock dev RabbitMQ + Redis to `127.0.0.1`** (already done in `docker-compose.yml`). Default creds are intentional for dev but the ports are loopback-only — never expose to a LAN or public IP without changing them.
- **Firewall the GPU host.** UI-TARS vLLM has no auth. Lock the inbound port to your operator IP (or use a private network, or wrap with a token-checking nginx).
- **FlyVM JWT secret rotates per environment.** Don't reuse the same `FLYVM_JWT_SECRET` across dev + prod.
- **Sandbox is a real Linux machine.** The agent can run arbitrary commands inside it. Treat it as compromised once it's been driven by an LLM you don't trust — wipe and recreate between sensitive tasks.

---

## Common errors

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `IO error: null` on `/memory/files/list?path=/tmp` | Sandbox controller is not reachable | `curl http://<host>:6090/eyes/evidence` — must return 200 |
| `FlyVM auto-discovery failed: could not list computers` | `firecracker` provider can't reach FlyVM API | switch to `computer-agent`, or set `NUBIAN_FLYVM_API_BASE` + valid JWT |
| `Could not resolve placeholder 'GEMINI_API_KEY'` | LLM key not exported | put it in `.env`, restart `./start.sh` |
| Grounder returns `(x, y)` far from target | Wrong `coordinate-space` or screenshot rescaled | force `nubian.uground.coordinate-space=native` and never crop |
| noVNC iframe shows `chrome-error://chromewebdata/` | Cross-origin iframe to remote noVNC host | open the noVNC URL in its own tab, or reverse-proxy through the agent host |
