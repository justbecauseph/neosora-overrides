package dev.neosora.overrides.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes: server thread parks forever inside a synchronous chunk load, watchdog kills the
 * server.
 *
 * <p>Voiceless Survival 2.0.1 scans for sculk sensors around a speaking player like this
 * (decompiled from {@code SculkVibrationHelper.findNearbySculkSensors}, line 76):
 *
 * <pre>{@code
 * for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r,-r,-r), center.offset(r,r,r))) {
 *     BlockEntity be = level.getBlockEntity(pos.immutable());   // <-- force-loads chunks
 *     if (be instanceof SculkSensorBlockEntity s) out.add(s);
 * }
 * }</pre>
 *
 * <p>Two problems. First, {@code Level.getBlockEntity} routes through {@code getChunkAt} and
 * <b>loads the chunk synchronously if it is not already loaded</b>, blocking the calling
 * thread. This runs on the server thread inside a {@code TickTask}, so with an async chunk
 * system (C2ME is installed here) the blocking wait can fail to be satisfied and the server
 * thread parks indefinitely.
 *
 * <p>In the 2026-08-05 16:11 crash, all six {@code c2me-worker} threads, {@code c2me-sched}
 * and every {@code C2ME Storage} thread were WAITING - idle, not busy. The server thread was
 * not waiting on slow generation; it was waiting on a request nothing was going to serve.
 *
 * <p>Second, the scan is cubic: {@code (2r+1)^3} block positions, each a separate lookup. At
 * r=16 that is roughly 36,000 {@code getBlockEntity} calls per voice packet. Even fully
 * loaded that is a lot of work to do on the tick thread.
 *
 * <p>This fix addresses the hang by never force-loading: positions in unloaded chunks return
 * null and are skipped. That is behaviourally correct as well as safer - a sculk sensor in an
 * unloaded chunk has no listener ticking and no player nearby to observe it.
 *
 * <p>It does <b>not</b> address the cubic scan, which needs an upstream change (iterate the
 * loaded chunks in range and read their block-entity maps, rather than probing every block).
 *
 * <p>{@code Level.isLoaded} is itself a pure query - verified in the 1.21.1 server jar, it is
 * an {@code isOutsideBuildHeight} check followed by {@code getChunkSource().hasChunk(x, z)},
 * with no loading path.
 *
 * <p>Reported upstream: see {@code bugreport-ezvcsurvival.md}.
 */
@Pseudo
@Mixin(targets = "com.armilp.ezvcsurvival.sculk.SculkVibrationHelper", remap = false)
public abstract class EzvcSurvivalSculkVibrationHelperMixin {

    /**
     * Note the owner in the descriptor is {@code ServerLevel}, not {@code Level}:
     * {@code findNearbySculkSensors} takes a {@code ServerLevel} and javac emitted
     * {@code invokevirtual net/minecraft/server/level/ServerLevel.getBlockEntity}.
     * Targeting {@code Level} here would silently fail to match.
     */
    @Redirect(
            method = "findNearbySculkSensors",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"
            ),
            remap = false,
            require = 0
    )
    private static BlockEntity neosora$skipUnloadedChunks(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos);
    }
}
