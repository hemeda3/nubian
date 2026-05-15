# GCP Spot VM Sandbox

End-to-end recipe: spin up a Google Cloud spot VM with nested KVM, boot
`happysixd/osworld-docker`, install the Nubian Python controller into the
guest VM, and wire the local agent to drive it.

This guide covers the full path, not just the container. Skipping any step
leaves you with a half-working sandbox.

---

## Prereqs

- `gcloud` installed + `gcloud auth login`
- A GCP project with billing on, Compute Engine API enabled

---

## 1. Bring up the spot VM + sandbox container

```bash
PROJECT=<your-gcp-project> SOURCE_IP=0.0.0.0/0 \
  ./deployment/gcloud-spot-sandbox.sh up
```

Output ends with:
```
External IP: <IP>
  noVNC:  http://<IP>:8006
  API:    http://<IP>:5000
```

Wait ~10–15 min the first time (downloads `Ubuntu.qcow2` ~10 GB + boots
QEMU with KVM). Watch with `./deployment/gcloud-spot-sandbox.sh logs`.

When the noVNC URL responds, the Ubuntu desktop is up but **the Nubian
controller is not installed yet**. The OSWorld container only ships with
its own benchmark agent on `:5000`, which speaks different endpoints than
Nubian's `/eyes/screenshot` / `/hands/action` API.

---

## 2. Install the Nubian controller into the guest VM

The controller lives in `deployment/sandbox-controller/`. To install it:

```bash
# Copy the controller package to the spot VM host
gcloud compute scp --zone=us-central1-a --project=<your-gcp-project> \
  --recurse deployment/sandbox-controller \
  nubian-sandbox-spot:/tmp/

# SSH in and install into the guest via the OSWorld /setup/upload + /execute API
gcloud compute ssh nubian-sandbox-spot --zone=us-central1-a --project=<your-gcp-project> --command='
  cd /tmp/sandbox-controller
  DIR=/home/user/.local/share/nubian-universal-computer-agent
  SVC=/home/user/.config/systemd/user
  API=http://127.0.0.1:5000

  for f in computer_agent.py window_tracker_x11.py xinput_tracker.py atspi_tracker.py \
           event_indexer.py screen_recorder.py recent_event_text.py \
           container_port_forwarder.py ubuntu_desktop_tweaks.sh; do
    curl -fsS --max-time 30 -X POST "$API/setup/upload" \
      -F "file_path=$DIR/$f" -F "file_data=@$f" -o /dev/null && echo "  uploaded $f"
  done

  for unit in nubian-universal-computer-agent nubian-x11-window-tracker \
              nubian-xinput-tracker nubian-atspi-tracker \
              nubian-event-indexer nubian-screen-recorder; do
    curl -fsS --max-time 30 -X POST "$API/setup/upload" \
      -F "file_path=$SVC/$unit.service" -F "file_data=@$unit.service" -o /dev/null && echo "  uploaded $unit.service"
  done

  # Run the install script (apt + pip + systemctl enable + start)
  python3 -c "
import json, urllib.request, pathlib
cmd = pathlib.Path(\"install-command.sh\").read_text()
data = json.dumps({\"shell\": True, \"command\": cmd}).encode()
req = urllib.request.Request(\"http://127.0.0.1:5000/execute\", data=data,
                              headers={\"Content-Type\": \"application/json\"},
                              method=\"POST\")
print(urllib.request.urlopen(req, timeout=600).read().decode()[-500:])
"
'
```

After this, the guest VM has the Nubian controller running on its
`127.0.0.1:6090`. But it's not yet reachable from outside the container.

---

## 3. Expose the controller through container nginx

The container's nginx serves noVNC on `:8006`. We add a `/agent/` location
that reverse-proxies to the guest VM IP `20.20.20.21:6090`:

```bash
gcloud compute ssh nubian-sandbox-spot --zone=us-central1-a --project=<your-gcp-project> --command='
sudo docker exec -i nubian-sandbox sh -s <<"EOS"
  set -e
  awk "
    /location = \/agent/ {skip=1; next}
    /location \/agent\// {skip=1; next}
    skip && /^    }/ {skip=0; next}
    !skip {print}
  " /etc/nginx/sites-enabled/web.conf > /tmp/web.clean
  head -n -1 /tmp/web.clean > /tmp/web.new
  cat >> /tmp/web.new << "NEOF"

    location = /agent { return 301 /agent/; }

    location /agent/ {
      proxy_http_version 1.1;
      proxy_buffering off;
      proxy_read_timeout 3600s;
      proxy_send_timeout 3600s;
      proxy_pass http://20.20.20.21:6090/;
    }
}
NEOF
  mv /tmp/web.new /etc/nginx/sites-enabled/web.conf
  nginx -t && nginx -s reload
EOS
'
```

