package dev.neosora.overrides.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixes: server thread spins forever in {@code HashMap.getNode}, watchdog kills the server.
 *
 * <p>ProgressiveStages 3.0.2 stores {@code StageRegressionData.grantTimes} in a plain
 * {@link java.util.HashMap}. That map is read on the entity-tracking path - via
 * {@code EntityPresenceEnforcer.shouldConcealTracking}, which runs inside
 * {@code ChunkMap$TrackedEntity.updatePlayer}, i.e. potentially once per tracked entity per
 * player movement packet - and written by the stage grant/clear paths.
 *
 * <p>If any write lands off the server thread, a concurrent resize can splice a bucket's
 * linked list into a cycle. The next read then walks that cycle forever: {@code getNode}
 * never returns, the thread pins a core at 100%, and {@code ServerHangWatchdog} force-kills
 * the server after 60 seconds.
 *
 * <p>Evidence this is what happened rather than mere slowness (crash 2026-08-05 18:33):
 * <ul>
 *   <li>the persisted {@code progressivestages_regression.dat} is 991 bytes, so the map is
 *       tiny - "too big" is ruled out;</li>
 *   <li>keys are Strings from {@code key(UUID, StageId)}, so hashing is well distributed -
 *       degenerate buckets are ruled out;</li>
 *   <li>a spark profile of the server showed ProgressiveStages in <b>0 of 68,864</b> sampled
 *       nodes, while {@code ChunkMap$TrackedEntity} had 40 - so this code is provably cold,
 *       and could not have been caught by the sample unless it was occupying most of that
 *       60-second tick;</li>
 *   <li>the thread was RUNNABLE, not blocked.</li>
 * </ul>
 * A cycle in the bucket chain is the only non-terminating failure mode of {@code getNode}.
 *
 * <p>The fix swaps the map for a {@link ConcurrentHashMap}, which removes the corruption
 * risk regardless of which caller turns out to be off-thread. The map holds a handful of
 * entries so the overhead is irrelevant.
 *
 * <p>Null-safety note: {@code ConcurrentHashMap} rejects null keys and values where
 * {@code HashMap} permits them. This is safe here because {@code getGrantTime} returns a
 * primitive {@code long} and {@code markGranted(UUID, StageId, long)} takes one, so no null
 * value can be stored.
 *
 * <p>Reported upstream: see {@code bugreport-progressivestages.md}.
 */
@Pseudo
@Mixin(targets = "com.enviouse.progressivestages.server.triggers.StageRegressionData", remap = false)
public abstract class ProgressiveStagesRegressionDataMixin {

    @Mutable
    @Final
    @Shadow
    private Map<String, Long> grantTimes;

    /**
     * Replace the map immediately after construction. The field is {@code final} and assigned
     * only in the constructor, so this is the single point where it can be swapped; the static
     * {@code load(...)} factory populates the instance afterwards and therefore fills the
     * replacement, not the original.
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void neosora$useConcurrentMap(CallbackInfo ci) {
        if (!(this.grantTimes instanceof ConcurrentHashMap)) {
            this.grantTimes = new ConcurrentHashMap<>(this.grantTimes);
        }
    }
}
