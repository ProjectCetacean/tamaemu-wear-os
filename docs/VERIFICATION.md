# Verification

The tests use synthetic inputs and do not require proprietary firmware.

## Automated checks

The [Wear workflow](../.github/workflows/wear.yml) runs:

- Python helper tests, native CTest, host JNI, native-save-to-Tile-reader tests and Java model tests.
- Debug and Release builds, separate audio QA builds and a Tile transport test build.
- Native compiler-flag checks across three ABIs, APK privacy, signature and alignment checks, and checks that test code stays out of production builds.
- Android unit tests, QA-input tests and Debug/Release lint.

The [Wear OS guide](WEAR_OS.md#build-prerequisites) lists the tools and commands. Manual workflow runs skip the repeated host tests, so use a full pull-request run to run every automated check. Check the run for the commit you plan to build or distribute.

These checks do not establish firmware compatibility or watch behavior. Synthetic audio timing does not measure input-to-speaker delay.

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

Review the exact source commit, its outgoing history and each artifact intended for distribution. Passing source checks does not inspect a later commit or prove that a separately built APK contains only that source. Keep APK privacy, signing and alignment checks tied to the actual binary. Human review and an explicit release decision are still required. These helpers do not publish a repository or release.

## Watch testing

Rapid-press sound lag and continuity remain unresolved. Acoustic delay, sustained gameplay, battery consumption, long-gap replay, injected save-I/O failure recovery, TalkBack, large fonts and appearance across different watches still need testing.

Updates need save-continuity and migration checks with the intended app and signer. Keep firmware, saves, keys and raw captures local; see [CONTRIBUTING.md](../CONTRIBUTING.md) for reporting problems.
