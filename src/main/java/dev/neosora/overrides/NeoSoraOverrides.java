package dev.neosora.overrides;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. This mod adds no content and registers nothing - all of its behaviour is in
 * the mixins under {@code dev.neosora.overrides.mixin}. The only runtime job here is to
 * print, at server start, exactly which patches are live.
 *
 * <p>That report matters more than it looks: the injectors are configured not to crash a
 * live server when a target mod updates out from under them, so the log is the only place
 * a silently-dead patch becomes visible.
 */
@Mod(NeoSoraOverrides.MOD_ID)
public class NeoSoraOverrides {

    public static final String MOD_ID = "neosora_overrides";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public NeoSoraOverrides(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
    }

    private void onServerStarting(ServerStartingEvent event) {
        for (String line : NeoSoraMixinPlugin.buildReport()) {
            LOGGER.info(line);
        }
        if (NeoSoraMixinPlugin.hasFailures()) {
            LOGGER.error("[NeoSora Overrides] One or more patches did NOT apply - see the report above.");
        }
    }
}
