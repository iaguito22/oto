#!/usr/bin/env bash
# Tests de los módulos que cambian respecto a main (o todos con --all).
# Tocar :core o :innertube también prueba :app y :desktopApp.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-21-openjdk ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
fi

base="${CHECK_BASE:-origin/main}"
if [[ "${1:-}" == "--all" ]]; then
  changed="core/ desktopApp/ app/"
else
  changed="$( { git diff --name-only "$base"...HEAD 2>/dev/null; git diff --name-only HEAD; git ls-files --others --exclude-standard; } | sort -u )"
fi

tasks=()
want() { grep -qE "^($1)" <<<"$changed"; }
shared=0
want 'core/|innertube/|gradle/|build.gradle.kts|settings.gradle.kts' && shared=1
(( shared )) && tasks+=(:core:test)
if (( shared )) || want 'desktopApp/'; then tasks+=(:desktopApp:test); fi
if (( shared )) || want 'app/'; then
  if [[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]] || grep -qs '^sdk.dir' local.properties; then
    tasks+=(:app:testDebugUnitTest)
  else
    echo "Aviso: sin Android SDK; se omiten los tests de :app (los pasa la CI)." >&2
  fi
fi

if (( ${#tasks[@]} == 0 )); then
  echo "Sin cambios en código Gradle: nada que probar."
  exit 0
fi
echo "Ejecutando: ${tasks[*]}"
exec ./gradlew "${tasks[@]}" --continue
