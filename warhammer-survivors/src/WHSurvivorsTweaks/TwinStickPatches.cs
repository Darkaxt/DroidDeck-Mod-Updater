using System;
using System.Collections.Generic;
using UnityEngine;
using Il2CppRewired;
using CharacterController = Il2CppVampireSurvivors.Objects.Characters.CharacterController;
using EnemyController = Il2CppVampireSurvivors.Objects.Characters.EnemyController;

namespace WHSurvivorsTweaks
{
    /// <summary>
    /// Stock behaviour: both sticks are bound to Move_Horizontal/Move_Vertical in Rewired's joystick maps, and
    /// CharacterController.HandlePlayerInput copies the movement vector into _lastFacingDirection and
    /// _lastMovementDirection; weapons fire along one or the other (GetFiringVector / GetPlayerCurrentDirection).
    ///
    /// This mod disables the aim-stick bindings of the movement actions (so only the left stick moves), reads the
    /// aim stick directly, and writes it into both fields after the game's own input handling. The aim is
    /// The sprite flip follows the aim. While the aim stick is released, auto-aim targets the nearest on-screen
    /// enemy (Stage.GetAllEnemiesInScreenBounds); without a target the last aim is kept (KeepAimOnRelease).
    /// </summary>
    internal static class TwinStickPatches
    {
        private const int MoveHorizontal = 0; // RewiredConsts.Action.Move_Horizontal
        private const int MoveVertical = 1;   // RewiredConsts.Action.Move_Vertical
        private const int RescanFrames = 120;
        private const int RetargetFrames = 6;

        private sealed class AimBinding
        {
            public int JoystickId, ElementId, Axis; // Axis 0 = horizontal, 1 = vertical
            public bool Invert;
            public AxisRange Range;
            public Pole Contribution;
        }

        private sealed class PlayerState
        {
            public readonly Dictionary<string, AimBinding> Bindings = new();
            public Vector2? Aim;
            public int NextScanFrame, NextDebugFrame, NextTargetFrame;
            public EnemyController Target;
            public int JoystickCount = -1;
            public bool WarnedNoBinding;
        }

        private static readonly Dictionary<int, PlayerState> States = new();

        internal static void Apply(HarmonyLib.Harmony h)
        {
            var t = typeof(TwinStickPatches);
            Mod.TryPatch(h, typeof(CharacterController), "HandlePlayerInput", postfix: nameof(HandlePlayerInputPostfix), patchClass: t);
            Mod.TryPatch(h, typeof(CharacterController), "InternalUpdate", postfix: nameof(InternalUpdatePostfix), patchClass: t);
        }

        private static void HandlePlayerInputPostfix(CharacterController __instance)
        {
            try
            {
                if (!Mod.TwinStick.Value) return;
                var player = __instance.PlayerInput;
                if (player == null) return;
                var st = GetState(player);

                var active = ReInput.controllers.GetLastActiveControllerType();
                Vector2 stick = ReadAimStick(st);
                if (Mod.VerboseLog.Value && Time.frameCount >= st.NextDebugFrame)
                {
                    st.NextDebugFrame = Time.frameCount + 60;
                    Mod.Log.Msg($"[debug] player {player.id} active={active} stick={stick} aim={st.Aim} " +
                                $"facing={__instance._lastFacingDirection} lastMove={__instance._lastMovementDirection}");
                }
                if (active != ControllerType.Joystick) return;

                float dz = Mathf.Clamp01((float)Mod.AimDeadzone.Value);
                if (stick.sqrMagnitude > dz * dz)
                {
                    st.Aim = stick.normalized;
                    st.Target = null;
                }
                else if (Mod.AutoAim.Value && TryAutoAim(__instance, st, out var toEnemy)) st.Aim = toEnemy;
                else if (!Mod.KeepAimOnRelease.Value) st.Aim = null; // stock behaviour: aim follows movement

                if (st.Aim.HasValue)
                {
                    // Weapons read either field (GetFiringVector -> facing; GetPlayerCurrentDirection and many
                    // inlined reads -> last movement direction). Walking itself uses _currentDirection.
                    __instance._lastFacingDirection = st.Aim.Value;
                    __instance._lastMovementDirection = st.Aim.Value;
                }
            }
            catch (Exception e) { LogOnce(e); }
        }

        private static void InternalUpdatePostfix(CharacterController __instance)
        {
            try
            {
                if (!Mod.TwinStick.Value || !Mod.CharacterFacesAim.Value) return;
                var player = __instance.PlayerInput;
                if (player == null || !States.TryGetValue(player.id, out var st) || !st.Aim.HasValue) return;
                if (ReInput.controllers.GetLastActiveControllerType() != ControllerType.Joystick) return;
                if (!__instance.CanFlip || __instance.ForceCinematicMovementDisabled) return;

                float x = st.Aim.Value.x;
                if (Mathf.Abs(x) < 0.05f) return;
                bool flipped = x < 0f; // same rule the game uses for movement: facing left => flipped
                if (__instance.IsFlipped != flipped) __instance.ForceSetIsFlipped(flipped);
            }
            catch (Exception e) { LogOnce(e); }
        }

