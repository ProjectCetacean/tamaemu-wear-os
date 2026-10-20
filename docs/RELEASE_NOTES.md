# Release notes

## 0.2.0-wear.11, unreleased candidate

- Version code 11. Routine autosaves preserve held A/B/C contacts, minimum press duration and paired release. A failed save freezes emulation; Retry saves the same pending checkpoint.
- Successful background retry clears the session marker after durable settlement. Visible disabled controls consume touches without activating the display beneath them; losing window focus releases held inputs.
- The saved-checkpoint Tile always includes saved time and a full localized date with year. It remains a saved preview and does not perform live care.
- Audio recovery accounts for intentionally absent samples. Under the declared non-leading playback-head contract, recovery requires one second of actual played silence. Finite host models preserve notes, gaps and buffer margin, schedule zero-only coalescing against a single 10 ms retry deadline and handle pending nonzero audio promptly. The executor can run late; this is not a hardware real-time guarantee. Playback-head uncertainty has no verified global 5 ms bound.

The app automatically compares current watch UTC with Android's cached network-time estimate opportunistically on API 33 or later. A match removes the actionable clock notice without manual acknowledgment; earlier estimated away time and owed time remain. API 26 through 32, or a missing estimate, show a neutral unavailable status. The estimate does not certify accuracy or guarantee a fresh sync, and the check does not set the pet's clock. Physical API behavior and Tile-host updates still need watch validation. Rapid-press sound lag remains unresolved.

The full pull-request [CI run](https://github.com/ProjectCetacean/tamaemu-wear-os/actions/runs/39446037368) passed build, tests, lint and APK checks for [baseline `9b8e354`](https://github.com/ProjectCetacean/tamaemu-wear-os/commit/9b8e35405188217a2e4d9f7522fa2c4ae56d850f) only. The later audio repair passed independent host checks; full hosted build, lint, fresh APK inspection and changed-behavior watch acceptance remain pending. The watch's production app remains `0.2.0-wear.10`. See [verification](VERIFICATION.md) for scope.

## 0.2.0-wear.10, unreleased

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
