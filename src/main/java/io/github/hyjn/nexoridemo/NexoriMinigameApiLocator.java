package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import io.github.hyjn.nexori.plugin.NexoriPlugin;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;

import javax.annotation.Nonnull;

final class NexoriMinigameApiLocator {

    private static final PluginIdentifier NEXORI_PLUGIN_ID = new PluginIdentifier("Nexori", "NexoriPlugin");

    private NexoriMinigameApiLocator() {
    }

    @Nonnull
    static NexoriMinigameApi resolve() {
        PluginBase plugin = PluginManager.get().getPlugin(NEXORI_PLUGIN_ID);
        if (!(plugin instanceof NexoriPlugin nexoriPlugin)) {
            throw new IllegalStateException("Could not resolve Nexori plugin dependency " + NEXORI_PLUGIN_ID + ".");
        }
        return nexoriPlugin.getMinigameApi();
    }
}
