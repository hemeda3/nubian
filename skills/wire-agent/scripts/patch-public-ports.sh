#!/usr/bin/env bash
# Patch the Nubian controller's advertised PUBLIC_AGENT_PORT and
# PUBLIC_VNC_PORT to match the actual external Docker mapping.
#
# Defaults in the systemd unit are 28006 (Hetzner-era). On GCP the
# happysixd container maps :8006, so the controller has to advertise 8006
# or the agent UI builds noVNC URLs pointing at port 28006 and shows black.
#
# Usage:
#   bash patch-public-ports.sh <gcp-vm-name> <external-port>
#   bash patch-public-ports.sh nubian-sandbox-spot 8006

set -euo pipefail

VM="${1:?need GCP VM name}"
PORT="${2:?need external port (e.g. 8006 for GCP, 28006 for Hetzner)}"

gcloud compute ssh "$VM" --command='curl -fsS --max-time 30 -X POST http://127.0.0.1:5000/execute \
  -H "Content-Type: application/json" \
  -d "{\"shell\":true,\"command\":\"
    sed -i \\\"s/PUBLIC_AGENT_PORT=[0-9]*/PUBLIC_AGENT_PORT='"$PORT"'/; s/PUBLIC_VNC_PORT=[0-9]*/PUBLIC_VNC_PORT='"$PORT"'/\\\" /home/user/.config/systemd/user/nubian-universal-computer-agent.service
    systemctl --user daemon-reload
    systemctl --user restart nubian-universal-computer-agent.service
    sleep 2
    curl -fsS http://127.0.0.1:6090/health | python3 -c \\\"import json,sys;print(json.load(sys.stdin)[\\\\\\\"ports\\\\\\\"])\\\"
  \"}"'
