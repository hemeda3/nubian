#!/usr/bin/env bash
set -euo pipefail

SERVER="${SERVER:-root@YOUR_HOST}"
PUBLIC_HOST="${PUBLIC_HOST:-YOUR_HOST}"
REMOTE_DIR="${REMOTE_DIR:-/root/flyvm-osworld-agent-lab/universal-computer-agent-eyes-hands-shell-memory-viewer-20260430}"
OSWORLD_API="${OSWORLD_API:-http://127.0.0.1:25000}"
CONTAINER="${CONTAINER:-awesome_bhabha}"
GUEST_AGENT_DIR="${GUEST_AGENT_DIR:-/home/user/.local/share/nubian-universal-computer-agent}"
GUEST_SERVICE_PATH="${GUEST_SERVICE_PATH:-/home/user/.config/systemd/user/nubian-universal-computer-agent.service}"
GUEST_WINDOW_TRACKER_SERVICE_PATH="${GUEST_WINDOW_TRACKER_SERVICE_PATH:-/home/user/.config/systemd/user/nubian-x11-window-tracker.service}"
GUEST_XINPUT_TRACKER_SERVICE_PATH="${GUEST_XINPUT_TRACKER_SERVICE_PATH:-/home/user/.config/systemd/user/nubian-xinput-tracker.service}"
GUEST_ATSPI_TRACKER_SERVICE_PATH="${GUEST_ATSPI_TRACKER_SERVICE_PATH:-/home/user/.config/systemd/user/nubian-atspi-tracker.service}"
GUEST_EVENT_INDEXER_SERVICE_PATH="${GUEST_EVENT_INDEXER_SERVICE_PATH:-/home/user/.config/systemd/user/nubian-event-indexer.service}"
GUEST_SCREEN_RECORDER_SERVICE_PATH="${GUEST_SCREEN_RECORDER_SERVICE_PATH:-/home/user/.config/systemd/user/nubian-screen-recorder.service}"
GUEST_AGENT_PORT="${GUEST_AGENT_PORT:-6090}"
PUBLIC_AGENT_PORT="${PUBLIC_AGENT_PORT:-28006}"
PUBLIC_AGENT_BASE_PATH="${PUBLIC_AGENT_BASE_PATH:-/agent}"
GUEST_IP="${GUEST_IP:-20.20.20.21}"
NUBIAN_CURSOR_SIZE="${NUBIAN_CURSOR_SIZE:-32}"
export NUBIAN_CURSOR_SIZE
PUBLIC_AGENT_BASE_URL="http://$PUBLIC_HOST:$PUBLIC_AGENT_PORT$PUBLIC_AGENT_BASE_PATH"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[deploy] syncing package to $SERVER:$REMOTE_DIR"
ssh "$SERVER" "mkdir -p '$REMOTE_DIR'"
rsync -av --exclude '__pycache__' --exclude 'tmp/' "$SCRIPT_DIR/" "$SERVER:$REMOTE_DIR/"

echo "[deploy] checking OSWorld guest API"
ssh "$SERVER" "curl -fsS -m 10 '$OSWORLD_API/screenshot' -o /tmp/osworld-agent-deploy-screenshot.png && file /tmp/osworld-agent-deploy-screenshot.png"

echo "[deploy] uploading agent into Ubuntu guest"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_AGENT_DIR/computer_agent.py' -F 'file_data=@$REMOTE_DIR/computer_agent.py'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_AGENT_DIR/window_tracker_x11.py' -F 'file_data=@$REMOTE_DIR/window_tracker_x11.py'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_AGENT_DIR/xinput_tracker.py' -F 'file_data=@$REMOTE_DIR/xinput_tracker.py'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_AGENT_DIR/atspi_tracker.py' -F 'file_data=@$REMOTE_DIR/atspi_tracker.py'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_AGENT_DIR/event_indexer.py' -F 'file_data=@$REMOTE_DIR/event_indexer.py'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_AGENT_DIR/screen_recorder.py' -F 'file_data=@$REMOTE_DIR/screen_recorder.py'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_AGENT_DIR/ubuntu_desktop_tweaks.sh' -F 'file_data=@$REMOTE_DIR/ubuntu_desktop_tweaks.sh'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_SERVICE_PATH' -F 'file_data=@$REMOTE_DIR/nubian-universal-computer-agent.service'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_WINDOW_TRACKER_SERVICE_PATH' -F 'file_data=@$REMOTE_DIR/nubian-x11-window-tracker.service'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_XINPUT_TRACKER_SERVICE_PATH' -F 'file_data=@$REMOTE_DIR/nubian-xinput-tracker.service'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_ATSPI_TRACKER_SERVICE_PATH' -F 'file_data=@$REMOTE_DIR/nubian-atspi-tracker.service'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_EVENT_INDEXER_SERVICE_PATH' -F 'file_data=@$REMOTE_DIR/nubian-event-indexer.service'"
ssh "$SERVER" "curl -fsS -m 30 -X POST '$OSWORLD_API/setup/upload' -F 'file_path=$GUEST_SCREEN_RECORDER_SERVICE_PATH' -F 'file_data=@$REMOTE_DIR/nubian-screen-recorder.service'"

