# Verification

Candidate `0.2.0-wear.12` (version code 12) is unreleased. Combined source review, final replay-batch review, full hosted Android/CI, inspected APKs and watch acceptance remain pending. Host results do not establish care-ready reopen time, speaker latency or app completion.

## Current host evidence

The permanent native regression passed 2,031 checks against a frozen literal reference, covering raw state, save/reopen, continuation and guarded hard-HALT eligibility and event boundaries. It uses synthetic inputs without proprietary firmware. Exact batching is restricted to eligible unchanged ticks; the original execution path handles boundaries and unsupported states.

A separate host comparison using local inputs matched original and candidate guest-state digests over two guest minutes. Candidate headless/rendered and save/reopen comparisons passed at eight hours, one day and three days. The eight-hour headless replay took about 0.246 seconds; the whole paired host check took about 2.567 seconds. Long-interval comparisons here were within the candidate, not against the original: the original long comparison timed out. These are scoped native host measurements, excluding Android owner and watch care-ready acceptance.

The integrated 19 Java model mains and actual JNI checks passed, including 151 new JNI checks. Replay grouping uses existing steps of at most 100 ms with a nominal 2 ms budget sampled between operations. The fallback completes one existing step; only exact reported completion consumes debt. Unknown failures require recovery from a durable checkpoint. Scheduling and an already-started operation can delay return, so the budget is no strict physical latency guarantee. Normal away aging, owed time, save/retry, clock, input and live-audio contracts remain unchanged.

Control feedback and mode-aware guidance have source and host coverage. Android execution, actual TalkBack events, visible watch guidance and retained settings still need checking. No new assets were needed.

## Historical wear.11 host and CI evidence

