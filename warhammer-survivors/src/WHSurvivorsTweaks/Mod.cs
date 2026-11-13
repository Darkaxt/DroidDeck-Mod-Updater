using HarmonyLib;
using MelonLoader;

[assembly: MelonInfo(typeof(WHSurvivorsTweaks.Mod), "WHSurvivorsTweaks", "1.3.0", "darka")]
[assembly: MelonGame("Auroch Digital", "Warhammer Survivors")]

namespace WHSurvivorsTweaks
{
    public sealed class Mod : MelonMod
    {
        internal const string ConfigFile = "UserData/WHSurvivorsTweaks.cfg";

        internal static MelonLogger.Instance Log;

        // Gameplay settings.
        internal static MelonPreferences_Entry<bool> TwinStick;
        internal static MelonPreferences_Entry<bool> AutoAim;
        internal static MelonPreferences_Entry<bool> KeepAimOnRelease;
        internal static MelonPreferences_Entry<bool> CharacterFacesAim;
        internal static MelonPreferences_Entry<double> AimDeadzone;
        internal static MelonPreferences_Entry<bool> Ultrawide;
        // Advanced / troubleshooting.
        internal static MelonPreferences_Entry<string> AimStickName;
        internal static MelonPreferences_Entry<bool> VerboseLog;

        public override void OnInitializeMelon()
        {
            Log = LoggerInstance;
            var cat = MelonPreferences.CreateCategory("WHSurvivorsTweaks", "Warhammer Survivors Tweaks");
            cat.SetFilePath(ConfigFile, autoload: true, printmsg: false);

            TwinStick = cat.CreateEntry("TwinStickAim", true,
                description: "Controller: the left stick moves, the right stick aims (weapons fire where it points). false = stock controls (both sticks move).");
            AutoAim = cat.CreateEntry("AutoAim", true,
                description: "While the right stick is released, aim at the nearest enemy on screen. Pushing the right stick overrides it.");
            KeepAimOnRelease = cat.CreateEntry("KeepAimOnRelease", true,
                description: "When the right stick is released (and AutoAim has no target): true = the aim stays where it last pointed. false = aim follows movement again.");
            CharacterFacesAim = cat.CreateEntry("CharacterFacesAim", true,
                description: "The character sprite turns to face the aim direction instead of the movement direction.");
            AimDeadzone = cat.CreateEntry("AimDeadzone", 0.35,
                description: "How far (0.0-1.0) the right stick must be pushed before it changes the aim.");
            Ultrawide = cat.CreateEntry("Ultrawide", false,
                description: "Optional ultrawide support (removes the 16:10 limit). Based on Hush's Ultrawide Support Patch " +
                             "(nexusmods.com/warhammersurvivors/mods/2), which you can use instead. Only acts on screens wider than 16:10.");
            AimStickName = cat.CreateEntry("AimStickName", "Right Stick",
                description: "Advanced: controller elements whose name contains this text become the aim stick. Check the MelonLoader log if aiming does not work.");
            VerboseLog = cat.CreateEntry("VerboseLog", false,
                description: "Troubleshooting: write aim/stick values to the MelonLoader log once per second.");
            cat.SaveToFile(false); // writes the file with defaults and descriptions on first run

            var h = HarmonyInstance;
            if (Ultrawide.Value) UltrawidePatches.Apply(h);
            TwinStickPatches.Apply(h);
            Log.Msg($"Loaded. Settings in {ConfigFile}: TwinStickAim={TwinStick.Value} AutoAim={AutoAim.Value} KeepAimOnRelease={KeepAimOnRelease.Value} " +
                    $"CharacterFacesAim={CharacterFacesAim.Value} AimDeadzone={AimDeadzone.Value} Ultrawide={Ultrawide.Value}");
        }

        internal static void TryPatch(HarmonyLib.Harmony h, System.Type type, string method, string prefix = null, string postfix = null, System.Type patchClass = null)
        {
            try
            {
                var target = AccessTools.Method(type, method);
                if (target == null) { Log.Error($"{type.Name}.{method} not found - this part of the mod is disabled (game update?)."); return; }
                h.Patch(target,
                    prefix: prefix == null ? null : new HarmonyMethod(AccessTools.Method(patchClass, prefix)),
                    postfix: postfix == null ? null : new HarmonyMethod(AccessTools.Method(patchClass, postfix)));
                Log.Msg($"Hooked {type.Name}.{method}");
            }
            catch (System.Exception e)
            {
                Log.Error($"Failed to hook {type.Name}.{method}: {e}");
            }
        }
    }
}