cat > /tmp/osworld-universal-agent-install-command.sh <<'CMD'
set -e
printf 'password\n' | sudo -S mkdir -p /workspace /workspace/agent-demo /downloads /uploads /logs /tmp/agent
printf 'password\n' | sudo -S chown -R user:user /workspace /downloads /uploads /logs /tmp/agent
if ! command -v xdotool >/dev/null 2>&1 || ! command -v wmctrl >/dev/null 2>&1 || ! command -v xclip >/dev/null 2>&1; then
  printf 'password\n' | sudo -S apt-get update
  printf 'password\n' | sudo -S DEBIAN_FRONTEND=noninteractive apt-get install -y xdotool wmctrl xclip
fi
if ! command -v xrdb >/dev/null 2>&1 || ! command -v xsetroot >/dev/null 2>&1; then
  printf 'password\n' | sudo -S apt-get update
  printf 'password\n' | sudo -S DEBIAN_FRONTEND=noninteractive apt-get install -y x11-xserver-utils
fi
if ! command -v xinput >/dev/null 2>&1 || ! python3 -c 'import pyatspi' >/dev/null 2>&1; then
  printf 'password\n' | sudo -S apt-get update
  printf 'password\n' | sudo -S DEBIAN_FRONTEND=noninteractive apt-get install -y xinput at-spi2-core python3-pyatspi
fi
if ! python3 -c 'import Xlib' >/dev/null 2>&1; then
  python3 -m pip install --user python-xlib
fi
if ! python3 -c 'import duckdb' >/dev/null 2>&1; then
  python3 -m pip install --user duckdb
fi
if ! python3 -c 'import pyautogui, requests, websocket' >/dev/null 2>&1; then
  python3 -m pip install --user pyautogui requests websocket-client pillow
fi
if ! python3 -c 'import numpy, paddle, paddleocr; assert numpy.__version__.startswith("1.26."); assert paddle.__version__.startswith("3.2.")' >/dev/null 2>&1; then
  python3 -m pip install --user --disable-pip-version-check --force-reinstall 'numpy==1.26.4' 'paddlepaddle==3.2.2' paddleocr
fi
if ! python3 -m ruff version >/dev/null 2>&1; then
  python3 -m pip install --user --disable-pip-version-check ruff
