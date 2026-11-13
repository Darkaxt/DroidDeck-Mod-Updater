# WHSurvivorsTweaks: twin-stick controls and auto-aim for Warhammer Survivors

A MelonLoader mod for Warhammer Survivors (Steam, v2.1.6) that adds twin-stick controls for gamepads.

- **Twin-stick:** the left stick moves and the right stick aims. Weapons that fire in the direction you face
  fire where the right stick points.
- **Auto-aim:** while the right stick is released, the aim targets the nearest enemy on screen. Push the stick
  to take over; let go and auto-aim resumes.
- **Character faces the aim:** the sprite turns towards where you aim, not where you walk.
- **Optional ultrawide support** (off by default; see Credits).

Keyboard and mouse controls are unchanged. Weapons that pick their own targets (nearest or random enemy) keep
doing so. Nothing in the game's files is modified.

## Install

1. Close the game. In Steam, right-click Warhammer Survivors > Manage > Browse local files.
2. Copy everything in the zip into that folder (next to `WarhammerSurvivors.exe`). Replace files if asked.
3. Start the game. The first start takes a few minutes while MelonLoader prepares its files.

The zip includes MelonLoader 0.7.3. If you already use MelonLoader 0.7.3 (for example for the Crossover mod),
you only need `Mods\WHSurvivorsTweaks.dll`.

**Steam Deck / Linux (Proton):** set the game's launch options to `WINEDLLOVERRIDES="version=n,b" %command%`.

**DroidDeck (Android):** use the [DroidDeck Mod Updater](../README.md#warhammer-survivors-on-droiddeck) app. It
installs and updates this mod and MelonLoader on the phone; set the same launch option.

**After a game update:** nothing to do. MelonLoader regenerates its files on the next start. If an update
renames something the mod uses, the MelonLoader log says which feature was disabled; the rest keeps working.

**Uninstall:** delete `Mods\WHSurvivorsTweaks.dll`. To remove MelonLoader as well, delete `version.dll` and the
`MelonLoader` folder (only if no other mod needs them). Your save is not touched by this mod.

## Settings

`UserData\WHSurvivorsTweaks.cfg` is created on the first start. Edit it with the game closed.

| Setting | Default | Effect |
|---|---|---|
| `TwinStickAim` | `true` | Left stick moves, right stick aims. `false` = stock controls (both sticks move). |
| `AutoAim` | `true` | While the right stick is released, aim at the nearest enemy on screen. The stick overrides it. |
| `KeepAimOnRelease` | `true` | With the stick released and no auto-aim target: aim stays where it last pointed. `false` = aim follows movement. |
| `CharacterFacesAim` | `true` | The character faces the aim direction instead of the movement direction. |
| `AimDeadzone` | `0.35` | How far (0.0-1.0) the right stick must be pushed before it changes the aim. |
| `Ultrawide` | `false` | Removes the 16:10 limit on wider screens. See Credits. |
| `AimStickName` | `"Right Stick"` | Advanced: which controller element becomes the aim stick. |
| `VerboseLog` | `false` | Troubleshooting: logs stick and aim values once per second. |

## Compatibility

- Needs MelonLoader 0.7.3. Works alongside other MelonLoader 0.7.3 mods such as Crossover.
- BepInEx mods (WS Tools, Level Up Reward Hotkeys, the BepInEx version of the Ultrawide Support Patch) use a
  different mod loader. Running BepInEx and MelonLoader together is not supported.
- Hush's Ultrawide Support Patch (DLL version) can be used together with this mod; leave `Ultrawide = false`.

## Credits

- The `Ultrawide` option is a MelonLoader port of the GameAssembly.dll fix from **Hush's Ultrawide Support
  Patch**: https://www.nexusmods.com/warhammersurvivors/mods/2. All credit for finding that fix goes to Hush.
  Please use and endorse the original; this option exists only for players who want everything in one
  MelonLoader mod.
- MelonLoader by LavaGang (Apache-2.0), https://github.com/LavaGang/MelonLoader.
- Auroch Digital and Games Workshop for the game. This mod is unofficial and not affiliated with or endorsed
  by them.

## How the twin-stick works

The stock Rewired joystick maps bind both sticks to `Move_Horizontal`/`Move_Vertical`. At runtime the mod
disables the movement bindings whose element name contains `AimStickName` and reads those axes itself. The aim
is written to `CharacterController._lastFacingDirection` and `_lastMovementDirection` (each weapon fires along
one of the two). Auto-aim uses the game's `Stage.GetAllEnemiesInScreenBounds`. Log lines
`... moved from Move_Horizontal to aiming` confirm the stick split per controller; if your controller's right
stick has another element name, the log lists the bindings it saw.

## Build

`dotnet build src/WHSurvivorsTweaks/WHSurvivorsTweaks.csproj -c Release` against a game folder where
MelonLoader has run once (`-p:GameDir=...` to override). `installer/Install-WHSurvivorsTweaks.ps1` installs
pinned MelonLoader 0.7.3 (SHA-256 verified) and the built DLL into a game folder (`-Uninstall` to remove).
`release/Build-NexusZip.ps1` builds the release zip. `release/github_release.py prepare|publish|verify` writes the
file manifest the Android app checks and publishes both as `warhammer-survivors-v<version>`.
