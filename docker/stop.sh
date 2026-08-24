#!/usr/bin/env bash

set -euo pipefail

BASE_DIR="${BASE_DIR:-/application/huimao}"

declare -A SERVICE_JARS=(
  [gateway]="ems-gateway.jar"
  [auth]="ems-auth.jar"
  [system]="ems-system.jar"
  [file]="ems-file.jar"
  [ems_server]="ems_server.jar"
  [parking]="witos-parking.jar"
  [simulator]="witos-parking-simulator.jar"
)

stop_service() {
  local key="$1"
  local jar="${SERVICE_JARS[$key]}"
  local pids
  pids="$(pgrep -f "java .*${jar}" || true)"
  if [[ -z "$pids" ]]; then
    echo "[OK] $key not running"
    return
  fi
  echo "[STOP] $key"
  kill $pids || true
}

usage() {
  cat <<'EOF'
Usage:
  ./stop.sh all
  ./stop.sh gateway auth
  ./stop.sh ems_server parking simulator

Service names:
  gateway auth system file ems_server parking simulator
EOF
}

if [[ $# -eq 0 ]]; then
  usage
  exit 1
fi

if [[ "${1:-}" == "all" ]]; then
  set -- gateway auth system file ems_server parking simulator
fi

for service in "$@"; do
  if [[ -z "${SERVICE_JARS[$service]:-}" ]]; then
    echo "[WARN] unknown service: $service"
    continue
  fi
  stop_service "$service"
done