The following results predate the wear.12 replay and control changes. The historical CI and APK results cover `9e3b8a4`, before the startup changes. See the [pull-request checks](https://github.com/ProjectCetacean/tamaemu-wear-os/pull/3/checks) for commit-specific automated evidence. Rapid-press acoustic lag and continuity remain unresolved.

The candidate prepares audio output before admitting initial live samples. While output is preparing or current emulation debt exceeds the selected normal target (30, 40 or 60 ms), guest time advances without producing audio. Once output is ready and debt is at or below that target, live audio begins from current guest state. Guest chronology, owed time, input and save accounting are preserved; startup elapsed time does not become queued historical audio. After live audio starts, the existing literal sample stream, FIFO order, notes, gaps, admission and recovery rules continue unchanged, including during stalls. Transient debt does not gate an existing live stream again. Legacy callers with unknown debt retain their existing behavior and lazy output configuration.

All 28 host check commands passed, including an independent 13-case regression through the production debt-aware caller. Four cold-start cases combine 200 or 443 ms of initial debt with 0 or 200 ms of output preparation; nine cases cover positive and lifecycle controls. All 13 matched audio-disabled guest-state counterparts. These are host results, not new Android, APK, watch or acoustic acceptance. Existing regressions for clock-time advancement, PCM output, pacing and independent references for delayed playback-head readings remain valid. They do not establish those defects as the cause of the historical startup backlog.

The candidate also replaces a strong activity-owner reference in the QA-only passive capture provider with a `WeakReference` to address `StaticFieldLeak`. Fresh QA Android tests and hosted lint must confirm the change and warning closure. Passive capture remains bounded to 32 seconds without care input or generated audio. Counters, latches and logging belong to the QA source set; common code uses a null observer. Actual changed APKs require their own QA-separation and privacy inspection.

The completed full pull-request [CI run](https://github.com/ProjectCetacean/tamaemu-wear-os/actions/runs/39510854042) for [historical commit `9e3b8a4`](https://github.com/ProjectCetacean/tamaemu-wear-os/commit/9e3b8a474608b9c055613310a733227ce4d49e47) passed host checks, Android builds, 311 production and 51 QA tests with zero failures, errors or skips, and all six actual APK checks. Debug lint recorded 16 production and 18 QA warnings, including the QA owner-reference warning addressed by the candidate. This completed run does not cover the new startup source or the QA reference change.

One finite, QA-only generated-silence component test on the watch completed cleanup. It observed restart below the configured threshold and a timing-envelope width above the prior 240-frame (5 ms) assumption, confirming a component dependency mismatch. That component test did not verify guest audio, onset timing, acoustic precision, hidden leading playback heads, drift or behavior across devices. The desktop suite also passed after test-only readiness waits replaced immediate socket-acceptance assumptions.

## Automated checks

The [Wear workflow](../.github/workflows/wear.yml) runs:

- Python helper tests, the upstream desktop suite, native CTest, the permanent exact hard-HALT replay regression, host JNI, native-save-to-Tile-reader tests and Java model tests.
- Debug and Release builds, separate audio QA builds and a Tile transport test build.
- Native compiler-flag checks across three ABIs, APK privacy, signature and alignment checks, and checks that test code stays out of production builds.
- Android unit tests, QA-input tests and Debug/Release lint.

The [Wear OS guide](WEAR_OS.md#build-prerequisites) lists the tools and commands. Manual workflow runs skip the repeated host tests, so use a full pull-request run to run every automated check. Check the run for the commit you plan to build or distribute.

These checks do not establish firmware compatibility or watch behavior. Synthetic audio timing does not measure input-to-speaker delay.

## Upstream desktop checks

On a POSIX host with an existing C compiler, run the inherited firmware-free suite:

```sh
scripts/test-upstream-desktop.sh
```

The helper compiles `tests/test_all.c` and its core, save-container and download-library dependencies. It runs in a separate ignored directory with synthetic files and loopback connections. It does not build or test the Windows launcher, SDL rendering or watch save migration. Windows-specific save-path expectations remain in the source suite.

## Publication checks

The [public hygiene workflow](../.github/workflows/public-hygiene.yml) reports the `Public hygiene` status. Run the source checks locally:

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/check-public-hygiene.py
python3 scripts/check-public-docs.py
```

Both source checkers accept `--export-root /path/to/export` for a complete source directory without Git metadata. The documentation checker otherwise uses tracked paths, including staged additions, and current working-tree bytes. Untracked drafts are outside that mode. Run export checks on the actual source export before sharing it.

The documentation checker requires the README, contribution guide, third-party notices, Wear OS guide, verification page and release notes. It discovers other Markdown files, checks local links and heading anchors, and rejects known private process documents, placeholders, tool-credit chatter, selected unsupported claims, em dashes and listed filler in authored prose. Code blocks, inline code and URLs retain technical identifiers. Full license texts retain their wording. Two inherited desktop documents have exact-content prose exemptions recorded in the checker; edits lose those exemptions. Their links and encoding are still checked. Attribution and legal provenance dates must remain intact.

These are bounded checks, not proof of factual accuracy, good writing, complete privacy or release readiness. A reviewer must check the wording and evidence. File checks can miss private material under an unexpected name or content pattern.

Install the optional local pre-push hook for each checkout:

```sh
python3 scripts/install-public-hooks.py
```

The hook checks outgoing committed trees and history. Local hooks are opt-in and bypassable. CI runs after a push and cannot undo disclosure. GitHub's native push protection can block selected credential patterns before acceptance when enabled; it does not inspect every kind of private input.

Before a public launch, run the read-only repository policy check with the intended owner and repository:

```sh
python3 scripts/check-github-publication-policy.py --repo OWNER/REPO
```

The future public repository must retain the upstream fork relationship, enable native secret scanning and push protection, and protect its default branch with the required `Public hygiene` status and no force pushes, deletion or bypass. The policy check verifies settings; it does not enable them. An unavailable setting or private preview is not public-launch clearance.

[GitHub supports bypass-list actors only for organization-owned repositories](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches). The policy check accepts an omitted bypass list only when the repository API verifies a personal owner with the matching login. A present bypass object must contain empty users, teams and apps lists. Organization or unknown owners still require that explicit evidence.

Review the exact source commit, its outgoing history and each artifact intended for distribution. Passing source checks does not inspect a later commit or prove that a separately built APK contains only that source. Keep APK privacy, signing and alignment checks tied to the actual binary. Human review and an explicit release decision are still required. These helpers do not publish a repository or release.

## Watch testing

Historical Wear 10 at [source commit `1ad4b62`](https://github.com/ProjectCetacean/tamaemu-wear-os/commit/1ad4b62c7579d1fdd6b89c5131ac0900e46b8f2a) was installed with the existing signer, with installed bytes matching the signed artifact. One existing iD L clock-setting checkpoint migrated from version 3 to version 4 with a valid payload checksum. Normal mode was preserved, checkpoint generations advanced, and cold process reopen and resaving succeeded. The foreground marker was absent after the lifecycle save completed.

This check used the Debug production package. It does not establish migration for every firmware or checkpoint; versions 1 and 2 have synthetic coverage only. Hardware Tile behavior, buttons and speaker latency were outside this check. The [Android CI run for that source](https://github.com/ProjectCetacean/tamaemu-wear-os/actions/runs/39001537544) passed 279 Android test cases with no failures, errors or skips.

Historical QA at `9820345` was installed with verified matching signed APK bytes; the production app stayed on `0.2.0-wear.10`. A scoped care-input check admitted and delivered 16 contacts, with no refusals or cancellations and no contacts left held. It measured delivery and input only, not sound.

Settings showed Care ready and saved UTC with no mismatch or unavailable notice. A cached `MATCH` outcome was inferred from the UI and source; the direct API outcome was not read. This does not establish absolute clock accuracy or a fresh network sync. Cold reopen preserved Normal mode and a valid version-4 checksum, advanced checkpoint generation and left care enabled; the foreground marker was absent after both pauses.

A separate 32-second passive QA capture at historical `9e3b8a4` completed cleanup and failed startup behavioral acceptance. It recorded 443 ms of debt before the first offer, 6,720 frames at the first stable pending reading and 20,208 startup dropped frames. Underrun and head-floor-lag counters were zero; the delayed-head retention condition was not exercised. These software observations motivated the startup change; they neither test the new candidate nor measure speaker latency. Changed-build startup watch acceptance is a separate release gate. Rapid-press sound lag and continuity remain unresolved. Acoustic delay, sustained gameplay, battery consumption, long-gap replay, injected save-I/O failure recovery, TalkBack, large fonts and appearance across different watches still need testing. Full Tile-host behavior and physical performance are not established by these checks.

Updates need save-continuity and migration checks with the intended app and signer. Keep firmware, saves, keys and raw captures local; see [CONTRIBUTING.md](../CONTRIBUTING.md) for reporting problems.
