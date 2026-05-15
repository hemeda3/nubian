# Universal Computer Agent Deployment Status

Deployed at:

```text
2026-04-30T06:51:43Z
```

Target:

```text
Hetzner host: YOUR_HOST
OSWorld lab: /root/flyvm-osworld-agent-lab
Docker wrapper container: awesome_bhabha
Ubuntu guest IP: 20.20.20.21
Ubuntu guest OS: Ubuntu 22.04.3 LTS
Ubuntu guest user: user
Ubuntu guest display: :0
Ubuntu guest session type: x11
```

## Public URLs

Unified computer-agent:

```text
http://YOUR_HOST:28006/agent
```

VNC/noVNC viewer:

```text
http://YOUR_HOST:28006/
```

Original OSWorld guest API:

```text
http://YOUR_HOST:25000
```

## Internal Ports

Inside Ubuntu guest:

```text
6090 = nubian-universal-computer-agent
5000 = original OSWorld guest API
```

Inside Docker wrapper:

```text
8006 = nginx/noVNC and /agent/ reverse proxy
```

On public host:

```text
28006 = noVNC plus /agent/ API proxy
25000 = original OSWorld guest API
```

## Installed Files

Inside Ubuntu guest:

```text
/home/user/.local/share/nubian-universal-computer-agent/computer_agent.py
/home/user/.local/share/nubian-universal-computer-agent/window_tracker_x11.py
/home/user/.local/share/nubian-universal-computer-agent/xinput_tracker.py
/home/user/.local/share/nubian-universal-computer-agent/atspi_tracker.py
/home/user/.local/share/nubian-universal-computer-agent/event_indexer.py
/home/user/.config/systemd/user/nubian-universal-computer-agent.service
/home/user/.config/systemd/user/nubian-x11-window-tracker.service
/home/user/.config/systemd/user/nubian-xinput-tracker.service
/home/user/.config/systemd/user/nubian-atspi-tracker.service
/home/user/.config/systemd/user/nubian-event-indexer.service
/workspace
/downloads
/uploads
/logs
/tmp/agent
/logs/skills.sqlite3
```

Inside OSWorld Docker wrapper:

```text
/etc/nginx/sites-enabled/web.conf
```

The deploy added this nginx route:

```nginx
location = /agent { return 301 /agent/; }

location /agent/ {
  proxy_http_version 1.1;
  proxy_buffering off;
  proxy_read_timeout 3600s;
  proxy_send_timeout 3600s;
  proxy_pass http://20.20.20.21:6090/;
}
```

## Service

Service name:

```text
nubian-universal-computer-agent.service
nubian-x11-window-tracker.service
nubian-xinput-tracker.service
nubian-atspi-tracker.service
nubian-event-indexer.service
```

Check inside Ubuntu through OSWorld API:

```bash
curl -fsS -X POST http://YOUR_HOST:25000/execute \
  -H 'Content-Type: application/json' \
  --data '{"shell":true,"command":"XDG_RUNTIME_DIR=/run/user/1000 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus systemctl --user status nubian-universal-computer-agent.service --no-pager"}'
```

Restart inside Ubuntu through OSWorld API:

```bash
curl -fsS -X POST http://YOUR_HOST:25000/execute \
  -H 'Content-Type: application/json' \
  --data '{"shell":true,"command":"XDG_RUNTIME_DIR=/run/user/1000 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus systemctl --user restart nubian-universal-computer-agent.service"}'
```

## Verified Smoke

Health:

```bash
curl -fsS http://YOUR_HOST:28006/agent/health
```

Verified result:

```text
ok: true
display: :0
pyautogui: true
screenshot: true
workspace_writable: true
downloads_writable: true
uploads_writable: true
logs_writable: true
```

Screenshot:

```bash
curl -fsS http://YOUR_HOST:28006/agent/eyes/screenshot -o screenshot.png
```

Verified:

```text
PNG image data, 1920 x 1080, 8-bit/color RGB
```