Verify:
```bash
curl -fsS http://<IP>:8006/agent/health | jq .ok   # expect: true
```

---

## 4. Patch the controller's public-port advertisement

The controller defaults to advertising `PUBLIC_AGENT_PORT=28006` and
`PUBLIC_VNC_PORT=28006` (Hetzner-era port mapping). On GCP we expose
`:8006` instead. Patch the systemd unit:

```bash
gcloud compute ssh nubian-sandbox-spot --zone=us-central1-a --project=<your-gcp-project> --command='
curl -fsS -X POST http://127.0.0.1:5000/execute \
  -H "Content-Type: application/json" \
  -d "{\"shell\": true, \"command\": \"
    sed -i \\\"s/PUBLIC_AGENT_PORT=28006/PUBLIC_AGENT_PORT=8006/; s/PUBLIC_VNC_PORT=28006/PUBLIC_VNC_PORT=8006/\\\" /home/user/.config/systemd/user/nubian-universal-computer-agent.service
    systemctl --user daemon-reload
    systemctl --user restart nubian-universal-computer-agent.service
    sleep 2
    curl -fsS http://127.0.0.1:6090/health | python3 -c \\\"import json,sys; print(json.load(sys.stdin)[\\\\\\\"ports\\\\\\\"])\\\"
  \"}"
'
```

Output should show `{'agent_internal': 6090, 'agent_public': 8006, 'vnc_public': 8006}`.

---

## 5. Wire the local agent at the GCP VM

Add to `.env`:

```bash
NUBIAN_SANDBOX_COMPUTER_AGENT_HOST=<IP>
NUBIAN_SANDBOX_COMPUTER_AGENT_AGENT_PORT=8006
NUBIAN_SANDBOX_COMPUTER_AGENT_BASE_PATH=/agent
NUBIAN_SANDBOX_COMPUTER_AGENT_NOVNC_PORT=8006
```

Then restart the agent:

```bash
./start.sh
```

Open `http://localhost:7070/demo/computer` — it should connect to the GCP
spot VM and show the 1024×1024 Ubuntu desktop in the noVNC iframe.

---

## Lifecycle commands

```bash
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh ip
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh ssh
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh stop
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh start
PROJECT=<your-gcp-project> ./deployment/gcloud-spot-sandbox.sh down
```

`stop` keeps the disk + qcow2 (no re-download on resume).
`down` destroys VM + firewall rule.

---

## Spot preemption recovery

When GCP reclaims the spot VM:
- VM state goes `TERMINATED`
- Disk is preserved (because `--instance-termination-action=STOP`)
- Container has `--restart unless-stopped` so it auto-restarts on resume

Just run `./deployment/gcloud-spot-sandbox.sh start`. The Nubian controller
in the guest VM persists across stops (it's installed in the qcow2 disk).

If the external IP changes after restart, update `.env`'s
`NUBIAN_SANDBOX_COMPUTER_AGENT_HOST` and restart the local agent.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `/agent/health` 404 | nginx proxy not added | re-run step 3 (the `awk` patch) |
| `/agent/health` 502 | Controller not running in guest | re-run step 2 (install) |
| noVNC iframe black, URL says `:28006` | Controller still reports old port | re-run step 4 (PUBLIC_AGENT_PORT patch) |
| noVNC iframe black, URL says `localhost` | Agent's NUBIAN_SANDBOX_COMPUTER_AGENT_HOST not set | check `.env` and restart |
| `Could not resolve placeholder 'GEMINI_API_KEY'` | LLM key not in `.env` | set it and restart |
| `File API failed: IO error path=/workspace` | Controller endpoint unreachable | step 2 + step 3 not done |
| 23 GB consumed but no qcow2 | Failed unzip mid-run | re-run `download-osworld-vm.sh` |

---

## Cost (back-of-envelope)

| State | Rate | Notes |
|-------|------|-------|
| `up` and running | ~$0.10/hr | n2-standard-8 spot, us-central1 |
| `stop` (disk only) | ~$0.01/hr | 80 GB pd-balanced |
| `down` | $0 | full teardown |

Same shape on-demand is ~$0.39/hr. Use `--provisioning-model=STANDARD` in
the script if you need a non-preemptible VM.
