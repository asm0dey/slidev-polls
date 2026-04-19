#!/usr/bin/env bash
set -euo pipefail

# Build every frontend package and copy the built SPA bundles into
# poll-api's static resources so the single-JAR deployment serves them.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATIC="$ROOT/backend/poll-api/src/main/resources/static"

cd "$ROOT/frontends"
bun install

bun run --filter '@polls/shared'          build
bun run --filter '@polls/voter'           build
bun run --filter '@polls/backoffice'      build
bun run --filter '@polls/slidev-addon'    build

mkdir -p "$STATIC" "$STATIC/admin"
rm -rf "$STATIC"/* "$STATIC/admin"/*

cp -r "$ROOT/frontends/voter/dist/."       "$STATIC/"
cp -r "$ROOT/frontends/backoffice/dist/."  "$STATIC/admin/"

echo "Frontends built and staged into $STATIC"
