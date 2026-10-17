#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
# The inherited suite creates synthetic saves and uses loopback connections.
# Run it in its own ignored directory, away from any user firmware or saves.
mkdir -p "$root/build/upstream-desktop"
run_dir=$(mktemp -d "$root/build/upstream-desktop/run.XXXXXX")
sources=(
    tests/test_all.c
    src/cpu.c src/device.c src/disasm.c src/lcd.c src/link.c src/mem.c
    src/nfcpeer.c src/panel.c src/periph.c src/pn512.c src/savepath.c
    src/state.c src/tamasave.c src/desktop_tamasave.c
    tools/dlc.c tools/dlc_scan.c tools/dlc_names_4u.c tools/swapreq.c
)
for i in "${!sources[@]}"; do sources[$i]="$root/${sources[$i]}"; done
"${CC:-cc}" -std=gnu11 -O2 -Wall -Wextra \
    "${sources[@]}" -lm -o "$run_dir/test_all"
cd "$run_dir"
./test_all
