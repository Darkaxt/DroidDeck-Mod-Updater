# DroidDeck Mod Updater

One Android app that installs and updates PC game mods inside DroidDeck, so each
modded game does not need its own APK. It downloads ordinary GitHub releases; no GitHub account is needed.

| Game | Mod | Source of releases | What the app does |
|---|---|---|---|
| 20 Minutes Till Dawn | [Revamped](https://github.com/Darkaxt/20-Minutes-Till-Dawn-Revamped) | that repository | Updates an install already prepared on Windows |
| Warhammer Survivors | [WHSurvivorsTweaks](warhammer-survivors/) (twin-stick, auto-aim) | this repository | First install and updates, MelonLoader 0.7.3 included |

## Install the app

1. Download `DroidDeck-Mod-Updater-<version>.apk` from the latest
   [`updater-v…` release](https://github.com/Darkaxt/DroidDeck-Mod-Updater/releases) and open it.
2. If you already have **Revamped Updater 0.2.0**, this installs over it (same app and signing key) and keeps its
   DroidDeck connection.
3. Open **DroidDeck Mod Updater**, tap **Connect**, then **Use this folder → Allow** on DroidDeck. One grant serves every game.

## Warhammer Survivors on DroidDeck

1. In the app, open the **Warhammer Survivors** tab. It finds the game in your Steam library.
2. Close the game, tap **Check GitHub**, then **Install 1.3.0** (the version shown).
3. The app also sets the game's Steam launch options to `WINEDLLOVERRIDES="version=n,b" %command%` (without it,
   Proton ignores MelonLoader). Close DroidDeck before installing so its Steam does not overwrite the setting; if it
   did, tap **Set launch option**. You can also set it yourself in DroidDeck's Launch tab.
4. Start the game. The first start takes a few minutes while MelonLoader prepares its files.

The app only writes the mod package (`version.dll`, `MelonLoader/`, `Mods/`, `UserLibs/`, `Plugins/`,
`WHSurvivorsTweaks.txt`) and records what it owns in `UserData/WHSurvivorsTweaks.install.json`. Saves and
`UserData/WHSurvivorsTweaks.cfg` are never touched. If a file it would replace belongs to something else (for
example a different `version.dll`), it stops before changing anything. Files identical to the package, such as
an existing MelonLoader 0.7.3, are shared and left in place on later updates.

On Windows, use the zip from the same release (or [Nexus Mods](https://www.nexusmods.com/warhammersurvivors/mods/6));
see [the mod README](warhammer-survivors/README.md).

## 20 Minutes Till Dawn Revamped

Works as Revamped Updater 0.2.0 did: see [updater/README.md](updater/README.md) and the Revamped repository's
install guide. The game still has to be prepared on Windows first.

## Repository layout

- `updater/`: the Android app (plain Java, no Gradle), its build and release scripts and instrumentation tests.
- `warhammer-survivors/`: the WHSurvivorsTweaks MelonLoader mod, its Windows installer and release tooling.

Releases are tagged `updater-v<version>` (the APK) and `warhammer-survivors-v<version>` (the mod zip plus a
file manifest the app verifies).

## Credits

- The Warhammer Survivors `Ultrawide` option (off by default) ports the fix from
  [Hush's Ultrawide Support Patch](https://www.nexusmods.com/warhammersurvivors/mods/2). All credit for it goes to Hush.
- [MelonLoader](https://github.com/LavaGang/MelonLoader) by LavaGang, Apache-2.0, is redistributed unmodified in the
  Warhammer Survivors release zip.
- The games belong to their publishers. These mods are unofficial.

MIT licensed; see [LICENSE](LICENSE).
