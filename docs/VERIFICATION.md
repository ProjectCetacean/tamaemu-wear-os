# Verification

The tests use synthetic inputs and do not require proprietary firmware.

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

Rapid-press sound lag and continuity remain unresolved. Acoustic delay, sustained gameplay, battery consumption, long-gap replay, injected save-I/O failure recovery, TalkBack, large fonts and appearance across different watches still need testing.

Updates need save-continuity and migration checks with the intended app and signer. Keep firmware, saves, keys and raw captures local; see [CONTRIBUTING.md](../CONTRIBUTING.md) for reporting problems.
