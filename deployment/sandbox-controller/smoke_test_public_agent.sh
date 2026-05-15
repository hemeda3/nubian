#!/usr/bin/env bash
set -euo pipefail

BASE="${BASE:-http://YOUR_HOST:28006/agent}"
OUT_DIR="${OUT_DIR:-tmp/osworld-universal-agent-smoke}"
mkdir -p "$OUT_DIR"

echo "[smoke] health"
curl -fsS -m 20 "$BASE/health" | tee "$OUT_DIR/health.json" | python3 -m json.tool

echo "[smoke] screenshot"
curl -fsS -m 20 "$BASE/eyes/screenshot" -o "$OUT_DIR/screenshot.png"
file "$OUT_DIR/screenshot.png"

echo "[smoke] shell"
curl -fsS -m 20 -X POST "$BASE/shell/exec" \
  -H 'Content-Type: application/json' \
  --data '{"cmd":"whoami && hostname && pwd"}' | tee "$OUT_DIR/exec.json" | python3 -m json.tool

echo "[smoke] hands"
curl -fsS -m 20 -X POST "$BASE/hands/action" \
  -H 'Content-Type: application/json' \
  --data '{"actions":[{"type":"move","x":960,"y":540},{"type":"wait","seconds":0.2}]}' | tee "$OUT_DIR/action.json" | python3 -m json.tool
curl -fsS -m 20 -X POST "$BASE/hands/action" \
  -H 'Content-Type: application/json' \
  --data '{"actions":[{"type":"mouse_down","x":960,"y":540},{"type":"wait_ms","ms":50},{"type":"mouse_up","x":960,"y":540},{"type":"drag_between","from_x":960,"from_y":540,"to_x":961,"to_y":541,"duration":0.05}]}' | tee "$OUT_DIR/action-drag.json" | python3 -m json.tool
curl -fsS -m 20 -X POST "$BASE/hands/xdotool" \
  -H 'Content-Type: application/json' \
  --data '{"args":["getmouselocation"]}' | tee "$OUT_DIR/xdotool.json" | python3 -m json.tool

echo "[smoke] memory"
curl -fsS -m 20 -X POST "$BASE/memory/files" \
  -H 'Content-Type: application/json' \
  --data '{"path":"/workspace/agent-demo/smoke.md","content":"# OSWorld universal agent smoke\n"}' | tee "$OUT_DIR/file-write.json" | python3 -m json.tool
curl -fsS -m 20 "$BASE/memory/files?path=/workspace/agent-demo/smoke.md&format=text" | tee "$OUT_DIR/file-read.json" | python3 -m json.tool

echo "[smoke] skills"
curl -fsS -m 20 "$BASE/skills/search?q=open%20excelshieet%20student%20fake%20id%20studne%20name%20save" \
  | tee "$OUT_DIR/skills-search.json" | python3 -m json.tool
curl -fsS -m 20 -X POST "$BASE/skills/run" \
  -H 'Content-Type: application/json' \
  --data '{"skill_id":"create_spreadsheet_csv","path":"/workspace/agent-demo/students.csv","columns":["Student Fake ID","Student Name"],"rows":[["S001","Aisha Khan"],["S002","Omar Ali"],["S003","Maya Chen"],["S004","Noah Smith"]]}' \
  | tee "$OUT_DIR/skills-run.json" | python3 -m json.tool
curl -fsS -m 20 "$BASE/memory/files?path=/workspace/agent-demo/students.csv&format=text" \
  | tee "$OUT_DIR/skills-file-read.json" | python3 -m json.tool

echo "[smoke] windows"
curl -fsS -m 20 "$BASE/eyes/windows?limit=20" | tee "$OUT_DIR/windows.json" | python3 -m json.tool

echo "[smoke] input events"
curl -fsS -m 20 "$BASE/eyes/input-events?limit=20" | tee "$OUT_DIR/input-events.json" | python3 -m json.tool

echo "[smoke] accessibility events"
curl -fsS -m 20 "$BASE/eyes/accessibility-events?limit=20" | tee "$OUT_DIR/accessibility-events.json" | python3 -m json.tool

echo "[smoke] merged events"
curl -fsS -m 20 "$BASE/eyes/events?limit=40" | tee "$OUT_DIR/events.json" | python3 -m json.tool

echo "[smoke] observe"
curl -fsS -m 20 "$BASE/eyes/observe?limit=10&apps_limit=20&files_limit=10" | tee "$OUT_DIR/observe.json" | python3 -m json.tool
curl -fsS -m 20 "$BASE/apps/search?q=spreadsheet" | tee "$OUT_DIR/apps-search.json" | python3 -m json.tool

echo "[smoke] event index"
curl -fsS -m 20 "$BASE/eyes/events/index/status" | tee "$OUT_DIR/event-index-status.json" | python3 -m json.tool
curl -fsS -m 20 "$BASE/eyes/events/active-window" | tee "$OUT_DIR/event-active-window.json" | python3 -m json.tool
curl -fsS -m 20 "$BASE/eyes/events/recent?limit=20" | tee "$OUT_DIR/event-recent.json" | python3 -m json.tool
TARGET_SEQ="$(python3 - <<'PY'
import json
from pathlib import Path
d=json.loads(Path('tmp/osworld-universal-agent-smoke/event-recent.json').read_text())
print(d["events"][-1]["seq"] if d.get("events") else "")
PY
)"
if [ -n "$TARGET_SEQ" ]; then
  curl -fsS -m 20 "$BASE/eyes/events/around?seq=$TARGET_SEQ&before=3&after=3" | tee "$OUT_DIR/event-around.json" | python3 -m json.tool
fi
curl -fsS -m 20 "$BASE/eyes/events/context?limit=20" | tee "$OUT_DIR/event-context.json" | python3 -m json.tool
curl -fsS -m 20 -X POST "$BASE/eyes/sql" \
  -H 'Content-Type: application/json' \
  --data '{"sql":"SELECT stream, event, app, title, seq FROM events ORDER BY seq DESC","limit":5}' \
  | tee "$OUT_DIR/sql.json" | python3 -m json.tool

echo "[smoke] ok"