Shell:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/shell/exec \
  -H 'Content-Type: application/json' \
  --data '{"cmd":"whoami && hostname && pwd"}'
```

Verified stdout:

```text
user
user-virtual-machine
/workspace
```

Hands:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"move","x":960,"y":540}'
```

Verified:

```text
ok: true
```

Structured hands also supports:

```text
mouse_down, mouse_up, long_click, drag_between, drag_drop, drag_window, move_window,
minimize_window, maximize_window, unmaximize_window, restore_window, close_window,
type_grid, human_type_grid, human_type_table, paste_text, clipboard_paste, paste_tsv
```

Memory:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/memory/files \
  -H 'Content-Type: application/json' \
  --data '{"path":"/workspace/agent-demo/universal-agent-proof.txt","content":"universal computer agent deployed\n"}'

curl -fsS 'http://YOUR_HOST:28006/agent/memory/files?path=/workspace/agent-demo/universal-agent-proof.txt&format=text'
```

Verified content:

```text
universal computer agent deployed
```

Window tracker:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/windows?limit=20'
curl -fsS 'http://YOUR_HOST:28006/agent/memory/files?path=/logs/windows.jsonl&format=text'
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/input-events?limit=20'
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/accessibility-events?limit=20'
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events?limit=100'
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/index/status'
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/recent?limit=20'
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/around?seq=123&before=3&after=3'
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/context?limit=30'
```

Verified:

```text
OSWorld guest session type is x11, tracker writes /logs/windows.jsonl.
Events include existing, create, open, hide, close, move, title_change, and focus.
XInput2 tracker writes /logs/input-events.jsonl with Button/Key/Motion events.
AT-SPI tracker writes /logs/atspi-events.jsonl with focus/text/selection/window events.
DuckDB indexer writes /logs/events.duckdb with session_id and monotonic seq.
/eyes/events/context returns summary, trace, and raw events for the active window.
/eyes/observe returns focused state, running windows, recent focus, launchable apps, AT-SPI objects, last action result, and recent files.
/eyes/sql exposes read-only SELECT/WITH queries against /logs/events.duckdb through the existing computer-agent.
/apps/search, /apps/launch, and /windows/activate support direct launch/focus without GUI app-menu navigation.
/windows/activate maximizes by default; pass {"maximize":false} to preserve geometry.
/hands/action supports exact-xid window move, minimize, maximize, restore, and close primitives.
/hands/action supports human-style type_grid keyboard entry for spreadsheets and tables.
/hands/action supports bulk clipboard paste/TSV paste for spreadsheets and large text blocks.
```

Skill library:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/skills/search?q=open%20excelshieet%20student%20fake%20id%20studne%20name%20save'

curl -fsS -X POST http://YOUR_HOST:28006/agent/skills/run \
  -H 'Content-Type: application/json' \
  --data '{"skill_id":"create_spreadsheet_csv","path":"/workspace/agent-demo/students.csv","columns":["Student Fake ID","Student Name"],"rows":[["S001","Aisha Khan"],["S002","Omar Ali"],["S003","Maya Chen"],["S004","Noah Smith"]]}'

curl -fsS 'http://YOUR_HOST:28006/agent/memory/files?path=/workspace/agent-demo/students.csv&format=text'
```

Verified behavior:

```text
/skills/search maps typo-heavy spreadsheet tasks to create_spreadsheet_csv.
/skills/run writes /workspace/agent-demo/students.csv directly and logs skill_run.
This is the intended first step before GUI control for file-shaped deliverables.
```

Guest dependencies enforced by deploy:

```text
python-xlib
xdotool
wmctrl
xinput
at-spi2-core
python3-pyatspi
duckdb
```

## Deploy Script

Run from repo root:

```bash
osworld/universal-ubuntu-desktop-computer-agent-eyes-hands-shell-memory-viewer-20260430/deploy_to_osworld_ubuntu.sh
```

Smoke:

```bash
osworld/universal-ubuntu-desktop-computer-agent-eyes-hands-shell-memory-viewer-20260430/smoke_test_public_agent.sh
```
