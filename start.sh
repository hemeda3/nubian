#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [[ -f ".env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export SERVER_PORT="${SERVER_PORT:-7070}"
export NUBIAN_SANDBOX_PROVIDER="${NUBIAN_SANDBOX_PROVIDER:-firecracker}"
export NUBIAN_SANDBOX_FIRECRACKER_ENABLED="${NUBIAN_SANDBOX_FIRECRACKER_ENABLED:-true}"
export NUBIAN_SANDBOX_DOCKER_ENABLED="${NUBIAN_SANDBOX_DOCKER_ENABLED:-false}"
export NUBIAN_MIND_DEBUG_DIR="${NUBIAN_MIND_DEBUG_DIR:-tmp/mind-debug/prompts-req}"

# FlyVM API configuration. Defaults attach to the live public MVP VM instead
# of provisioning a new computer, so local app startup is deterministic.
export NUBIAN_FLYVM_API_BASE="${NUBIAN_FLYVM_API_BASE:-http://localhost:19191}"
export NUBIAN_FLYVM_STATIC_VM_ID="${NUBIAN_FLYVM_STATIC_VM_ID:-}"
export NUBIAN_FLYVM_DIRECT_NOVNC_URL="${NUBIAN_FLYVM_DIRECT_NOVNC_URL:-http://localhost:6080}"
export NUBIAN_FLYVM_REGION="${NUBIAN_FLYVM_REGION:-}"
export NUBIAN_FLYVM_REQUIRED_PROVIDER="${NUBIAN_FLYVM_REQUIRED_PROVIDER:-}"
export NUBIAN_FLYVM_REPAIR_NOVNC="${NUBIAN_FLYVM_REPAIR_NOVNC:-false}"
export FLYVM_TOKEN="${FLYVM_TOKEN:-}"
export FLYVM_API_KEY="${FLYVM_API_KEY:-}"
export FLYVM_JWT_SECRET="${FLYVM_JWT_SECRET:-}"
export FLYVM_TENANT_ID="${FLYVM_TENANT_ID:-}"
export FLYVM_JWT_ISSUER="${FLYVM_JWT_ISSUER:-flyvm-auth-service}"
export FLYVM_JWT_AUDIENCE="${FLYVM_JWT_AUDIENCE:-flyvm-api}"
export FLYVM_JWT_TIER="${FLYVM_JWT_TIER:-enterprise}"
export FLYVM_JWT_QUERY_LIMIT="${FLYVM_JWT_QUERY_LIMIT:-1000000}"
export FLYVM_JWT_TTL="${FLYVM_JWT_TTL:-1h}"

SPRING_ADDITIONAL_CONFIG="$ROOT_DIR/config/application-dev.properties"
if [[ -f "$SPRING_ADDITIONAL_CONFIG" ]]; then
  export GCP_API_KEY="${GCP_API_KEY:-$(awk -F= '$1=="GCP_API_KEY"{print substr($0,index($0,"=")+1); exit}' "$SPRING_ADDITIONAL_CONFIG")}"
  export OPENROUTER_API_KEY="${OPENROUTER_API_KEY:-$(awk -F= '$1=="OPENROUTER_API_KEY"{print substr($0,index($0,"=")+1); exit}' "$SPRING_ADDITIONAL_CONFIG")}"
  export DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-$(awk -F= '$1=="DEEPSEEK_API_KEY"{print substr($0,index($0,"=")+1); exit}' "$SPRING_ADDITIONAL_CONFIG")}"
  export OPENAI_API_KEY="${OPENAI_API_KEY:-$(awk -F= '$1=="OPENAI_API_KEY"{print substr($0,index($0,"=")+1); exit}' "$SPRING_ADDITIONAL_CONFIG")}"
fi

have() {
  command -v "$1" >/dev/null 2>&1
}

port_busy() {
  local port="$1"
  if have lsof; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  elif have nc; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
  else
    return 1
  fi
}

port_pids() {
  local port="$1"
  if have lsof; then
    local found
    found="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -n "$found" ]]; then
      printf '%s\n' "$found" | sort -u
    fi
  fi
  return 0
}

wait_port_free() {
  local port="$1"
  local attempts="${2:-50}"
  local i
  for i in $(seq 1 "$attempts"); do
    if ! port_busy "$port"; then
      return 0
    fi
    sleep 0.2
  done
  return 1
}

kill_port_listener() {
  local port="$1"
  local pids
  pids="$(port_pids "$port" || true)"
  if [[ -z "$pids" ]]; then
    if port_busy "$port"; then
      echo "Port $port is busy, but lsof is not available to identify the listener." >&2
      exit 1
    fi
    return 0
  fi

  echo "Port $port is busy; stopping listener PID(s): ${pids//$'\n'/ }"
  kill $pids >/dev/null 2>&1 || true
  if wait_port_free "$port" 40; then
    echo "Port $port is free."
    return 0
  fi

  pids="$(port_pids "$port" || true)"
  if [[ -n "$pids" ]]; then
    echo "Port $port did not stop cleanly; force-killing PID(s): ${pids//$'\n'/ }" >&2
    kill -9 $pids >/dev/null 2>&1 || true
  fi
  if ! wait_port_free "$port" 25; then
    echo "Could not free port $port." >&2
    exit 1
  fi
  echo "Port $port is free."
}

