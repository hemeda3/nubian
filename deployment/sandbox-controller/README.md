# Universal Ubuntu Desktop Computer Agent

Created: `2026-04-30T06:37:18Z`

Purpose:

```text
Install one small, universal Python computer-agent inside an Ubuntu desktop VM so any host
or Java adapter can control it with the same Eyes + Hands + Shell + Memory + Viewer API.
```

This package is for the OSWorld Ubuntu VM running on Hetzner:

```text
host: YOUR_HOST
osworld lab: /root/flyvm-osworld-agent-lab
docker container: awesome_bhabha
ubuntu guest ip: 20.20.20.21
```

## Ports

Inside Ubuntu guest:

```text
6090 = universal computer-agent API
5000 = original OSWorld Flask guest API
5900 = VNC from OSWorld Docker/QEMU stack
```

Public host:

```text
25000 = original OSWorld guest API
28006 = OSWorld noVNC and /agent/ computer-agent proxy
```

Why `/agent/` on `28006`: the OSWorld Docker wrapper already exposes nginx/noVNC reliably on host port `28006`, and nginx can route to the Ubuntu guest network at `20.20.20.21:6090`. The standalone host `29222` CDP mapping remains unreliable for late-started services in this Docker wrapper, so this package publishes the computer-agent through nginx.

## Reproducible Desktop Tweaks

`deploy_to_osworld_ubuntu.sh` uploads and runs:

```bash
/home/user/.local/share/nubian-universal-computer-agent/ubuntu_desktop_tweaks.sh
```

Current default:

```bash
NUBIAN_CURSOR_SIZE=32
```

The script applies the cursor size live through GNOME `gsettings` and X11
`xrdb`, and persists `Xcursor.size` in `/home/user/.Xresources`. Override on
deploy if needed:

```bash
NUBIAN_CURSOR_SIZE=40 ./deploy_to_osworld_ubuntu.sh
```

## API Shape

Eyes:

```http
GET /eyes/screenshot
POST /eyes/ocr
GET /eyes/accessibility
GET /eyes/windows
GET /eyes/window-open?name=writer
GET /eyes/input-events
GET /eyes/accessibility-events
GET /eyes/events
GET /eyes/observe
GET /eyes/evidence?dir=/workspace&file=/workspace/result.svg
GET /system/process-running?name=gimp
GET /eyes/sql?q=SELECT...
POST /eyes/sql
```

The desktop event stack has three parallel logs:

```text
/logs/windows.jsonl       = X11 top-level windows: open/close/move/focus/title
/logs/input-events.jsonl  = XInput2 mouse/keyboard events: coords/buttons/keycode/key/text
/logs/atspi-events.jsonl  = AT-SPI app UI events: focus/text/selection/active descendant/text snapshots
/logs/events.duckdb       = derived DuckDB index with session_id + seq
```

The raw JSONL logs are the audit trail. DuckDB is a rebuildable query index for time ranges, filters, active-window context, and before/after neighbor lookup.
Default event retention is 3600 seconds.

Hands:

```http
POST /hands/action
POST /hands/pyautogui
POST /hands/xdotool
```

Verifier evidence:

```http
GET /eyes/evidence
GET /memory/files/list?path=/workspace
GET /memory/files/stat?path=/workspace/result.svg
GET /memory/files/recent?dir=/downloads&since=1770000000
GET /memory/clipboard
POST /memory/clipboard {"text":"..."}
GET /apps/is-installed?name=libreoffice
```

Use `/eyes/evidence` beside the last screenshots. It returns screen geometry,
active/running windows, non-window processes, selected directory listings,
specific file stats, recent files, and clipboard text when the agent has native
OS clipboard access. Ubuntu uses `xclip`/`xsel`/`pyperclip`; Windows uses
`pyperclip` or PowerShell `Get-Clipboard` / `Set-Clipboard`.

Minimal CodeAct:

```http
GET  /codeact
GET  /codeact/screenshot
POST /codeact/python
POST /codeact/ocr
```

