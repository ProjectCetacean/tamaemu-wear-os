# Tamaemu on Wear OS

Version `0.2.0-wear.10` is unreleased. The app uses compatible, user-supplied iD L firmware; see [release notes](RELEASE_NOTES.md) for current changes.

## Controls and settings

Use the on-screen A/B/C buttons. Hold the display area to open **Settings**; choose **Done** or Back to return. With **Retractable** controls, tap the display area to hide or reveal them. A display gesture during a held A/B/C contact is ignored. Opening Settings or leaving care releases held inputs.

Settings include:

| Setting | Behavior |
| --- | --- |
| Display size | Small, Balanced or Large, independent of control visibility. |
| Integer pixels | Fits a whole multiple of the original 128-pixel LCD where possible; the row reports the actual factor and pixel size. Below 128 pixels it reports a Fit fallback. |
| Control space | Own space reserves button room; Over display can cover part of the LCD while controls are visible. Hide/reveal keeps the selected LCD bounds. |
| Position | Bottom, Left, Right or Top. Settings reports a Bottom fallback when the requested position cannot fit. |
| Display effect | Clean, Soft LCD or LCD Glass. |
| LCD sharpness | Off, Slight, Medium or Strong; Slight is the default. |
| Appearance | Button shape and color, shell color, and Reset appearance. |
| Watch behavior | Sound On/Off, light haptics, Keep awake or System timeout. |

Reset appearance preserves sound, haptics and timeout preferences. Display settings do not change the pet's game state. Visual quality on different watches and physical accessibility behavior still need testing.

Sound plays only while the app is in the foreground with audio focus. Away replay is silent. Rapid-press tone lag and continuity remain unresolved; acoustic input-to-speaker delay has not been established. The app does not set the pet's clock from the watch clock.

## Life between visits

**Normal** records elapsed away time and replays it when Android permits, with remaining catch-up completed on reopening. The firmware's original care consequences apply. Background jobs have a 60-second replay allowance on a 15-minute periodic schedule. Android can delay or stop them; registration does not guarantee execution. Long gaps can still require catch-up on reopening, and delayed work does not erase owed time.

**Protected** pauses future away-time accumulation. Existing owed time must finish before switching modes, and a mode change takes effect only after its checkpoint saves. Foreground time still advances. Protected provides no automatic care or harm prevention.

Catch-up batches idle CPU work and timer comparisons while preserving elapsed time and save behavior. Repeated progress updates are limited to 10 per second; state and error messages are not delayed by that limit. On reopening, care resumes after required replay and saving finish.

Accounting uses elapsed time within a known boot and nonnegative UTC elapsed time across reboot or an unavailable boot clock. Forward clock changes can add aging. Backward changes add no new time and keep existing owed time.

If saving fails, keep the app and its files intact, free storage, then use **Retry**. Recovery after an unconfirmed session returns to the last durable checkpoint and can lose recent input. Do not uninstall or clear app data to resolve a save or recovery problem.

## Saved-checkpoint Tile

Add Tamaemu through the watch's Tile picker. The Tile shows the full original LCD from the last verified save. Check its saved time to see how old the image is. Tap it to open the app. The image may show a clock or menu. If no trusted save is available, the Tile shows a message instead.

The Tile omits the catch-up warning derived from saved debt, which can include a small remainder from closing the app. Protected and clock-check notices remain. Its saved time still describes the checkpoint, not current progress.

The Tile does not run the emulator live, perform care or prove that background replay has finished. No live-pet widget is included.

The upstream desktop emulator uses `.tamasave` containers. Wear OS retains its separate private save format and does not import or export those containers.

## Build prerequisites

Use existing installations of Git, Python 3, a C/C++ compiler, CMake, Ninja and JDK 17 for the host checks. Android builds use JDK 21 and an Android SDK with these pinned components:

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 8.13.2 |
| Gradle wrapper | 8.13 |
| SDK platform / target | 36 |
| SDK Build Tools | 35.0.0 |
| NDK | 27.3.13750724 |
| SDK CMake | 3.31.5 |

The minimum Android API is 26. Packaged ABIs are `armeabi-v7a`, `arm64-v8a` and `x86_64`.

SDK licenses must already be accepted by the person setting up the SDK. Automatic SDK package downloads are disabled; missing components must be installed separately after reviewing their terms. The commands below do not install SDK packages or accept licenses. The Gradle wrapper and dependency resolution can download the Gradle distribution and build libraries.

From the repository root, run the firmware-free host checks:

```sh
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check-public-hygiene.py
scripts/test-wear-native.sh
scripts/test-wear-jni.sh
scripts/test-wear-preview-jni.sh
scripts/test-wear-java.sh
```

Build and check the production variants with the Android toolchain:

```sh
export ANDROID_HOME=/path/to/existing/android-sdk
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
cd wear
./gradlew --no-daemon assembleDebug assembleRelease
./gradlew --no-daemon --continue testDebugUnitTest lintDebug lintRelease :app:dependencies --configuration debugRuntimeClasspath
cd ..
python3 scripts/verify-wear-native-flags.py
python3 scripts/inspect-wear-apk.py wear/app/build/outputs/apk/debug/app-debug.apk
python3 scripts/inspect-wear-apk.py wear/app/build/outputs/apk/release/app-release-unsigned.apk --unsigned
```

The [Wear workflow](../.github/workflows/wear.yml) also builds isolated audio/Tile diagnostic packages and checks their separation from production. Full pull-request CI runs the host checks; manual dispatch skips those repeated host steps and does not replace a full run. See [verification](VERIFICATION.md) for test coverage and limits. Sign the APK before installing it. A successful build does not mean it has been tested on your watch.

## Local signing

Android updates require the same signing certificate. For a personal build, initialize one durable local identity and keep a secure offline copy of the complete ignored `private/signing/` directory. Losing it prevents ordinary updates to apps signed with that identity. Never upload its keys or password files.

The signing helper uses an existing JDK 17 and downloads a SHA-256-pinned Google `apksig` library if needed. Use a new output filename for each build:

```sh
python3 scripts/sign-wear-apk.py --jdk /path/to/jdk-17 init
python3 scripts/sign-wear-apk.py --jdk /path/to/jdk-17 sign wear/app/build/outputs/apk/debug/app-debug.apk artifacts/tamaemu-local.apk
python3 scripts/sign-wear-apk.py --jdk /path/to/jdk-17 verify artifacts/tamaemu-local.apk
```

`init` reuses an existing identity. Incomplete, mismatched or symlinked signing material is refused. Signing verifies v2/v3 signatures and preserves native-library alignment. The device helper expects this local identity; raw CI debug APKs use disposable keys. Do not re-sign an update with a new identity when an installed app already holds a save.

## Device installation and updates

Use an existing `adb` installation and follow the [Wear OS wireless debugging instructions](https://developer.android.com/training/wearables/get-started/debug-wifi). Pairing and connection ports can differ. Select the exact device serial shown by `adb devices -l`.

Before an update, leave the existing app in the background and let its lifecycle save finish. Confirm that `files/pet.state.running` is absent before treating the checkpoint as complete; an inaccessible check is not confirmation. The helper backs up the last durable state and cannot force an in-memory save. Keep the original backup before changing app versions.

```sh
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
python3 scripts/wear-device.py --serial WATCH_SERIAL probe
python3 scripts/wear-device.py --serial WATCH_SERIAL backup
python3 scripts/wear-device.py --serial WATCH_SERIAL install artifacts/tamaemu-local.apk
```

`probe` checks watch capability, Android version and ABIs. `install` makes a unique host backup when a save exists, verifies the local signer and uses `adb install -r` to preserve app data. The helper relies on the Debug app's local `run-as` access. If backup, signature or ABI checks fail, diagnose the failure before installing.

For a fresh installation only, supply your own compatible local firmware. Compute the SHA-256 of the intended file locally and substitute it for `EXPECTED_SHA256` below. This confirms which input you selected; it does not establish firmware compatibility. The helper requires an 8 MiB file. Keep the app force-stopped during import:

```sh
adb -s WATCH_SERIAL shell am force-stop io.github.projectcetacean.tamaemu
python3 scripts/wear-device.py --serial WATCH_SERIAL import-rom /path/to/local-firmware.bin --expected-rom-sha256 EXPECTED_SHA256
```

Import verifies the complete size and hash locally, refuses an existing firmware target and preserves save state. A failed transfer leaves its target for diagnosis; do not delete it without understanding the failure. Routine updates do not require importing firmware again. Firmware, backups, captures and signing material belong only in local ignored storage.

Launch the app and check display, input, pause/save and cold-reopen continuity. Complete firmware setup choices yourself. Wear 10 reads earlier Wear checkpoint formats and writes version 4, which retains the expanded RAM used by the updated core. Earlier Wear builds cannot read version 4; rollback requires the matching old app and old save with a separate restore plan. Migration with a real watch save still needs device testing. Uninstalling, clearing data or forcing a downgrade can destroy the pet.

## Troubleshooting

- **Build tools missing:** compare your SDK and JDKs with the version table. Install missing SDK components separately after reviewing their terms; the build will not install them for you.
- **Update rejected:** check package, ABI and signer. Preserve the installed app and save while resolving the mismatch.
- **Preview stale:** check its saved time, then open the app. A Tile is not a live session.
- **Save or recovery blocked:** preserve files and use the app's Retry path after resolving storage errors. Share a scrubbed report following [CONTRIBUTING.md](../CONTRIBUTING.md).