fi
mkdir -p /home/user/.local/share/nubian-universal-computer-agent /home/user/.config/systemd/user
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/computer_agent.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/window_tracker_x11.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/xinput_tracker.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/atspi_tracker.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/event_indexer.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/screen_recorder.py
chmod 0755 /home/user/.local/share/nubian-universal-computer-agent/ubuntu_desktop_tweaks.sh
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/computer_agent.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/window_tracker_x11.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/xinput_tracker.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/atspi_tracker.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/event_indexer.py
python3 -m py_compile /home/user/.local/share/nubian-universal-computer-agent/screen_recorder.py
export XDG_RUNTIME_DIR=/run/user/1000
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus
export NUBIAN_CURSOR_SIZE="__NUBIAN_CURSOR_SIZE__"
gsettings set org.gnome.desktop.interface toolkit-accessibility true || true
NUBIAN_CURSOR_SIZE="${NUBIAN_CURSOR_SIZE:-32}" /home/user/.local/share/nubian-universal-computer-agent/ubuntu_desktop_tweaks.sh || true
systemctl --user daemon-reload
systemctl --user enable nubian-universal-computer-agent.service
systemctl --user enable nubian-x11-window-tracker.service
systemctl --user enable nubian-xinput-tracker.service
systemctl --user enable nubian-atspi-tracker.service
systemctl --user enable nubian-event-indexer.service
systemctl --user enable nubian-screen-recorder.service
systemctl --user restart nubian-universal-computer-agent.service
systemctl --user restart nubian-x11-window-tracker.service
systemctl --user restart nubian-xinput-tracker.service
systemctl --user restart nubian-atspi-tracker.service
systemctl --user restart nubian-event-indexer.service
systemctl --user restart nubian-screen-recorder.service
sleep 2
systemctl --user --no-pager --full status nubian-universal-computer-agent.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-x11-window-tracker.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-xinput-tracker.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-atspi-tracker.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-event-indexer.service | sed -n '1,80p'
systemctl --user --no-pager --full status nubian-screen-recorder.service | sed -n '1,80p'
curl -fsS http://127.0.0.1:6090/health
CMD
perl -0pi -e 's/__NUBIAN_CURSOR_SIZE__/$ENV{NUBIAN_CURSOR_SIZE}/g' /tmp/osworld-universal-agent-install-command.sh

echo "[deploy] installing and restarting guest systemd service"
scp -q /tmp/osworld-universal-agent-install-command.sh "$SERVER:$REMOTE_DIR/install-command.sh"
ssh "$SERVER" "OSWORLD_API='$OSWORLD_API' CMD_FILE='$REMOTE_DIR/install-command.sh' python3 -" <<'PY'
import json
import os
import pathlib
import urllib.request

api = os.environ["OSWORLD_API"].rstrip("/") + "/execute"
cmd = pathlib.Path(os.environ["CMD_FILE"]).read_text()
data = json.dumps({"shell": True, "command": cmd}).encode("utf-8")
req = urllib.request.Request(api, data=data, headers={"Content-Type": "application/json"}, method="POST")
with urllib.request.urlopen(req, timeout=120) as resp:
    print(resp.read().decode("utf-8"))
PY

echo "[deploy] publishing agent through nginx $PUBLIC_AGENT_BASE_URL -> guest $GUEST_IP:$GUEST_AGENT_PORT"
ssh "$SERVER" "docker exec -i '$CONTAINER' sh -s" <<'EOS'
set -e
rm -f /etc/nginx/sites-enabled/web.conf.before-nubian-agent
cp /etc/nginx/sites-enabled/web.conf /tmp/web.conf.before-nubian-agent
awk '
  /location = \/agent/ {skip=1; next}
  /location \/agent\// {skip=1; next}
  skip && /^    }/ {skip=0; next}
  !skip {print}
' /etc/nginx/sites-enabled/web.conf > /tmp/web.clean
head -n -1 /tmp/web.clean > /tmp/web.new
cat >> /tmp/web.new <<'EOF'

    location = /agent { return 301 /agent/; }

    location /agent/ {
      proxy_http_version 1.1;
      proxy_buffering off;
      proxy_read_timeout 3600s;
      proxy_send_timeout 3600s;
      proxy_pass http://20.20.20.21:6090/;
    }

EOF
tail -n 1 /tmp/web.clean >> /tmp/web.new
mv /tmp/web.new /etc/nginx/sites-enabled/web.conf
nginx -t
nginx -s reload
EOS

echo "[deploy] smoke testing public agent"
sleep 1
retry_curl_json() {
  local url="$1"
  local attempts="${2:-10}"
  local delay="${3:-1}"
  local tmp
  tmp="$(mktemp)"
  for _ in $(seq 1 "$attempts"); do
    if curl -fsS -m 20 "$url" -o "$tmp"; then
      python3 -m json.tool < "$tmp"
      rm -f "$tmp"
      return 0
    fi
    sleep "$delay"
  done
  cat "$tmp" >&2 || true
  rm -f "$tmp"
  return 1
}
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/health" | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/eyes/screenshot" -o /tmp/osworld-universal-agent-screenshot.png
file /tmp/osworld-universal-agent-screenshot.png
curl -fsS -m 20 -X POST "$PUBLIC_AGENT_BASE_URL/shell/exec" \
  -H 'Content-Type: application/json' \
  --data '{"cmd":"whoami && hostname && pwd"}' | python3 -m json.tool
