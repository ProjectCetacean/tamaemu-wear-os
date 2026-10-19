# Tamaemu for Wear OS

A Wear OS port of [maragotchi's Tamaemu](https://github.com/maragotchi/tamaemu), a Tamagotchi Color emulator. It runs the original LCD and A/B/C controls in a standalone watch app.

Supported firmware: Tamagotchi iD L. The watch app requires Android API 26 or later and includes ARM32, ARM64 and x86_64 libraries. Other models and the Windows emulator's connection play are not supported by this port.

Candidate `0.2.0-wear.11` (version code 11) is unreleased.

## Firmware

Firmware is not included or downloaded. You need a compatible local dump that you have the right to use. See the [Wear OS guide](docs/WEAR_OS.md#device-installation-and-updates) for importing it.

## Controls

Use the on-screen A/B/C buttons. Hold the display to open **Settings**. With retractable controls, tap the display to show or hide them.

Settings include display size, integer scaling, LCD effects and sharpness, button position, colors, sound, haptics and screen timeout. The [Wear OS guide](docs/WEAR_OS.md) describes each option.

## Saves

Saves stay in the app's private storage. **Normal** mode accounts for time away, with the firmware's original care consequences. **Protected** pauses future away time; it does not provide automatic care. Background jobs use a 60-second replay allowance on a 15-minute periodic schedule. Android can delay or stop them, so long gaps can still require catch-up on reopening.

Catch-up batches idle CPU work and timer comparisons. Catch-up progress updates are limited to 10 per second without changing aging or owed time.

Back up the save before updating and use the same signing identity. Do not uninstall or clear app data to work around an update failure.

## Limits

Sound plays in the foreground only. Rapid-press sound lag remains unresolved. The Tile shows a saved LCD image and opens the app when tapped; it is not live. Watch-clock autosync, multiplayer, automatic care and a live-pet widget are not included. Battery life, sustained gameplay and accessibility still need watch testing.

## Building and installing

See the [Wear OS guide](docs/WEAR_OS.md#build-prerequisites) for build tools, checks, signing and installation, [verification](docs/VERIFICATION.md) for test coverage and limits, and [release notes](docs/RELEASE_NOTES.md) for current changes. Contributions are covered in [CONTRIBUTING.md](CONTRIBUTING.md).

The inherited [device map](docs/device-map.md) and [Windows packaging notes](packaging/README.txt) describe the upstream desktop emulator. This fork includes upstream changes through `79e19b6`, including consolidated desktop `.tamasave` containers and launcher routing for downloaded saves. Wear OS continues to use its own private save format; desktop cross-save import and export are not available in the watch app.

## License

[GPL-3.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for build and test dependencies. Tamagotchi firmware is not covered by this license and is not distributed here.