`/codeact/python` accepts raw Python, fenced `python` code blocks,
`<code>...</code>` bodies, or JSON like `{"code":"...","timeout_ms":10000}`.
The Python environment preloads `pyautogui` with `FAILSAFE=False`, plus
`requests`, `websocket`, `cdp_get()`, and `cdp_post()`. Use
`/codeact/screenshot` as the observation source.

Before execution, the server strips common model wrappers such as a bare first
line of `python`, repairs common inline compound syntax such as
`with ...: for ...:`, and runs `ruff check --fix` plus `ruff format` when Ruff
is installed. If the code still does not parse, it can call the Qwen repair
service as a removable layer before execution. Runtime Python errors return
HTTP 200 with `ok:false`, `stderr`, `normalization`, `ruff`, and `qwen_repair`
metadata so the caller can retry instead of treating the tool call as a
transport failure.

Qwen repair controls:

```json
{"qwen_repair":"auto"}    // default: only if syntax is still invalid
{"qwen_repair":"always"}  // force model repair before execution
{"qwen_repair":false}     // remove the layer for this request
```

Environment controls:

```bash
COMPUTER_AGENT_QWEN_REPAIR=auto
COMPUTER_AGENT_QWEN_REPAIR_URL=http://YOUR_HOST:28009/repair
```

For visible text labels, the Python code can set a target intent at the end of
the block. After the user code exits successfully, the controller captures the
current screen, OCRs the optional crop, matches the target, applies the action,
and returns `intent_applied` plus a stdout sentinel.

```python
target = "Hyväksy kaikki"
target_region = [520, 900, 1500, 1060]
target_action = "click"

target = "Search"
target_region = [0, 0, 1920, 200]
target_action = "type"
target_text = "iceland flights\n"
```

Recognized globals:

```python
target = None          # str | None
target_region = None   # [x1,y1,x2,y2] | None
target_action = None   # click | double_click | right_click | type
target_text = None     # text to type when target_action == "type"
```

Intent sentinel in stdout:

```text
<<<CODEACT_INTENT_APPLIED>>>{"ok":true,"matched":"Hyväksy kaikki","center":[741,992]}
```

PaddleOCR target action:

```http
POST /eyes/ocr
POST /codeact/ocr
```

Body:

```json
{
  "bbox": [520, 900, 1500, 1060],
  "target_text": "Hyväksy kaikki",
  "action": "click",
  "model": "mobile"
}
```

The agent captures the current framebuffer, crops `bbox` in screenshot pixel
coordinates, runs PaddleOCR on that crop, finds `target_text`, then applies
`action` to the matched text center. Supported actions are `none`, `click`,
`double_click`, `right_click`, `move`, and `type`. The JSON response includes all OCR boxes as
`x1 y1 x2 y2`, the selected match, click coordinates, timings, and artifact
links under `/logs/ocr/*.png|*.json|*.txt`.

Structured `/hands/action` types:

```text
move / move_to
click
double_click / dblclick
right_click / context_click
middle_click
long_click / click_hold / hold_click
mouse_down / mouse_up
drag / drag_to
drag_between / drag_from_to / drag_drop / drag_window
move_window / window_move
minimize_window / window_minimize
maximize_window / window_maximize
unmaximize_window / restore_window / window_restore
close_window / window_close / exit_window
type_grid / human_type_grid / human_type_table / keyboard_table / spreadsheet_type_grid
paste_text / clipboard_paste / paste / paste_tsv / type_block / write_block
type / write
press
hotkey
scroll
wait / wait_ms / hold
```

Deploy ensures `xdotool`, `wmctrl`, `xclip`, `xinput`, `python-xlib`, `python3-pyatspi`, and `at-spi2-core` are installed inside the Ubuntu guest.

Shell:

```http
POST /shell/exec
POST /exec
```

Memory:

```http
GET  /memory/files/list?path=/workspace
GET  /memory/files?path=/workspace/result.md
POST /memory/files
```

Skills:

```http
GET  /skills
GET  /skills/search?q=create+spreadsheet+student+fake+id
POST /skills/run
```

