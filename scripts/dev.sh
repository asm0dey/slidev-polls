#!/usr/bin/env bash
set -euo pipefail

# Inner-loop dev: Postgres up, backend on :8080, two Vite dev servers
# proxying /api → :8080. Ctrl-C tears everything down.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cleanup() {
  echo "Stopping dev processes…"
  jobs -p | xargs -r kill 2>/dev/null || true
}
trap cleanup EXIT

docker compose -f "$ROOT/docker-compose.yml" up -d postgres

(cd "$ROOT" && ./mvnw quarkus:dev) &
(cd "$ROOT/frontends" && bun run --filter '@slidev-polls/voter'      dev --port 5173) &
(cd "$ROOT/frontends" && bun run --filter '@slidev-polls/backoffice' dev --port 5174) &

wait
