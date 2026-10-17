#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if (( $# > 1 )); then echo "Usage: $0 [local-rom-path]" >&2; exit 2; fi
cmake -S "$root/wear/native" -B "$root/build/wear-native" -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build "$root/build/wear-native"
ctest --test-dir "$root/build/wear-native" --output-on-failure
if (( $# == 1 )); then
    rom=$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$1")
    cd "$root/build/wear-native"
    ./test_backend "$rom"
fi
