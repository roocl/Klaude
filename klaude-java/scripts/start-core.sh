#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LAUNCHER="$ROOT/build/install/klaude-core-java/bin/klaude-core-java"
if [ "${1:-}" = "--print-command" ]; then
  echo "runtime=java"
  echo "command=$LAUNCHER"
  exit 0
fi
if [ ! -x "$LAUNCHER" ]; then
  "$ROOT/gradlew" --no-daemon installDist
fi
exec "$LAUNCHER" "$@"
