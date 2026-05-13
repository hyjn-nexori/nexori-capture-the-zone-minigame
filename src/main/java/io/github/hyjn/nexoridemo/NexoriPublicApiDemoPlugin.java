package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureHudService;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureMinigameService;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureTickSystem;

import javax.annotation.Nonnull;

public final class NexoriPublicApiDemoPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private MidCaptureMinigameService midCaptureMinigameService;
    private MidCaptureHudService midCaptureHudService;

    public NexoriPublicApiDemoPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        NexoriMinigameApi minigameApi = NexoriMinigameApiLocator.resolve();
        this.midCaptureMinigameService = new MidCaptureMinigameService(minigameApi, this.getLogger());
        this.midCaptureHudService = new MidCaptureHudService(this.midCaptureMinigameService, this.getLogger());

        LOGGER.atInfo().log(
            "Setting up "
                + this.getName()
                + " v"
                + this.getManifest().getVersion().toString()
                + " against Nexori API type "
                + NexoriMinigameApi.class.getName()
        );

        this.getCommandRegistry().registerCommand(new NexoriPublicApiDemoCommand(
            minigameApi,
            this.getName(),
            this.getManifest().getVersion().toString()
        ));
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            if (event.getPlayerRef() == null || event.getPlayerRef().getUuid() == null) {
                return;
            }
            this.midCaptureMinigameService.handlePlayerDisconnect(event.getPlayerRef().getUuid());
            this.midCaptureHudService.remove(event.getPlayerRef());
        });
        this.getEntityStoreRegistry().registerSystem(new MidCaptureTickSystem(this.midCaptureMinigameService, this.midCaptureHudService));
    }
}
