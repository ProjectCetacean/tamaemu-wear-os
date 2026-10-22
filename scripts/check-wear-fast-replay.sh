#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if (( $# != 1 )); then echo "Usage: $0 external-build-directory" >&2; exit 2; fi
out=$1
mkdir -p "$out"
cc=${CC:-cc}
flags=(-std=c11 -O2 -Wall -Wextra -I"$root/src" -I"$root/wear/native")
"$cc" "${flags[@]}" -Dcpu_step=fast_counted_cpu_step -Dcpu_skip_idle=fast_counted_cpu_skip_idle \
  -Dperiph_tick_at=fast_counted_periph_tick_at -c "$root/wear/native/backend.c" -o "$out/backend-counted.o"
"$cc" "${flags[@]}" "$root/wear/native/test_replay_fast_idle.c" "$out/backend-counted.o" \
  "$root/wear/native/test_replay_reference_backend.c" "$root/wear/native/test_replay_reference_periph.c" \
  "$root/src/"{cpu,mem,periph,lcd,device,disasm,pn512}.c \
  "$root/wear/native/standalone.c" "$root/wear/native/sha256.c" -lm -o "$out/test_replay_fast_idle"
"$out/test_replay_fast_idle"
