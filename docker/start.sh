#!/usr/bin/env bash

set -euo pipefail

BASE_DIR="${BASE_DIR:-/application/huimao}"
LOG_DIR="${LOG_DIR:-$BASE_DIR/logs}"

declare -A SERVICE_DIRS=(
  [gateway]="huimao-gateway"
  [auth]="huimao-auth"
  [system]="huimao-system"
  [file]="huimao-file"
)

declare -A SERVICE_JARS=(
  [gateway]="ems-gateway.jar"
  [auth]="ems-auth.jar"
  [system]="ems-system.jar"
  [file]="ems-file.jar"
)

mkdir -p "$LOG_DIR"

start_service() {
  local key="$1"
  local dir="${SERVICE_DIRS[$key]}"
  local jar="$BASE_DIR/$dir/${SERVICE_JARS[$key]}"
  local log="$LOG_DIR/${key}.log"

  if [[ ! -f "$jar" ]]; then
    echo "[SKIP] $key: missing jar $jar"
    return
  fi

  if pgrep -f "java .*${SERVICE_JARS[$key]}" >/dev/null 2>&1; then
    echo "[OK] $key already running"
    return
  fi

  echo "[START] $key -> $jar"
  nohup java -Xms512m -Xmx512m -jar "$jar" > "$log" 2>&1 &
}

usage() {
  cat <<'EOF'
Usage:
  ./start.sh all
  ./start.sh gateway auth

Service names:
  gateway auth system file
EOF
}

if [[ $# -eq 0 ]]; then
  usage
  exit 1
fi

if [[ "${1:-}" == "all" ]]; then
  set -- gateway auth system file
fi

for service in "$@"; do
  if [[ -z "${SERVICE_DIRS[$service]:-}" ]]; then
    echo "[WARN] unknown service: $service"
    continue
  fi
  start_service "$service"
done
