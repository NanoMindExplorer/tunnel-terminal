#!/usr/bin/env bash
# Wave-7: local / CI-friendly unit test entrypoint
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew testFullDebugUnitTest --no-daemon --continue "$@"