kill_process_tree() {
  local pid="${1:-}"
  local signal="${2:-TERM}"
  local child
  if [[ -z "$pid" ]] || ! kill -0 "$pid" >/dev/null 2>&1; then
    return 0
  fi
  if have pgrep; then
    for child in $(pgrep -P "$pid" 2>/dev/null || true); do
      kill_process_tree "$child" "$signal"
    done
  fi
  kill "-$signal" "$pid" >/dev/null 2>&1 || true
}

require_cmd() {
  local cmd="$1"
  if ! have "$cmd"; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
}

require_cmd java
require_cmd mvn

if [[ "$NUBIAN_SANDBOX_PROVIDER" != "firecracker" ]]; then
  echo "This start script is configured for the FlyVM API provider only." >&2
  echo "Set NUBIAN_SANDBOX_PROVIDER=firecracker or edit the script intentionally." >&2
  exit 1
fi

if [[ -z "${FLYVM_TOKEN:-}" && -z "${FLYVM_API_KEY:-}" && ( -z "${FLYVM_JWT_SECRET:-}" || -z "${FLYVM_TENANT_ID:-}" ) ]]; then
  cat >&2 <<'EOF'
Info: no FlyVM auth found. That is OK for the current public computer MVP API.
For a secured FlyVM API, set one of:
  export FLYVM_TOKEN=...
  export FLYVM_API_KEY=...
  export FLYVM_JWT_SECRET=... FLYVM_TENANT_ID=...
EOF
fi

if [[ -z "${OPENROUTER_API_KEY:-}" && -z "${OPENAI_API_KEY:-}" && -z "${GCP_API_KEY:-}" ]]; then
  cat >&2 <<'EOF'
Warning: no LLM API key found.
Set one before running real agent tasks, for example:
  export OPENROUTER_API_KEY=...
  export OPENAI_API_KEY=...
  export GCP_API_KEY=...
EOF
fi

kill_port_listener "$SERVER_PORT"

echo "Building Nubian app and installing local module dependencies..."
mvn -pl nubian-app -am -Dmaven.test.skip=true install

kill_port_listener "$SERVER_PORT"

cat <<EOF

Starting demo server...
Open this URL after startup finishes:
  http://localhost:${SERVER_PORT}/demo/computer

Sandbox provider:
  ${NUBIAN_SANDBOX_PROVIDER} (FlyVM API)

FlyVM API:
  ${NUBIAN_FLYVM_API_BASE}

Raw LLM requests will be written under:
  ${NUBIAN_MIND_DEBUG_DIR}

Press Ctrl+C to stop.

EOF

APP_PID=""

cleanup() {
  local status="${1:-0}"
  trap - INT TERM EXIT
  if [[ -n "${APP_PID:-}" ]] && kill -0 "$APP_PID" >/dev/null 2>&1; then
    echo
    echo "Stopping demo server PID $APP_PID..."
    kill_process_tree "$APP_PID" TERM
    local i
    for i in $(seq 1 40); do
      if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
        break
      fi
      sleep 0.2
    done
    if kill -0 "$APP_PID" >/dev/null 2>&1; then
      echo "Demo server did not stop cleanly; force-killing PID $APP_PID..." >&2
      kill_process_tree "$APP_PID" KILL
    fi
    wait "$APP_PID" >/dev/null 2>&1 || true
  fi
  APP_PID=""
  if wait_port_free "$SERVER_PORT" 40; then
    echo "Demo server stopped; port $SERVER_PORT is free."
  else
    echo "Demo server stopped, but port $SERVER_PORT is still busy." >&2
  fi
  exit "$status"
}

trap 'cleanup 130' INT
trap 'cleanup 143' TERM
trap 'cleanup $?' EXIT

mvn -pl nubian-app spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=$SPRING_PROFILES_ACTIVE \
  -Dspring.config.additional-location=optional:file:$SPRING_ADDITIONAL_CONFIG \
  -Dserver.port=$SERVER_PORT \
  -Dspring.ai.model.chat=none \
  -Dspring.ai.model.embedding=none \
  -Dspring.ai.model.image=none \
  -Dspring.ai.model.moderation=none \
  -Dspring.ai.model.audio.speech=none \
  -Dspring.ai.model.audio.transcription=none \
  -Dnubian.sandbox.provider=$NUBIAN_SANDBOX_PROVIDER \
  -Dnubian.sandbox.firecracker.enabled=$NUBIAN_SANDBOX_FIRECRACKER_ENABLED \
  -Dnubian.sandbox.docker.enabled=false \
  -Dnubian.sandbox.local.enabled=false" &

APP_PID="$!"
wait "$APP_PID"
APP_STATUS="$?"
APP_PID=""
exit "$APP_STATUS"