The skills API is the fast path before GUI control. For file-shaped deliverables, the host
should search skills first and run a macro if there is a strong match. Example: a task like
"open an excel sheet, add fake student ids and names, save it" maps to
`create_spreadsheet_csv`, which writes the CSV artifact directly instead of spending many
screenshots/clicks in LibreOffice.

Viewer and browser:

```http
GET /viewer/vnc
GET /browser/cdp/json/version
GET /browser/cdp/json/list
```

Nerves:

```http
GET /health
GET /logs/actions
```

## Deploy

From the repo root:

```bash
osworld/universal-ubuntu-desktop-computer-agent-eyes-hands-shell-memory-viewer-20260430/deploy_to_osworld_ubuntu.sh
```

## Smoke Test

```bash
osworld/universal-ubuntu-desktop-computer-agent-eyes-hands-shell-memory-viewer-20260430/smoke_test_public_agent.sh
```

Expected public base URL:

```text
http://YOUR_HOST:28006/agent
```

## Examples

Health:

```bash
curl -fsS http://YOUR_HOST:28006/agent/health | jq .
```

Screenshot:

```bash
curl -fsS http://YOUR_HOST:28006/agent/eyes/screenshot -o screenshot.png
```

Window events:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/windows?limit=20' | jq .
curl -fsS 'http://YOUR_HOST:28006/agent/memory/files?path=/logs/windows.jsonl&format=text' | jq -r .content
```

Raw input events:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/input-events?limit=20' | jq .
curl -fsS 'http://YOUR_HOST:28006/agent/memory/files?path=/logs/input-events.jsonl&format=text' | jq -r .content
```

Printable key presses include `key`, `key_base`, `text`, `is_printable`, and `modifiers` when X11 exposes the keycode through `xmodmap`.

Accessibility events:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/accessibility-events?limit=20' | jq .
curl -fsS 'http://YOUR_HOST:28006/agent/memory/files?path=/logs/atspi-events.jsonl&format=text' | jq -r .content
```

Merged session events:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events?limit=100' | jq .
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events?streams=windows,input,accessibility,actions&limit=200' | jq .
```

DuckDB event memory:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/index/status' | jq .
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/active-window' | jq .
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/recent?limit=50&app=Gnome-terminal' | jq .
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/around?seq=123&before=3&after=3' | jq .
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/around?app=Google-chrome&event=focus&before=5&after=5' | jq .
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/events/context?limit=30' | jq .
```

Use `/eyes/events/context` when building an LLM prompt: screenshot is visual evidence, this trace is verified semantic state.
It returns both `summary` for quick model reading and `trace`/`events` for exact details.

State of the world:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/eyes/observe?limit=10&apps_limit=30&files_limit=10' | jq .
```

`/eyes/observe` merges the desktop world into one response:

```text
focused                = active X11/AT-SPI window and focused widget
running_windows        = wmctrl/xprop snapshot with xid, pid, title, bbox
recent_focus           = DuckDB focus history with ago_sec
available_to_launch    = parsed .desktop launchers from system/user/snap/flatpak dirs
screen_objects.from_atspi = recent accessibility UI objects/events
last_action_result     = last agent action plus verified events after it
filesystem_recent      = recent files under /workspace, /downloads, /uploads, Documents, Downloads
```

Read-only SQL:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/eyes/sql \
  -H 'Content-Type: application/json' \
  --data '{"sql":"SELECT app, title, max(t) AS last_seen FROM events WHERE stream = '\''windows'\'' GROUP BY app, title ORDER BY last_seen DESC","limit":20}' | jq .
```

This is intentionally served by the existing computer-agent instead of an experimental DuckDB TCP server. The agent opens DuckDB read-only and only accepts one `SELECT`/`WITH` statement.

Apps and windows:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/apps/search?q=spreadsheet' | jq .

curl -fsS -X POST http://YOUR_HOST:28006/agent/apps/launch \
  -H 'Content-Type: application/json' \
  --data '{"query":"spreadsheet"}'

curl -fsS -X POST http://YOUR_HOST:28006/agent/windows/activate \
  -H 'Content-Type: application/json' \
  --data '{"app":"libreoffice"}'
```

