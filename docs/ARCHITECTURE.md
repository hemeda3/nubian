# Architecture

## Components

| Component | Role | Where it runs |
|-----------|------|---------------|
| **Agent** | Java/Spring Boot. Owns the loop, the plan, the tool dispatch. | Laptop or container |
| **SeeActPlanner** | Per-turn LLM call. Picks the next action from the screenshot + plan. | Inside Agent |
| **SupervisorAdvisor** | Fires every N turns or on a doom-loop signal. Can rewrite a step (MODIFY), mark it done (ADVANCE), or take over for up to 6 actions (EXECUTE). | Inside Agent |
| **UGroundClient** | HTTP client for the pixel grounder. Sends `{image, description}` → gets `(x, y)`. | Inside Agent |
| **Tools** | Action dispatcher. Maps `{action, args}` → `POST /hands/action`. | Inside Agent |
| **LLM (Gemini 3.1 Pro Preview)** | Three roles: Planner, Verifier, Supervisor. | api.google.com (or OpenRouter) |
| **UI-TARS-1.7B grounder** | vLLM serving UI-TARS for description → coords. | GPU VM (any cloud) |
| **Sandbox** | `happysixd/osworld-docker` — QEMU-in-Docker Ubuntu 22.04 VM. | Hetzner, GCP spot, or local |
| **Sandbox controller** | Python Flask server inside the guest VM at `20.20.20.21:6090`. Endpoints: `/eyes/*`, `/hands/*`, `/memory/*`. | Inside the guest VM |
| **nginx (in container)** | Reverse-proxies `/agent/*` → guest VM `:6090`. Also fronts `:5000` OSWorld API + `:8006` noVNC. | Sandbox container |

## Per-turn flow

```
LOOP until plan complete or max-steps:

  1. Agent  → Sandbox    GET /eyes/screenshot              [PNG]
  2. Agent  → LLM        SeeActPlanner: image + plan       [planner role]
  3. LLM    → Agent      {action, target_description, ...}
  4. Agent  → Grounder   image + description               (if action needs coords)
  5. Grounder → Agent    (x, y)
  6. Agent  → Sandbox    POST /hands/action {action, args}
  7. wait observation-settle-ms (default 600)
  8. Agent  → Sandbox    GET /eyes/screenshot              [post-action]
  9. Agent  → LLM        verify: did the active step succeed?  [verifier role]
 10. update plan state

  EVERY 10 TURNS or on doom-loop signal:
 11. Agent  → LLM        SupervisorAdvisor + screenshot    [supervisor role, vision on in angry bird mode]
 12. LLM    → Agent      {kind: CONTINUE | MODIFY | ADVANCE | EXECUTE, ...}
 13. apply verdict (rewrite active step, mark done, or take over keyboard)
```

## Doom-loop signals

- `scroll_loop_detected` — 4 consecutive navigation-only actions
- `repeated_action_detected` — same non-navigation route hash ≥ 2× in last 5 turns

On either signal the supervisor fires immediately (not waiting for the
next mod-10 turn) and receives the current screenshot. Signals never leak
to the planner as text hints — text hints were ignored in v1.

## Tool catalog

| Action | Args | Notes |
|--------|------|-------|
| `click` | `{x, y, button?}` | pixel coords from grounder |
| `type_text` | `{text, mode?: "append"\|"replace"}` | `replace` = Ctrl+A → Delete → type |
| `hotkey` | `{combo}` | e.g. `ctrl+l`, `enter` |
| `scroll` | `{direction: "up"\|"down"\|"left"\|"right", amount?: <int>}` | explicit direction required |
| `activate_window` | `{name}` | exact title from `/eyes/evidence` |
| `close_window` | `{name}` | window-manager close |
| `write_file` | `{path, content}` | absolute path in sandbox FS |
| `wait` | `{ms}` | |

Sign-based scroll inference (`dy<0 → up`) was the source of a long
doom-loop in v1: the planner inconsistently emitted negative `dy`
intending either direction. v2 forces explicit `direction`.

## Diagrams

- [`architecture.svg`](architecture.svg) — compact per-turn flow
- [`architecture-detailed.svg`](architecture-detailed.svg) — full 12-layer stack with external services, recovery, grounding, persistence, security
