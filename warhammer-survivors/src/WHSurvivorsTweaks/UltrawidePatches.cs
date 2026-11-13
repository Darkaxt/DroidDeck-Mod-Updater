using UnityEngine;
using Unity.Mathematics;
using RenderingHelper = Il2CppPhaserPort.RenderingHelper;
using ViewportCrop = Il2Cpp.ViewportCrop;
using RenderTextureResizer = Il2CppVampireSurvivors.App.Graphics.RenderTextureResizer;

namespace WHSurvivorsTweaks
{
    /// <summary>
    /// Optional (setting "Ultrawide", off by default). A Harmony port of the three GameAssembly.dll patch sites of
    /// Hush's "Ultrawide Support Patch" (https://www.nexusmods.com/warhammersurvivors/mods/2); credit to its author.
    /// The game caps the view at RenderingHelper.TargetRatio (1.6).
    /// Each hook only acts when the screen is wider than that, so 16:10 and narrower screens behave as stock.
    /// </summary>
    internal static class UltrawidePatches
    {
        private const float TargetRatio = 1.6f;

        internal static void Apply(HarmonyLib.Harmony h)
        {
            var t = typeof(UltrawidePatches);
            // UpdateRendererForLandscape shares this compiled body, so hooking GetRendererSize covers both.
            Mod.TryPatch(h, typeof(RenderingHelper), nameof(RenderingHelper.GetRendererSize), postfix: nameof(RendererSizePostfix), patchClass: t);
            Mod.TryPatch(h, typeof(ViewportCrop), "Update", postfix: nameof(ViewportCropPostfix), patchClass: t);
            Mod.TryPatch(h, typeof(RenderTextureResizer), nameof(RenderTextureResizer.UpdateRT), postfix: nameof(UpdateRTPostfix), patchClass: t);
        }

        /// <summary>Screen aspect ratio when ultrawide handling should apply, otherwise 0.</summary>
        private static float WideAspect()
        {
            int w = Screen.width, hgt = Screen.height;
            if (w <= 0 || hgt <= 0) return 0f;
            float a = (float)w / hgt;
            return a > TargetRatio + 0.001f ? a : 0f;
        }

        // Stock: wider than 1.6 => size = (2*ortho*1.6, 2*ortho). We widen x to the real aspect.
        private static void RendererSizePostfix(ref float2 __result)
        {
            float a = WideAspect();
            if (a > 0f) __result.x = __result.y * a;
        }

        // Stock: crops the camera viewport to the reference aspect (pillarboxing). Use the whole screen instead.
        private static void ViewportCropPostfix(ViewportCrop __instance)
        {
            if (WideAspect() <= 0f) return;
            var cam = __instance._camera;
            if (cam == null) return;
            var full = new Rect(0f, 0f, 1f, 1f);
            if (cam.rect != full) cam.rect = full;
        }

        // Stock: AspectRatioFitter.aspectRatio = 1.6 for the render-texture display. Match the screen.
        private static void UpdateRTPostfix(RenderTextureResizer __instance)
        {
            float a = WideAspect();
            if (a <= 0f) return;
            var fitter = __instance._AspectRatioFitter;
            if (fitter != null && Mathf.Abs(fitter.aspectRatio - a) > 0.0001f) fitter.aspectRatio = a;
        }
    }
}