        /// <summary>Direction from the character to the nearest live on-screen enemy, re-picked every few frames.</summary>
        private static bool TryAutoAim(CharacterController self, PlayerState st, out Vector2 dir)
        {
            dir = default;
            Vector2 from = self.transform.position;
            if (Time.frameCount >= st.NextTargetFrame || !IsValidTarget(st.Target))
            {
                st.NextTargetFrame = Time.frameCount + RetargetFrames;
                st.Target = null;
                var stage = self._gameManager?.Stage;
                if (stage == null) return false;
                var enemies = stage.GetAllEnemiesInScreenBounds(0f, true);
                if (enemies == null) return false;
                float best = float.MaxValue;
                for (int i = 0; i < enemies.Count; i++)
                {
                    var e = enemies[i];
                    if (!IsValidTarget(e)) continue;
                    float d = ((Vector2)e.transform.position - from).sqrMagnitude;
                    if (d < best) { best = d; st.Target = e; }
                }
            }
            if (st.Target == null) return false;
            Vector2 v = (Vector2)st.Target.transform.position - from;
            if (v.sqrMagnitude < 1e-6f) return false;
            dir = v.normalized;
            return true;
        }

        private static bool IsValidTarget(EnemyController e) =>
            e != null && !e.IsDead && e.gameObject.activeInHierarchy;

        private static PlayerState GetState(Player player)
        {
            if (!States.TryGetValue(player.id, out var st)) States[player.id] = st = new PlayerState();
            int count = ReInput.controllers.joystickCount;
            if (Time.frameCount >= st.NextScanFrame || count != st.JoystickCount)
            {
                st.NextScanFrame = Time.frameCount + RescanFrames;
                st.JoystickCount = count;
                Scan(player, st);
            }
            return st;
        }

        /// <summary>Find aim-stick bindings of the movement actions, disable them, and remember them for reading.</summary>
        private static void Scan(Player player, PlayerState st)
        {
            string match = Mod.AimStickName.Value ?? "";
            if (match.Length == 0) return;
            var list = new Il2CppSystem.Collections.Generic.List<ActionElementMap>();
            foreach (int action in new[] { MoveHorizontal, MoveVertical })
            {
                list.Clear();
                player.controllers.maps.GetAxisMapsWithAction(ControllerType.Joystick, action, false, list);
                for (int i = 0; i < list.Count; i++)
                {
                    var aem = list[i];
                    string name = aem.elementIdentifierName ?? "";
                    if (name.IndexOf(match, StringComparison.OrdinalIgnoreCase) < 0) continue;
                    var map = aem.controllerMap;
                    string key = $"{map.controllerId}:{map.id}:{aem.id}";
                    if (!st.Bindings.ContainsKey(key))
                    {
                        st.Bindings[key] = new AimBinding
                        {
                            JoystickId = map.controllerId,
                            ElementId = aem.elementIdentifierId,
                            Axis = action == MoveHorizontal ? 0 : 1,
                            Invert = aem.invert,
                            Range = aem.axisRange,
                            Contribution = aem.axisContribution,
                        };
                        Mod.Log.Msg($"Player {player.id}: '{name}' ({aem.axisRange}, invert={aem.invert}) on joystick {map.controllerId} " +
                                    $"moved from {(action == MoveHorizontal ? "Move_Horizontal" : "Move_Vertical")} to aiming.");
                    }
                    if (aem.enabled)
                    {
                        aem.enabled = false;
                        if (Mod.VerboseLog.Value) Mod.Log.Msg($"Disabled binding {key} '{name}'.");
                    }
                }
            }
            if (st.Bindings.Count == 0 && count(player) > 0 && !st.WarnedNoBinding)
            {
                st.WarnedNoBinding = true;
                Mod.Log.Warning($"Player {player.id}: no movement binding named like '{match}' found. Bindings seen:");
                foreach (int action in new[] { MoveHorizontal, MoveVertical })
                {
                    list.Clear();
                    player.controllers.maps.GetAxisMapsWithAction(ControllerType.Joystick, action, false, list);
                    for (int i = 0; i < list.Count; i++)
                        Mod.Log.Warning($"  action {action}: '{list[i].elementIdentifierName}' id={list[i].elementIdentifierId} on joystick {list[i].controllerMap.controllerId}");
                }
            }

            static int count(Player p) => p.controllers.joystickCount;
        }

        private static Vector2 ReadAimStick(PlayerState st)
        {
            float x = 0f, y = 0f;
            foreach (var b in st.Bindings.Values)
            {
                var joy = ReInput.controllers.GetJoystick(b.JoystickId);
                if (joy == null) continue;
                float v = joy.GetAxisById(b.ElementId);
                if (b.Invert) v = -v;
                float c;
                if (b.Range == AxisRange.Full) c = v;
                else
                {
                    float mag = b.Range == AxisRange.Positive ? Mathf.Max(v, 0f) : -Mathf.Min(v, 0f);
                    c = b.Contribution == Pole.Positive ? mag : -mag;
                }
                if (b.Axis == 0) x += c; else y += c;
            }
            return Vector2.ClampMagnitude(new Vector2(Mathf.Clamp(x, -1f, 1f), Mathf.Clamp(y, -1f, 1f)), 1f);
        }

        private static bool _logged;
        private static void LogOnce(Exception e)
        {
            if (_logged) return;
            _logged = true;
            Mod.Log.Error($"Twin-stick error (further errors suppressed): {e}");
        }
    }
}
