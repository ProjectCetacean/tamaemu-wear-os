# Contributing

Check existing issues before opening a report. Include the app version or commit, watch model, Android version, display size, steps to reproduce, expected behavior and what happened. Use a small synthetic example when possible.

Do not attach firmware, saves, keys, passwords, raw device logs or personal screenshots. Remove usernames, local paths, device addresses and personal information from shared excerpts. Do not post firmware download links.

For code changes, run the relevant firmware-free checks in the [Wear OS guide](docs/WEAR_OS.md#build-prerequisites) and `python3 scripts/check-public-hygiene.py`. Add a regression test for a bug fix. Say which checks ran and which could not run. For documentation changes, run `python3 scripts/check-public-docs.py` and review wording, links and factual accuracy. See [publication checks](docs/VERIFICATION.md#publication-checks) for local hooks and release limits.

Preserve copyright and license notices. Contributions must be compatible with [GPL-3.0](LICENSE); separately licensed components are listed in [third-party notices](THIRD_PARTY_NOTICES.md).
