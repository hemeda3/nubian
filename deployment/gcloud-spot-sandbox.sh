#!/usr/bin/env bash
# Spin up a GCP spot VM with nested KVM, install Docker, and run the OSWorld
# sandbox container with KVM acceleration. One-shot: paste, run, get an IP.
#
# REQUIRED env vars (no defaults — script will refuse to run without them):
#   PROJECT     GCP project ID
#   SOURCE_IP   CIDR allowed through firewall, e.g. 1.2.3.4/32 or 0.0.0.0/0
#
# Optional (have safe defaults):
#   ZONE        default us-central1-a
#   NAME        default nubian-sandbox-spot
#   MACHINE     default n2-standard-8 (nested-virt capable)
#   DISK_GB     default 80
#   FW_RULE     default nubian-sandbox-novnc
#
# Usage:
#   PROJECT=my-proj SOURCE_IP=1.2.3.4/32 ./gcloud-spot-sandbox.sh up
#   PROJECT=my-proj ./gcloud-spot-sandbox.sh ip
#   PROJECT=my-proj ./gcloud-spot-sandbox.sh logs|ssh|stop|start|down

set -euo pipefail

PROJECT="${PROJECT:?set PROJECT env var to your GCP project id}"
ZONE="${ZONE:-us-central1-a}"
NAME="${NAME:-nubian-sandbox-spot}"
MACHINE="${MACHINE:-n2-standard-8}"
DISK_GB="${DISK_GB:-80}"
FW_RULE="${FW_RULE:-nubian-sandbox-novnc}"

gcloud config set project "$PROJECT" >/dev/null

cmd_up() {
  : "${SOURCE_IP:?set SOURCE_IP env var (CIDR allowed through firewall, e.g. 1.2.3.4/32 or 0.0.0.0/0)}"

  local startup
  startup=$(mktemp -t nubian-startup.XXXX)
  trap 'rm -f "$startup"' RETURN
  cat > "$startup" <<'STARTUP'
#!/bin/bash
set -e
exec > /var/log/startup.log 2>&1
echo "[startup] apt install ..."
apt-get update
apt-get install -y docker.io qemu-kvm unzip curl
systemctl enable --now docker
usermod -aG kvm,docker ubuntu

echo "[startup] downloading Ubuntu.qcow2 (~10 GB) ..."
mkdir -p /opt/nubian/vms && cd /opt/nubian
curl -fL --retry 3 -C - -o vms/Ubuntu.qcow2.zip \
  https://huggingface.co/datasets/xlangai/ubuntu_osworld/resolve/main/Ubuntu.qcow2.zip
unzip -o vms/Ubuntu.qcow2.zip -d vms/
FOUND=$(find vms -maxdepth 3 -name Ubuntu.qcow2 | head -1)
if [ "$FOUND" != "vms/Ubuntu.qcow2" ]; then mv "$FOUND" vms/Ubuntu.qcow2; fi
rm -f vms/Ubuntu.qcow2.zip

echo "[startup] launching sandbox container ..."
docker run -d --name nubian-sandbox \
  --privileged --cap-add NET_ADMIN \
  --device /dev/kvm \
  -e DISK_SIZE=32G -e RAM_SIZE=8G -e CPU_CORES=6 -e KVM=Y \
  -v /opt/nubian/vms/Ubuntu.qcow2:/System.qcow2:ro \
  -p 8006:8006 -p 5000:5000 -p 9222:9222 -p 8080:8080 -p 5900:5900 \
  --restart unless-stopped \
  happysixd/osworld-docker:latest

echo "[startup] done. http://<this-ip>:8006"
STARTUP

  echo "[1/3] Creating spot VM ${NAME} in ${ZONE} ..."
  gcloud compute instances create "$NAME" \
    --zone="$ZONE" \
    --machine-type="$MACHINE" \
    --enable-nested-virtualization \
    --provisioning-model=SPOT \
    --instance-termination-action=STOP \
    --image-family=ubuntu-2204-lts \
    --image-project=ubuntu-os-cloud \
    --boot-disk-size="${DISK_GB}GB" \
    --boot-disk-type=pd-balanced \
    --tags=nubian-sandbox \
    --metadata-from-file="startup-script=$startup"

  echo "[2/3] Opening firewall ${FW_RULE} for ${SOURCE_IP} ..."
  gcloud compute firewall-rules create "$FW_RULE" \
    --allow=tcp:8006,tcp:5000,tcp:9222,tcp:5900,tcp:8080 \
    --target-tags=nubian-sandbox \
    --source-ranges="$SOURCE_IP" \
    --quiet || true

  IP=$(gcloud compute instances describe "$NAME" --zone="$ZONE" \
       --format='value(networkInterfaces[0].accessConfigs[0].natIP)')
  echo "[3/3] VM up. External IP: $IP"
  echo
  echo "  noVNC:    http://$IP:8006"
  echo "  API:      http://$IP:5000"
  echo
  echo "First boot downloads ~10 GB and boots the VM. Watch progress:"
  echo "  $0 logs"
}

cmd_ip()    { gcloud compute instances describe "$NAME" --zone="$ZONE" --format='value(networkInterfaces[0].accessConfigs[0].natIP)'; }
cmd_logs()  { gcloud compute ssh "$NAME" --zone="$ZONE" -- 'sudo tail -f /var/log/startup.log'; }
cmd_ssh()   { gcloud compute ssh "$NAME" --zone="$ZONE"; }
cmd_stop()  { gcloud compute instances stop  "$NAME" --zone="$ZONE"; }
cmd_start() { gcloud compute instances start "$NAME" --zone="$ZONE"; }
cmd_down()  {
  gcloud compute instances delete "$NAME" --zone="$ZONE" --quiet || true
  gcloud compute firewall-rules delete "$FW_RULE" --quiet || true
}

case "${1:-up}" in
  up)    cmd_up    ;;
  ip)    cmd_ip    ;;
  logs)  cmd_logs  ;;
  ssh)   cmd_ssh   ;;
  stop)  cmd_stop  ;;
  start) cmd_start ;;
  down)  cmd_down  ;;
  *) echo "usage: $0 {up|ip|logs|ssh|stop|start|down}"; exit 1 ;;
esac