Use this launch/focus path before GUI app-menu navigation. If a target app is already open, activate its `xid`; if it is not running, launch the parsed `.desktop` `Exec` command. `/apps/launch` waits for the window in stages (`5s`, then `10s`, then `15s` by default), then activates it. `/apps/launch` and `/windows/activate` request fullscreen by default on Linux and maximize as fallback; pass `{"fullscreen":false}` or `{"maximize":false}` only when Java needs to preserve the current geometry. Windows has no universal OS fullscreen window state, so the native Windows path activates and maximizes.

Launch controls:

```json
{"name":"GIMP","wait_stages_sec":[5,10,15],"fullscreen":true,"maximize":true}
{"name":"GIMP","fullscreen":false,"maximize":true}
```

Move/click:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"click","x":960,"y":540}'
```

Drag and click-hold:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"drag_between","from_x":400,"from_y":300,"to_x":700,"to_y":500,"duration":0.35}'

curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"long_click","x":960,"y":540,"duration_ms":800}'
```

Bulk paste text or spreadsheet TSV:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"paste_tsv","rows":[["Student Name","Score","City"],["ahmed","42","Cairo"],["mohammed","17","Dubai"],["ali","88","Riyadh"]]}'
```

For Calc and other spreadsheet apps, prefer `paste_tsv` over repeated `type_text` plus arrow/Return keys. One paste can fill an entire table and avoids key-budget churn.

Human-style spreadsheet keyboard fill:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"type_grid","home_first":true,"columns":["Student Name","Score","City"],"rows":[["Ahmed","72","Cairo"],["Mohammed","91","Dubai"],["Ali","64","Riyadh"]]}'
```

`type_grid` is deliberately not a document API and not clipboard paste. It simulates human keyboard entry through the active GUI: type a cell, press Tab, continue across the row, press Enter then Home for the next row. Use it when the task needs visible desktop interaction but the model should not spend one iteration per cell.

Move a window by `xid` without pixel dragging:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"move_window","xid":"0x06e00099","x":100,"y":100,"w":900,"h":700}'
```

Window state and close by exact `xid`:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"maximize_window","xid":"0x06e00099"}'

curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"unmaximize_window","xid":"0x06e00099"}'

curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"minimize_window","xid":"0x06e00099"}'

curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/action \
  -H 'Content-Type: application/json' \
  --data '{"type":"close_window","xid":"0x06e00099"}'
```

Prefer passing an exact `xid` from `/eyes/observe.running_windows` for minimize, maximize, restore, move, and close. App-name targeting can be ambiguous when several windows of the same app are open.

Raw pyautogui:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/hands/pyautogui \
  -H 'Content-Type: application/json' \
  --data '{"action":"pyautogui.moveTo(960, 540); pyautogui.click()"}'
```

Shell:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/shell/exec \
  -H 'Content-Type: application/json' \
  --data '{"cmd":"whoami && hostname && pwd"}'
```

Write a file:

```bash
curl -fsS -X POST http://YOUR_HOST:28006/agent/memory/files \
  -H 'Content-Type: application/json' \
  --data '{"path":"/workspace/agent-demo/result.md","content":"# hello\n"}'
```

Search and run a spreadsheet skill:

```bash
curl -fsS 'http://YOUR_HOST:28006/agent/skills/search?q=open%20excelshieet%20student%20fake%20id%20studne%20name%20save' | jq .

curl -fsS -X POST http://YOUR_HOST:28006/agent/skills/run \
  -H 'Content-Type: application/json' \
  --data '{
    "skill_id":"create_spreadsheet_csv",
    "path":"/workspace/agent-demo/students.csv",
    "columns":["Student Fake ID","Student Name"],
    "rows":[
      ["S001","Aisha Khan"],
      ["S002","Omar Ali"],
      ["S003","Maya Chen"],
      ["S004","Noah Smith"]
    ]
  }'

curl -fsS 'http://YOUR_HOST:28006/agent/memory/files?path=/workspace/agent-demo/students.csv&format=text' | jq -r .content
```
