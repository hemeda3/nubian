#!/usr/bin/env bash
# Fetch the OSWorld benchmark Ubuntu VM disk and unpack it for docker-compose.
# Run once before the first `docker compose up sandbox`.

set -euo pipefail

URL="https://huggingface.co/datasets/xlangai/ubuntu_osworld/resolve/main/Ubuntu.qcow2.zip"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VMS_DIR="$SCRIPT_DIR/vms"
ZIP="$VMS_DIR/Ubuntu.qcow2.zip"
QCOW="$VMS_DIR/Ubuntu.qcow2"

mkdir -p "$VMS_DIR"

if [[ -f "$QCOW" ]]; then
    SIZE_GB=$(du -h "$QCOW" | cut -f1)
    echo "[ok] Ubuntu.qcow2 already present ($SIZE_GB at $QCOW)"
    exit 0
fi

echo "[1/3] Downloading Ubuntu.qcow2.zip from Hugging Face (~10 GB, resumable)..."
curl -fL --retry 3 --retry-delay 5 -C - -o "$ZIP" "$URL"

echo "[2/3] Unzipping into $VMS_DIR ..."
unzip -o "$ZIP" -d "$VMS_DIR"

# The zip may contain a nested path — flatten it.
FOUND=$(find "$VMS_DIR" -maxdepth 3 -name 'Ubuntu.qcow2' | head -1 || true)
if [[ -n "$FOUND" && "$FOUND" != "$QCOW" ]]; then
    mv "$FOUND" "$QCOW"
fi

echo "[3/3] Cleaning up the zip ..."
rm -f "$ZIP"

SIZE_GB=$(du -h "$QCOW" | cut -f1)
echo "[done] Ubuntu.qcow2 ready ($SIZE_GB at $QCOW)"
echo "       Now run:  docker compose up -d sandbox"
echo "       Then:     open http://localhost:8006"
