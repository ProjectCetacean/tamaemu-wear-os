#!/usr/bin/env bash
# Real native v4 writer -> production Java Tile reader, with local-only fixtures.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if (( $# > 1 )); then echo "Usage: $0 [local-rom-path]" >&2; exit 2; fi
if [[ -z ${JAVA_HOME:-} && -x "$root/.tooling/jdk-17.0.20.1+1/Contents/Home/bin/java" ]]; then
    export JAVA_HOME="$root/.tooling/jdk-17.0.20.1+1/Contents/Home"
fi
if [[ -z ${JAVA_HOME:-} || ! -x "$JAVA_HOME/bin/javac" ]]; then
    echo 'Set JAVA_HOME to JDK 17 before running preview interop.' >&2
    exit 2
fi
if ! "$JAVA_HOME/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "17[.]'; then
    echo 'Preview interop requires an existing JDK 17.' >&2
    exit 2
fi
"$root/scripts/test-wear-jni.sh"
preview_out=$(mktemp -d "$root/build/preview-jni-XXXXXX")
trap 'rm -rf "$preview_out"' EXIT
preview_src="$root/wear/app/src/main/java/io/github/projectcetacean/tamaemu"
"$JAVA_HOME/bin/javac" --release 8 -d "$preview_out" \
    "$preview_src/NativeBridge.java" "$preview_src/PetPreviewSnapshot.java" \
    "$preview_src/TimeVerifier.java" "$preview_src/LifeLedger.java" \
    "$preview_src/PetPreviewStore.java" "$root/scripts/java/PreviewJniProbe.java"
"$JAVA_HOME/bin/java" -Djava.library.path="$root/build/wear-jni-host" \
    -Dpreview.fixture.root="$preview_out" -cp "$preview_out" \
    io.github.projectcetacean.tamaemu.PreviewJniProbe "$@"
