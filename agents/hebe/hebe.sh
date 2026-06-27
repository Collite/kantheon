#!/usr/bin/env bash
# Run Hebe's cli-app from sources via the kantheon root Gradle build.
# (P1 Stage 1.1 — the standalone gradlew was retired by the gradle merge; this
# now drives the root build at the `:agents:hebe:modules:cli-app` path.)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
exec "$ROOT/gradlew" --quiet --console=plain :agents:hebe:modules:cli-app:run --args="$*"
