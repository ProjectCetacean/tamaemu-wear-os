# Verification

The tests use synthetic inputs and do not require proprietary firmware.

Candidate `0.2.0-wear.11` is unreleased. The completed CI and APK evidence below applies only to `9820345`. At this documentation revision, later changes had no completed combined source review, full hosted CI, fresh APK inspection or changed-build watch acceptance. Later changes require their own exact-commit checks. Rapid-press acoustic lag and continuity remain unresolved.

One finite, QA-only generated-silence component test on the watch completed cleanup. It observed restart below the configured threshold and a timing-envelope width above the prior 240-frame (5 ms) assumption, confirming a component dependency mismatch. That component test did not verify guest audio, onset timing, acoustic precision, hidden leading playback heads, drift or behavior across devices. The watch's production app remains `0.2.0-wear.10`.

The full pull-request [CI run](https://github.com/ProjectCetacean/tamaemu-wear-os/actions/runs/39496730379) for [commit `9820345`](https://github.com/ProjectCetacean/tamaemu-wear-os/commit/98203456cb95537e4fc61249ffcd99c3a47a1b1b) passed host checks, Android Gradle build/DEX, and 311 production and 44 QA tests with zero failures, errors or skips. Debug lint reported 0 errors, with 16 production and 17 QA warnings; Release lint passed, but its exact XML warning count was unavailable. Six actual APKs passed three-ABI, native-flag, JNI, privacy, QA-separation, alignment and signature checks. Production Debug and regular QA APKs used the existing signing identity, with all non-signature entries unchanged by signing. These results do not validate later code.

Later host regressions passed for true clock-time advancement, PCM output and pacing, and for retaining independent playback-head references for valid delayed readings. The desktop suite also passed after test-only readiness waits replaced immediate socket-acceptance assumptions. These results do not establish the cause or resolution of the observed watch audio backlog.

Later source adds a QA-only passive scalar capture lasting up to 32 seconds, without care input or generated audio. Counters, latches and the log formatter belong to the QA source set; common code uses a null observer. Manifest and privacy guards passed 44 synthetic tests. This describes a code feature, not an executed watch measurement; exclusion from actual new production APKs still requires inspection of those binaries.

## Automated checks

The [Wear workflow](../.github/workflows/wear.yml) runs:

- Python helper tests, the upstream desktop suite, native CTest, host JNI, native-save-to-Tile-reader tests and Java model tests.
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

Wear 10 at [source commit `1ad4b62`](https://github.com/ProjectCetacean/tamaemu-wear-os/commit/1ad4b62c7579d1fdd6b89c5131ac0900e46b8f2a) was installed with the existing signer, with installed bytes matching the signed artifact. One existing iD L clock-setting checkpoint migrated from version 3 to version 4 with a valid payload checksum. Normal mode was preserved, checkpoint generations advanced, and cold process reopen and resaving succeeded. The foreground marker was absent after the lifecycle save completed.

This check used the Debug production package. It does not establish migration for every firmware or checkpoint; versions 1 and 2 have synthetic coverage only. Hardware Tile behavior, buttons and speaker latency were outside this check. The [Android CI run for that source](https://github.com/ProjectCetacean/tamaemu-wear-os/actions/runs/39001537544) passed 279 Android test cases with no failures, errors or skips.

QA at `9820345` was installed with verified matching signed APK bytes; the production app stayed on `0.2.0-wear.10`. A scoped care-input check admitted and delivered 16 contacts, with no refusals or cancellations and no contacts left held. It measured delivery and input only, not sound.

Settings showed Care ready and saved UTC with no mismatch or unavailable notice. A cached `MATCH` outcome was inferred from the UI and source; the direct API outcome was not read. This does not establish absolute clock accuracy or a fresh network sync. Cold reopen preserved Normal mode and a valid version-4 checksum, advanced checkpoint generation and left care enabled; the foreground marker was absent after both pauses.

Actual guest-software audio backlog and drops were observed and remain unresolved, blocking a production update. Rapid-press sound lag and continuity remain unresolved. Acoustic delay, sustained gameplay, battery consumption, long-gap replay, injected save-I/O failure recovery, TalkBack, large fonts and appearance across different watches still need testing. Full Tile-host behavior and physical performance are not established by these checks.

Updates need save-continuity and migration checks with the intended app and signer. Keep firmware, saves, keys and raw captures local; see [CONTRIBUTING.md](../CONTRIBUTING.md) for reporting problems.
