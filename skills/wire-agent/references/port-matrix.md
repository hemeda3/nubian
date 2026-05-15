# Port matrix

Every layer of the stack picks its own port number. When they don't agree, the agent builds wrong URLs and the noVNC iframe goes black.

## The four numbers that must align

| Layer | What | Hetzner | GCP spot | Local Docker |
|-------|------|---------|----------|--------------|
| Docker mapping | `-p HOST:8006` | `28006:8006` | `8006:8006` | `8006:8006` |
| Container nginx `/agent/` location | proxies to guest:6090 | required | required | required |
| Controller `PUBLIC_AGENT_PORT` / `PUBLIC_VNC_PORT` (systemd unit) | what controller advertises | `28006` | `8006` | `8006` |
| Laptop `.env` `NUBIAN_SANDBOX_COMPUTER_AGENT_AGENT_PORT` + `_NOVNC_PORT` | what the local Java app uses | `28006` | `8006` | `8006` |

## Other relevant ports (no alignment needed beyond docker-compose / firewall)

| Port (host) | What |
|-------------|------|
| `5000` (or `25000`) | OSWorld benchmark API (`/setup/upload`, `/execute`, `/screenshot`) |
| `5900` | raw VNC |
| `9222` | Chrome DevTools Protocol |
| `8080` | VLC |

## Pitfalls

- **happysixd/osworld-docker maps `8006:8006` by default** — if you change the host port (`-p 28006:8006`), you must also flip the controller env and the laptop env to `28006`.
- **The Hetzner production VM uses `:28006` everywhere** because its docker-compose maps `28006:8006`. That's why the controller's default `PUBLIC_AGENT_PORT=28006` exists. Don't assume "28006 is wrong" — it's right *for Hetzner*.
- **The Java app does not auto-discover ports** from the controller's `/health` response. You must set `NUBIAN_SANDBOX_COMPUTER_AGENT_NOVNC_PORT` in `.env`.
