#!/usr/bin/env bash
# Android-free model mains only. Keep the production list explicit: no android.jar/JNI.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${JAVA_HOME:-}" ]]; then
    wear_javac="$JAVA_HOME/bin/javac"
    wear_java="$JAVA_HOME/bin/java"
else
    wear_javac=javac
    wear_java=java
fi
wear_out=wear/build/ui-host-tests
wear_src=wear/app/src/main/java/io/github/projectcetacean/tamaemu
wear_tests=wear/app/src/test/java/io/github/projectcetacean/tamaemu
mkdir -p "$wear_out"
wear_models=(TimeVerifier AudioPump AudioBackend EmulatorSession LifeLedger LifeOwner LifeScheduler LifeUiModel CommittedPreviewPublisher WatchGeometry UiPreferences ButtonState PetPreviewSnapshot PetPreviewStore TileCallbackQueue)
wear_mains=(AudioPassiveScalarsTest TimeVerifierTest AudioPumpTest AudioBackendTest EmulatorSessionTest LifeLedgerTest LifeSessionTest LifeSchedulerTest LifeUiModelTest CommittedPreviewPublisherTest WatchGeometryTest RecoveryLifecycleTest UiPreferencesTest ButtonStateTest PetPreviewStoreTest TileCallbackQueueTest)
wear_files=()
for model in "${wear_models[@]}"; do wear_files+=("$wear_src/$model.java"); done
for main in "${wear_mains[@]}"; do wear_files+=("$wear_tests/$main.java"); done
"$wear_javac" --release 8 -d "$wear_out" "${wear_files[@]}"
for main in "${wear_mains[@]}"; do "$wear_java" -cp "$wear_out" "io.github.projectcetacean.tamaemu.$main"; done
