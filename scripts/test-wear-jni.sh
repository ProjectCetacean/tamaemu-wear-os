#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if (( $# > 1 )); then echo "Usage: $0 [local-rom-path]" >&2; exit 2; fi
# Native-only CTest stays JDK-optional. This explicit JNI check requires a JDK.
if [[ -z ${JAVA_HOME:-} && -x "$root/.tooling/jdk-17.0.20.1+1/Contents/Home/bin/java" ]]; then
    export JAVA_HOME="$root/.tooling/jdk-17.0.20.1+1/Contents/Home"
fi
if [[ -z ${JAVA_HOME:-} || ! -x "$JAVA_HOME/bin/javac" ]]; then
    echo 'Set JAVA_HOME to JDK 17 before running the JNI regression.' >&2
    exit 2
fi
cmake -S "$root/wear/native" -B "$root/build/wear-jni-host" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$root/build/wear-jni-host" --target tamaemu_jni_host native_life_jni_classes
ctest --test-dir "$root/build/wear-jni-host" -R '^backend_jni$' --output-on-failure
if (( $# == 1 )); then
    "$JAVA_HOME/bin/java" -Djava.library.path="$root/build/wear-jni-host" \
        -cp "$root/build/wear-jni-host/jni-test-classes" \
        io.github.projectcetacean.tamaemu.LifeJniProbe "$1"
fi
