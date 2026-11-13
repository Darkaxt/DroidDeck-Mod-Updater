# DroidDeck Mod Updater (Android app)

A small native Android client that installs and updates PC game mods inside DroidDeck. It downloads ordinary
GitHub releases; no GitHub account is needed. It started as Revamped Updater 0.2.0 and keeps its package id and
signing key, so it installs over it.

## Connect once

1. Open the app and tap **Connect**.
2. Android Files opens at DroidDeck. Tap **Use this folder → Allow**. You do not need to browse Linux folders.
3. The app reads Steam's library catalogue and finds each supported game. If there are several copies, choose
   the intended one. A grant made from either game tab is reused for the other.

This uses DroidDeck's Files provider, not direct access to another app's private data. Root, Shizuku, all-files
permission, USB and DroidDeck debugging are unnecessary. Android may require a new grant if the app is reinstalled.

## Warhammer Survivors

Releases come from this repository (`warhammer-survivors-v<version>`): the same zip players install on Windows,
plus `WHSurvivorsTweaks-<version>.json`, which lists every file's SHA-256, the archive digest and the minimum app
version. The app checks GitHub's asset digests, the manifest and every zip member before writing anything.

- First install and updates are both done on the phone; no Windows step is needed.
- Only `version.dll`, `WHSurvivorsTweaks.txt`, `MelonLoader/`, `Mods/`, `UserLibs/` and `Plugins/` may be written.
  Saves and `UserData/WHSurvivorsTweaks.cfg` are never part of a package.
- Ownership is recorded in `UserData/WHSurvivorsTweaks.install.json`. A file already identical to the package
  (for example an existing MelonLoader 0.7.3) is shared, not owned. A different file the app does not own makes it
  stop before changing anything. A manually installed `Mods/WHSurvivorsTweaks.dll` is adopted.
- Owned files that a newer release drops are removed, unless you changed them.
- Writes are journaled. If the app is interrupted, reopen it and tap **Finish interrupted installation**. Do not
  clear the app's data while that is pending.
- Proton needs the launch option `WINEDLLOVERRIDES="version=n,b" %command%` to load MelonLoader. After installing,
  the app sets it in DroidDeck's Steam the way DroidDeck's Launch tab does while Steam is closed: only
  `apps/3669620/LaunchOptions` in `userdata/<account>/config/localconfig.vdf` changes (account from
  `config/loginusers.vdf`), the previous file is kept as `localconfig.vdf.revamped-previous`, existing options are
  kept and a different `WINEDLLOVERRIDES` is never replaced. A running Steam may overwrite it on exit; the
  **Set launch option** button retries.
- After installing, the main button changes to **Open DroidDeck**.

## 20 Minutes Till Dawn Revamped

Unchanged from Revamped Updater 0.2.0. Releases come from
[Darkaxt/20-Minutes-Till-Dawn-Revamped](https://github.com/Darkaxt/20-Minutes-Till-Dawn-Revamped). The app updates an
**already prepared** installation: the game needs proprietary assets prepared on Windows first (see that repository's
INSTALL.md). It verifies the archive digest, package inventory and `UPDATE.json`, and checks the installed file set
before changing the owned mod DLL and install record. Crossing the character-rune transition (game 1.3.0) resets
this game's own progress once, with the button saying **Reset saves & install**; the exact files are recorded and
limited to this game's profile in its Steam app-1966900 prefix.

## Build

Requires Python 3.11+, JDK 21 and an Android SDK with platform 36 and build-tools 35.0.0. Plain Java, no Gradle or
third-party runtime dependencies. Minimum Android version is 8.0 / API 26.

Use the persistent private PKCS12 key with alias `revamped-updater` and supply its password through
`REVAMPED_KEYSTORE_PASSWORD`. Keys and passwords stay outside the repository; a different key cannot update
installed copies.

```powershell
python -B updater/build.py --work D:/Temp/ddmu-build/work --output D:/Temp/ddmu-build/out --sdk C:/path/to/Android/Sdk --jdk C:/path/to/jdk --keystore D:/private/release.p12 --tests
```

`--work` must be a new directory. The output holds the signed APK, its certificate check and a source digest receipt.
`--tests` adds the separate instrumentation APK (never distributed). Its fixture modes (`recovery`, `rune-reset`,
`warhammer`, `warhammer-live`, `launch-options`) run in the test app's private storage and drive the production buttons.

`release.py prepare | publish | verify` packages a committed build as `updater-v<version>`.

## Design

**B layout with A colors**: opaque purple, coral, cream and sage surfaces with one main action.
[Approved prototype](design/approved.html) · [Design contract](design/brand-spec.md).
