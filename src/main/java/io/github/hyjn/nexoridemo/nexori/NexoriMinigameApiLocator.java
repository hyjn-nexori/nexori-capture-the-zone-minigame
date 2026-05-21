package io.github.hyjn.nexoridemo.nexori;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.Optional;

final class NexoriMinigameApiLocator {

    private static final PluginIdentifier NEXORI_PLUGIN_ID = new PluginIdentifier("Nexori", "NexoriPlugin");

    private NexoriMinigameApiLocator() {
    }

    @Nonnull
    static Optional<NexoriMinigameApi> resolveOptional(@Nonnull HytaleLogger logger) {
        PluginBase plugin = PluginManager.get().getPlugin(NEXORI_PLUGIN_ID);
        if (plugin == null) {
            logger.atInfo().log("Nexori plugin dependency " + NEXORI_PLUGIN_ID + " is not available.");
            return Optional.empty();
        }
        try {
            Method accessor = plugin.getClass().getMethod("getMinigameApi");
            Object api = accessor.invoke(plugin);
            if (api instanceof NexoriMinigameApi minigameApi) {
                return Optional.of(minigameApi);
            }
            logger.atWarning().log("Nexori plugin did not expose a compatible minigame API.");
        } catch (ReflectiveOperationException | LinkageError exception) {
            logger.atWarning().withCause(exception).log("Failed to resolve Nexori minigame API.");
        }
        return Optional.empty();
    }
}
