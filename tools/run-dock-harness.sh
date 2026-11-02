#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export OTO_DOCK_HARNESS=1
exec "$project_dir/gradlew" -p "$project_dir" :desktopApp:run "$@"
