#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/target/level3-prototype-classes"

rm -rf "$out"
mkdir -p "$out"

# --release cannot carry --add-exports for a system module. The source/target
# combination is only for the internal-API prototype; normal project builds
# continue to use Maven's release 25.
javac \
  -source 25 \
  -target 25 \
  --add-exports java.base/jdk.internal.vm=ALL-UNNAMED \
  -d "$out" \
  "$root/prototypes/level3/Level3BackendPrototype.java"

exec java \
  -Xms512m \
  -Xmx2g \
  --add-exports java.base/jdk.internal.vm=ALL-UNNAMED \
  -cp "$out" \
  Level3BackendPrototype "$@"
