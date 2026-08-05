package dev.neosora.overrides;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gates every mixin in this mod behind "is the mod it patches actually loaded?".
 *
 * <p>Without this, removing or renaming any patched mod would hard-crash the server at
 * startup, because Mixin fails loudly when a declared target class does not exist. A pack
 * override mod has to tolerate its targets coming and going.
 *
 * <p>Injectors use {@code require = 0} so that a target mod which is present but has been
 * updated in a way that moves the injection point will not crash a live server either. That
 * buys availability at the cost of silence, so this class deliberately closes the gap: it
 * records which mixins were <em>enabled</em> (target mod present) and which were actually
 * <em>applied</em>, and {@link #buildReport()} surfaces any mismatch at startup. A fix that
 * has quietly stopped working is the outcome worth guarding against most - you would
 * otherwise believe you were protected when you were not.
 */
public class NeoSoraMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("neosora_overrides");

    /** Mixin simple name -> mod id that must be loaded for it to be meaningful. */
    private static final Map<String, String> GATES = new LinkedHashMap<>();

    /** Mixin simple name -> short description, for the startup report. */
    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    /**
     * Mixin simple name -> the class it patches.
     *
     * <p>Needed because Mixin transforms lazily, on first class load. {@code postApply} does
     * not fire until something actually touches the target, so a patch that is perfectly fine
     * looks unapplied at server start if its class has not been reached yet. Observed on the
     * first deploy: {@code UltimateWeaponHandler} was loaded (it is an event handler, so it is
     * registered during mod construction) and reported ACTIVE, while
     * {@code StageRegressionData} and {@code SculkVibrationHelper} - which are not touched
     * until a stage is queried or a player speaks - reported FAILED despite being correct.
     *
     * <p>{@link #ensureTargetsTransformed()} force-loads these before the report is built so
     * the answer is deterministic rather than a race against gameplay.
     */
    private static final Map<String, String> TARGET_CLASSES = new LinkedHashMap<>();

    static {
        GATES.put("ProgressiveStagesRegressionDataMixin", "progressivestages");
        GATES.put("EzvcSurvivalSculkVibrationHelperMixin", "ezvcsurvival");
        GATES.put("TensuraMinesuraUltimateWeaponHandlerMixin", "tensura_minesura");

        DESCRIPTIONS.put("ProgressiveStagesRegressionDataMixin",
                "StageRegressionData.grantTimes -> ConcurrentHashMap (fixes server-thread hang in HashMap.getNode)");
        DESCRIPTIONS.put("EzvcSurvivalSculkVibrationHelperMixin",
                "SculkVibrationHelper skips unloaded chunks (fixes sync chunk-load deadlock)");
        DESCRIPTIONS.put("TensuraMinesuraUltimateWeaponHandlerMixin",
                "UltimateWeaponHandler no longer forces loot unpack (fixes StackOverflow with Lootr)");

        TARGET_CLASSES.put("ProgressiveStagesRegressionDataMixin",
                "com.enviouse.progressivestages.server.triggers.StageRegressionData");
        TARGET_CLASSES.put("EzvcSurvivalSculkVibrationHelperMixin",
                "com.armilp.ezvcsurvival.sculk.SculkVibrationHelper");
        TARGET_CLASSES.put("TensuraMinesuraUltimateWeaponHandlerMixin",
                "com.joaomaia.tensura_minesura.event.UltimateWeaponHandler");
    }

    private static final Set<String> ENABLED = new LinkedHashSet<>();
    private static final Set<String> APPLIED = new LinkedHashSet<>();

    @Override
    public void onLoad(String mixinPackage) {
        // nothing to do
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simple = simpleName(mixinClassName);
        String requiredMod = GATES.get(simple);

        // Not a gated mixin: allow it.
        if (requiredMod == null) {
            return true;
        }

        if (modPresent(requiredMod)) {
            ENABLED.add(simple);
            return true;
        }

        LOGGER.info("[NeoSora Overrides] mod '{}' is not loaded - skipping {}", requiredMod, simple);
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // nothing to do
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // nothing to do
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        String simple = simpleName(mixinClassName);
        APPLIED.add(simple);
        LOGGER.debug("[NeoSora Overrides] applied {} -> {}", simple, targetClassName);
    }

    /**
     * Human-readable summary of what is and is not actually patched. Logged at server start.
     */
    public static List<String> buildReport() {
        ensureTargetsTransformed();

        List<String> lines = new java.util.ArrayList<>();
        lines.add("=== NeoSora Overrides: active patches ===");

        for (Map.Entry<String, String> entry : GATES.entrySet()) {
            String simple = entry.getKey();
            String modId = entry.getValue();
            String desc = DESCRIPTIONS.getOrDefault(simple, simple);

            if (!ENABLED.contains(simple)) {
                lines.add(String.format("  [skipped] %s not installed - %s", modId, desc));
            } else if (APPLIED.contains(simple)) {
                lines.add(String.format("  [ACTIVE ] %s - %s", modId, desc));
            } else {
                // Target mod is installed but the injection did not take. Almost always
                // means that mod updated and moved the code we hook.
                lines.add(String.format("  [FAILED ] %s IS installed but the patch did NOT apply - %s", modId, desc));
                lines.add("             ^ that mod likely updated. The bug it fixes is UNPATCHED. See README.md.");
            }
        }
        return lines;
    }

    /**
     * Force each not-yet-applied target class to load, so Mixin transforms it and
     * {@code postApply} runs before the report is built.
     *
     * <p>Uses {@code initialize = false}: the class is defined and transformed, but its static
     * initialiser does not run. Loading is what triggers transformation, so this is enough to
     * get a truthful answer without executing any of the target mod's code earlier than it
     * would otherwise run.
     *
     * <p>Failures here are deliberately quiet at WARN rather than fatal - being unable to
     * verify a patch is not a reason to take a server down.
     */
    private static void ensureTargetsTransformed() {
        for (Map.Entry<String, String> entry : TARGET_CLASSES.entrySet()) {
            String simple = entry.getKey();

            // Only bother with patches whose mod is present and which have not already applied.
            if (!ENABLED.contains(simple) || APPLIED.contains(simple)) {
                continue;
            }

            try {
                Class.forName(entry.getValue(), false, NeoSoraMixinPlugin.class.getClassLoader());
            } catch (Throwable t) {
                LOGGER.warn("[NeoSora Overrides] could not load {} to verify {}: {}",
                        entry.getValue(), simple, t.toString());
            }
        }
    }

    /** True if any gated mixin was enabled but never applied. */
    public static boolean hasFailures() {
        for (String simple : ENABLED) {
            if (!APPLIED.contains(simple)) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(String className) {
        int idx = className.lastIndexOf('.');
        return idx < 0 ? className : className.substring(idx + 1);
    }

    private static boolean modPresent(String modId) {
        try {
            return LoadingModList.get().getModFileById(modId) != null;
        } catch (Throwable t) {
            // If the mod list is not queryable for any reason, fail closed: do not patch.
            LOGGER.warn("[NeoSora Overrides] could not query the mod list for '{}'; skipping its patch", modId, t);
            return false;
        }
    }
}
