package io.github.hyjn.nexoridemo.nexori;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import io.github.hyjn.nexori.plugin.NexoriPlugin;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;

import javax.annotation.Nonnull;
import java.util.Optional;

final class NexoriMinigameApiLocator {

    private static final PluginIdentifier NEXORI_PLUGIN_ID = new PluginIdentifier("Nexori", "NexoriPlugin");

    private NexoriMinigameApiLocator() {
    }

    @Nonnull
    static Optional<NexoriMinigameApi> resolveOptional(@Nonnull HytaleLogger logger) {
        PluginBase plugin = PluginManager.get().getPlugin(NEXORI_PLUGIN_ID);
        if (!(plugin instanceof NexoriPlugin nexoriPlugin)) {
            logger.atInfo().log("Nexori plugin dependency " + NEXORI_PLUGIN_ID + " is not available.");
            return Optional.empty();
        }
        return Optional.of(nexoriPlugin.getMinigameApi());
    }
}
