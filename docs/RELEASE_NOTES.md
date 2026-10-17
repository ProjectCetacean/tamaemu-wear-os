# Release notes

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
