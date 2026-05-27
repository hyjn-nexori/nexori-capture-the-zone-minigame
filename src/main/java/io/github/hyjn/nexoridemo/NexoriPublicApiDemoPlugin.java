package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureEventBus;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureHudService;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureIntegrationHandle;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureMinigameService;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureStandaloneDriver;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureTickSystem;
import io.github.hyjn.nexoridemo.midcapture.events.MidCaptureSessionClosedEvent;

import javax.annotation.Nonnull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class NexoriPublicApiDemoPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private MidCaptureMinigameService midCaptureMinigameService;
    private MidCaptureHudService midCaptureHudService;
    private MidCaptureIntegrationHandle nexoriIntegrationRegistration = MidCaptureIntegrationHandle.inactive();
    private MidCaptureStandaloneDriver standaloneDriver;

    public NexoriPublicApiDemoPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        MidCaptureEventBus midCaptureEventBus = new MidCaptureEventBus();
        this.midCaptureMinigameService = new MidCaptureMinigameService(this.getLogger(), midCaptureEventBus);
        this.midCaptureHudService = new MidCaptureHudService(this.midCaptureMinigameService, this.getLogger());
        midCaptureEventBus.register(MidCaptureSessionClosedEvent.class, event -> this.midCaptureHudService.removeAll(event.playerUuids()));
        this.nexoriIntegrationRegistration = startNexoriIntegrationIfAvailable(midCaptureEventBus);
        this.standaloneDriver = new MidCaptureStandaloneDriver(
            this.getLogger(),
            this.midCaptureMinigameService,
            () -> this.nexoriIntegrationRegistration.active()
        );

        LOGGER.atInfo().log(
            "Setting up "
                + this.getName()
                + " v"
                + this.getManifest().getVersion().toString()
                + "."
        );

        this.getCommandRegistry().registerCommand(new NexoriPublicApiSpectatorCommand(this.midCaptureMinigameService));
        this.getCommandRegistry().registerCommand(new MidCaptureLocalCommand(this.standaloneDriver));
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            if (event.getPlayerRef() == null || event.getPlayerRef().getUuid() == null) {
                return;
            }
            this.midCaptureMinigameService.handlePlayerDisconnect(event.getPlayerRef().getUuid());
            this.midCaptureHudService.remove(event.getPlayerRef());
        });
        this.getEntityStoreRegistry().registerSystem(new MidCaptureTickSystem(this.midCaptureMinigameService, this.midCaptureHudService));
    }

    @Nonnull
    private MidCaptureIntegrationHandle startNexoriIntegrationIfAvailable(@Nonnull MidCaptureEventBus midCaptureEventBus) {
        try {
            Class<?> integrationClass = Class.forName("io.github.hyjn.nexoridemo.nexori.CaptureTheZoneNexoriIntegration");
            Method method = integrationClass.getMethod(
                "startIfAvailable",
                HytaleLogger.class,
                MidCaptureMinigameService.class,
                MidCaptureEventBus.class,
                String.class
            );
            Object result = method.invoke(
                null,
                this.getLogger(),
                this.midCaptureMinigameService,
                midCaptureEventBus,
                "capture_the_zone"
            );
            if (result instanceof MidCaptureIntegrationHandle handle) {
                return handle;
            }
            LOGGER.atWarning().log("Capture The Zone Nexori integration factory returned no close handle; running in passive mode.");
        } catch (ClassNotFoundException | LinkageError exception) {
            LOGGER.atInfo().log("Capture The Zone running in passive mode; Nexori integration is not available.");
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            LOGGER.atWarning().withCause(cause).log("Failed to start Capture The Zone Nexori integration; running in passive mode.");
        } catch (ReflectiveOperationException exception) {
            LOGGER.atWarning().withCause(exception).log("Failed to reflect Capture The Zone Nexori integration; running in passive mode.");
        }
        return MidCaptureIntegrationHandle.inactive();
    }
}
