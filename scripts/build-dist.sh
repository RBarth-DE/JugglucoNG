#!/usr/bin/env bash
# Canonical, production-signed distribution build. Ordinary CI uses Gradle directly.
set -euo pipefail
cd "$(dirname "$0")/.."
target="${1:-phone}"
shift "$(( $# > 0 ? 1 : 0 ))"
case "$target" in phone|phone-all|wear|wear-all|all) ;; *) echo "Usage: $0 {phone|phone-all|wear|wear-all|all} [Gradle options]" >&2; exit 2 ;; esac
# ABI selection has one source, so verification cannot disagree with Gradle.
for arg in "$@"; do
  case "$arg" in -PjugglucoAbi=*|-Pthe*|-PrequireProductionSigning*) echo 'Use ORG_GRADLE_PROJECT_* or Gradle properties for ABI/signing configuration' >&2; exit 2 ;; esac
done
python3 scripts/dist/dist.py check-inputs --target "$target"
# Prevent a failed build from leaving a previous successful distribution in this target.
rm -rf "build/dist/$target"
tasks=()
while IFS= read -r task; do tasks+=("$task"); done < <(python3 scripts/dist/dist.py tasks --target "$target")
echo "Building production-signed distribution APK(s): $target"
./gradlew "${tasks[@]}" "$@" --stacktrace -PrequireProductionSigning=true
printf '\nBuilt APKs:\n'
python3 scripts/dist/dist.py stage --target "$target"
