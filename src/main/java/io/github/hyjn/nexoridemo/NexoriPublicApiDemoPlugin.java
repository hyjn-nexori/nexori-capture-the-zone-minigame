package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureHudService;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureService;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureTickSystem;

import javax.annotation.Nonnull;

public final class NexoriPublicApiDemoPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private MidCaptureService midCaptureService;
    private MidCaptureHudService midCaptureHudService;

    public NexoriPublicApiDemoPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        NexoriMinigameApi minigameApi = NexoriMinigameApiLocator.resolve();
        this.midCaptureService = new MidCaptureService(minigameApi, this.getLogger());
        this.midCaptureHudService = new MidCaptureHudService(this.midCaptureService, this.getLogger());

        LOGGER.atInfo().log(
            "Setting up "
                + this.getName()
                + " v"
                + this.getManifest().getVersion().toString()
                + " against Nexori API type "
                + NexoriMinigameApi.class.getName()
        );

        this.getCommandRegistry().registerCommand(new NexoriPublicApiDemoCommand(
            this.getName(),
            this.getManifest().getVersion().toString()
        ));
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            if (event.getPlayerRef() == null || event.getPlayerRef().getUuid() == null) {
                return;
            }
            this.midCaptureService.handlePlayerDisconnect(event.getPlayerRef().getUuid());
            this.midCaptureHudService.remove(event.getPlayerRef());
        });
        this.getEntityStoreRegistry().registerSystem(new MidCaptureTickSystem(this.midCaptureService, this.midCaptureHudService));
    }
}
