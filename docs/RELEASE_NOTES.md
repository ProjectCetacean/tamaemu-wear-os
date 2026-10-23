# Release notes

## 0.2.0-wear.13, unreleased candidate

- Version code 13. Silent replay batches literal CPU steps up to the original timing and event boundaries. Audible execution retains its per-instruction audio observation.
- Persistent trace bookkeeping now uses defined unsigned wrap. Version-4 save bytes and the desktop state layout retain their existing representation.
- New native regressions check batching, counter wrap, diagnostic output and older save fixtures.

Source review and host checks cover these changes. Host timing does not establish watch reopen time, speaker latency or Tile behavior. See [verification](VERIFICATION.md) for results and remaining checks.

## 0.2.0-wear.12, historical candidate snapshot

- Version code 12. Guarded exact hard-HALT batching skips unchanged native ticks only when eligible; event boundaries retain the original execution path.
- Catch-up groups completed work across JNI using the existing steps of at most 100 ms and a nominal 2 ms budget checked between operations. Only reported completed work reduces owed time. One operation and scheduling can delay return; this is no strict physical latency guarantee.
- Normal away aging, care consequences, owed time, save/retry, clock and input contracts remain unchanged.
- Controls provide result feedback and mode-aware hints. No new assets were needed.
- Full pull-request host checks now include the permanent exact replay regression alongside the existing native suite.

Full hosted CI, APK checks and scoped clock-screen watch results for wear.12 are recorded in [verification](VERIFICATION.md). Those clock samples do not establish normal pet-play speed or speaker latency.

## 0.2.0-wear.11, historical candidate snapshot

- Version code 11. Routine autosaves preserve held A/B/C contacts, minimum press duration and paired release. A failed save freezes emulation; Retry saves the same pending checkpoint.
- Successful background retry clears the session marker after durable settlement. Visible disabled controls consume touches without activating the display beneath them; losing window focus releases held inputs.
- The saved-checkpoint Tile always includes saved time and a full localized date with year. It remains a saved preview and does not perform live care.
- Audio recovery accounts for intentionally absent samples. Under the declared non-leading playback-head contract, recovery requires one second of actual played silence. Finite host models preserve notes, gaps and buffer margin, schedule zero-only coalescing against a single 10 ms retry deadline and handle pending nonzero audio promptly. The executor can run late; this is not a hardware real-time guarantee. Playback-head uncertainty has no verified global 5 ms bound. Later host regressions passed for true clock-time advancement, PCM and pacing, and retention of independent playback-head references for valid delayed readings.
- Initial live audio waits for prepared output and current emulation debt at or below the selected normal target (30, 40 or 60 ms). Guest chronology and owed time continue unchanged without queuing a historical startup soundtrack. After live audio begins, the existing literal stream, notes, gaps and recovery rules remain in effect, including during stalls; transient debt does not restart admission gating. Legacy unknown-debt callers retain their behavior and lazy output configuration.
- The QA-only passive capture provider uses a `WeakReference` for its activity owner to address `StaticFieldLeak`. Fresh QA Android tests and hosted lint are required to verify the change and warning closure.

The app automatically compares current watch UTC with Android's cached network-time estimate opportunistically on API 33 or later. A match removes the actionable clock notice without manual acknowledgment; earlier estimated away time and owed time remain. API 26 through 32, or a missing estimate, show a neutral unavailable status. The estimate does not certify accuracy or guarantee a fresh sync, and the check does not set the pet's clock. QA Settings observations support only an inferred cached match; the direct API outcome was not read. Full Tile-host behavior remains unverified. Rapid-press sound lag and continuity remain unresolved.

The completed full pull-request [CI run](https://github.com/ProjectCetacean/tamaemu-wear-os/actions/runs/39510854042) passed build, tests, lint and all six actual APK checks for [historical baseline `9e3b8a4`](https://github.com/ProjectCetacean/tamaemu-wear-os/commit/9e3b8a474608b9c055613310a733227ce4d49e47), including 311 production and 51 QA tests with zero failures, errors or skips. It does not cover the new startup source or QA owner-reference change. All 28 host check commands for the startup candidate passed, including 13 independent cases through the production debt-aware caller with matching audio-disabled guest-state counterparts. Host results do not establish Android, APK, watch or acoustic acceptance. Combined source review, full CI, inspected APKs and watch acceptance must cover the intended changed artifact; see the [current pull-request checks](https://github.com/ProjectCetacean/tamaemu-wear-os/pull/3/checks).

At this historical snapshot, production remained at `0.2.0-wear.10` and wear.11 was unreleased. Historical scoped QA checks at `9820345` covered care-input delivery, Settings and cold reopen. A subsequent 32-second passive QA capture at `9e3b8a4` completed cleanup but failed startup behavioral acceptance, recording 20,208 startup dropped frames. The earlier clock-time and delayed-head fixes remain valid; they do not establish the actual startup cause. This capture predates the new startup source and does not measure acoustic delay or continuity. Changed-build watch acceptance remains a separate gate. See [verification](VERIFICATION.md) for evidence scope and other limitations.

## 0.2.0-wear.10, historical unreleased snapshot

- Sync upstream through `79e19b6`, including desktop save containers, downloaded-save routing and core corrections.
- Wear saves now use checkpoint version 4 for the updated core's expanded RAM. Version 3 migration, cold process reopen and resaving were verified on one existing watch checkpoint; versions 1 and 2 have synthetic coverage. Preserve the pre-update backup because earlier Wear builds cannot read version 4.
- Desktop `.tamasave` import and export remain unavailable on Wear OS. The watch app retains its separate save format.

## 0.2.0-wear.9, unreleased

- Catch-up batches idle CPU work and timer comparisons while preserving elapsed time, event boundaries and saved state.
- Background replay has a 60-second allowance per run on the existing 15-minute periodic schedule. Android can delay or stop a run; remaining owed time is retained.
- Repeated catch-up progress updates are limited to 10 per second; state and error messages are not delayed by that limit.
- The saved-checkpoint Tile omits the debt-based catch-up warning. Saved time, Protected and clock-check notices remain.
- Independent display size, integer scaling, control space and control placement, with retractable controls that preserve LCD bounds.
- Revised LCD effects and Off/Slight/Medium/Strong sharpness; Slight is the default.
- A full-LCD saved-checkpoint Tile with a whole-surface action to open the app.
- Foreground audio transport and input-handling changes. Rapid-press sound lag and continuity remain open.

Aging modes, owed time and care consequences retain their existing rules. Android background work remains opportunistic, and long gaps can still need replay.

See [verification](VERIFICATION.md) for automated checks and remaining watch tests. The Tile remains a saved image. Live-pet widgets and watch-clock autosync are unavailable.

Use the same signer and retain a fresh save backup for an update. See the [installation guide](WEAR_OS.md#device-installation-and-updates).