curl -fsS -m 20 -X POST "$PUBLIC_AGENT_BASE_URL/hands/action" \
  -H 'Content-Type: application/json' \
  --data '{"type":"move","x":960,"y":540}' | python3 -m json.tool
curl -fsS -m 20 -X POST "$PUBLIC_AGENT_BASE_URL/hands/action" \
  -H 'Content-Type: application/json' \
  --data '{"actions":[{"type":"mouse_down","x":960,"y":540},{"type":"wait_ms","ms":50},{"type":"mouse_up","x":960,"y":540},{"type":"drag_between","from_x":960,"from_y":540,"to_x":961,"to_y":541,"duration":0.05}]}' | python3 -m json.tool
curl -fsS -m 20 -X POST "$PUBLIC_AGENT_BASE_URL/hands/xdotool" \
  -H 'Content-Type: application/json' \
  --data '{"args":["getmouselocation"]}' | python3 -m json.tool
curl -fsS -m 20 -X POST "$PUBLIC_AGENT_BASE_URL/memory/files" \
  -H 'Content-Type: application/json' \
  --data '{"path":"/workspace/agent-demo/universal-agent-proof.txt","content":"universal computer agent deployed\n"}' | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/memory/files?path=/workspace/agent-demo/universal-agent-proof.txt&format=text" | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/skills/search?q=open%20excelshieet%20student%20fake%20id%20studne%20name%20save" | python3 -m json.tool
curl -fsS -m 20 -X POST "$PUBLIC_AGENT_BASE_URL/skills/run" \
  -H 'Content-Type: application/json' \
  --data '{"skill_id":"create_spreadsheet_csv","path":"/workspace/agent-demo/students.csv","columns":["Student Fake ID","Student Name"],"rows":[["S001","Aisha Khan"],["S002","Omar Ali"],["S003","Maya Chen"],["S004","Noah Smith"]]}' | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/memory/files?path=/workspace/agent-demo/students.csv&format=text" | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/eyes/windows?limit=20" | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/eyes/input-events?limit=20" | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/eyes/accessibility-events?limit=20" | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/eyes/events?limit=40" | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/eyes/observe?limit=10&apps_limit=20&files_limit=10" | python3 -m json.tool
curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/apps/search?q=spreadsheet" | python3 -m json.tool
retry_curl_json "$PUBLIC_AGENT_BASE_URL/eyes/events/index/status"
retry_curl_json "$PUBLIC_AGENT_BASE_URL/eyes/events/active-window"
retry_curl_json "$PUBLIC_AGENT_BASE_URL/eyes/events/recent?limit=20"
TARGET_SEQ="$(curl -fsS -m 20 "$PUBLIC_AGENT_BASE_URL/eyes/events/recent?limit=1" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["events"][-1]["seq"] if d.get("events") else "")')"
if [ -n "$TARGET_SEQ" ]; then
  retry_curl_json "$PUBLIC_AGENT_BASE_URL/eyes/events/around?seq=$TARGET_SEQ&before=3&after=3"
fi
retry_curl_json "$PUBLIC_AGENT_BASE_URL/eyes/events/context?limit=20"
curl -fsS -m 20 -X POST "$PUBLIC_AGENT_BASE_URL/eyes/sql" \
  -H 'Content-Type: application/json' \
  --data '{"sql":"SELECT stream, event, app, title, seq FROM events ORDER BY seq DESC","limit":5}' | python3 -m json.tool

cat <<DONE

[deploy] complete
public agent: $PUBLIC_AGENT_BASE_URL
vnc viewer:   http://$PUBLIC_HOST:28006/
guest agent:  http://$GUEST_IP:$GUEST_AGENT_PORT inside OSWorld container network
service:      nubian-universal-computer-agent.service inside Ubuntu guest
window log:   /logs/windows.jsonl via nubian-x11-window-tracker.service
input log:    /logs/input-events.jsonl via nubian-xinput-tracker.service
atspi log:    /logs/atspi-events.jsonl via nubian-atspi-tracker.service
duckdb:       /logs/events.duckdb via nubian-event-indexer.service
screen rec:   /logs/screen-recorder/frames via nubian-screen-recorder.service

DONE
