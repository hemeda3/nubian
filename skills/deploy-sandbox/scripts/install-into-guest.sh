#!/usr/bin/env bash
# Install the Nubian Python controller into the OSWorld guest VM.
#
# Run this *on the sandbox host* (the machine running the OSWorld container).
# It uses the OSWorld /setup/upload + /execute API to push files and run
# the installer inside the guest.
#
# Usage:
#   bash install-into-guest.sh <OSWORLD_API>
#   e.g. bash install-into-guest.sh http://127.0.0.1:5000
#
# Expects the controller package to be at /tmp/sandbox-controller/ (scp it
# there first from the repo's deployment/sandbox-controller/).

set -euo pipefail

OSWORLD_API="${1:-http://127.0.0.1:5000}"
PKG_DIR="${PKG_DIR:-/tmp/sandbox-controller}"
GUEST_AGENT_DIR="/home/user/.local/share/nubian-universal-computer-agent"
GUEST_SVC_DIR="/home/user/.config/systemd/user"

cd "$PKG_DIR"

echo "[1/3] uploading controller files into the guest VM"
for f in \
    computer_agent.py \
    window_tracker_x11.py \
    xinput_tracker.py \
    atspi_tracker.py \
    event_indexer.py \
    screen_recorder.py \
    recent_event_text.py \
    container_port_forwarder.py \
    ubuntu_desktop_tweaks.sh
do
  curl -fsS --max-time 30 -X POST "$OSWORLD_API/setup/upload" \
    -F "file_path=$GUEST_AGENT_DIR/$f" \
    -F "file_data=@$f" \
    -o /dev/null
  echo "  $f"
done

echo "[2/3] uploading systemd unit files"
for unit in \
    nubian-universal-computer-agent \
    nubian-x11-window-tracker \
    nubian-xinput-tracker \
    nubian-atspi-tracker \
    nubian-event-indexer \
    nubian-screen-recorder
do
  curl -fsS --max-time 30 -X POST "$OSWORLD_API/setup/upload" \
    -F "file_path=$GUEST_SVC_DIR/$unit.service" \
    -F "file_data=@$unit.service" \
    -o /dev/null
  echo "  $unit.service"
done

echo "[3/3] running guest installer (apt + pip + systemctl enable + start)"
python3 - "$OSWORLD_API" "$PKG_DIR/install-command.sh" <<'PY'
import json, sys, urllib.request, pathlib
api = sys.argv[1].rstrip("/") + "/execute"
cmd = pathlib.Path(sys.argv[2]).read_text()
data = json.dumps({"shell": True, "command": cmd}).encode()
req = urllib.request.Request(api, data=data,
                              headers={"Content-Type": "application/json"},
                              method="POST")
print(urllib.request.urlopen(req, timeout=600).read().decode()[-1000:])
PY

echo "[done] verifying controller health"
curl -fsS --max-time 10 -X POST "$OSWORLD_API/execute" \
  -H "Content-Type: application/json" \
  -d '{"shell": true, "command": "curl -fsS http://127.0.0.1:6090/health | python3 -c \"import json,sys;d=json.load(sys.stdin);print(\\\"ok=\\\",d[\\\"ok\\\"],\\\"ports=\\\",d[\\\"ports\\\"])\""}'

cat <<EOF

Next step: expose the controller through the container's nginx and patch
the advertised public port. See ../wire-agent/SKILL.md
EOF
