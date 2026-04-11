package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMatchPlacementState;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureService;

import javax.annotation.Nonnull;
import java.util.Optional;

public final class NexoriPublicApiDemoCommand extends CommandBase {

    private final NexoriMinigameApi minigameApi;
    private final MidCaptureService midCaptureService;
    private final String pluginName;
    private final String pluginVersion;

    public NexoriPublicApiDemoCommand(
        @Nonnull NexoriMinigameApi minigameApi,
        @Nonnull MidCaptureService midCaptureService,
        @Nonnull String pluginName,
        @Nonnull String pluginVersion
    ) {
        super("nexoridemo", "Prints the current Nexori public API demo plugin status.");
        this.setPermissionGroup(GameMode.Adventure);
        this.minigameApi = minigameApi;
        this.midCaptureService = midCaptureService;
        this.pluginName = pluginName;
        this.pluginVersion = pluginVersion;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw(
                pluginName
                    + " v"
                    + pluginVersion
                    + " loaded. This command shows live player state when used in-game."
            ));
            return;
        }

        var playerEntityRef = ctx.senderAsPlayerRef();
        if (playerEntityRef == null) {
            ctx.sendMessage(Message.raw("Could not resolve the player entity for this command call."));
            return;
        }

        var store = playerEntityRef.getStore();
        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(playerEntityRef, Universe.get().getPlayerRefComponentType());
        TransformComponent transformComponent = store.getComponent(playerEntityRef, TransformComponent.getComponentType());
        if (player == null || playerRef == null || playerRef.getUuid() == null) {
            ctx.sendMessage(Message.raw("Could not resolve the runtime player state for this command call."));
            return;
        }

        World world = player.getWorld();
        var playerUuid = playerRef.getUuid();
        ctx.sendMessage(Message.raw(pluginName + " v" + pluginVersion));
        ctx.sendMessage(Message.raw("World: " + (world == null ? "<none>" : world.getName())));

        Optional<String> activeMatchId = minigameApi.findActiveMatchId(playerUuid);
        if (activeMatchId.isEmpty()) {
            ctx.sendMessage(Message.raw("No active Nexori match is currently associated with you."));
            return;
        }

        String matchId = activeMatchId.get();
        ctx.sendMessage(Message.raw("Match: " + matchId));
        ctx.sendMessage(Message.raw(
            "Trigger: " + minigameApi.findMatchResolutionTriggerId(matchId)
                .filter(triggerId -> !triggerId.isBlank() && !"none".equalsIgnoreCase(triggerId))
                .orElse("manual")
        ));

        NexoriMatchPlacementState placementState = minigameApi.findMatchPlacementState(matchId).orElse(null);
        if (placementState != null) {
            ctx.sendMessage(Message.raw(
                "Placement: expected=" + placementState.expectedPlayers()
                    + " arrived=" + placementState.arrivedPlayers()
                    + " placed=" + placementState.placedPlayers()
                    + " complete=" + placementState.placementComplete()
            ));
        }

        MidCaptureService.DebugState debugState = midCaptureService.findDebugState(playerUuid).orElse(null);
        if (debugState == null) {
            ctx.sendMessage(Message.raw("Mid-capture runtime has not tracked you yet."));
            return;
        }

        ctx.sendMessage(Message.raw(
            "Progress: " + Math.round((debugState.captureProgressSeconds() / 20.0D) * 100.0D)
                + "% insideZone=" + debugState.insideCaptureZone()
                + " homeRespawnConfigured=" + debugState.homeRespawnConfigured()
                + " resolved=" + debugState.resolved()
        ));
        ctx.sendMessage(Message.raw("Zone: " + debugState.zoneStatusText()));
        if (transformComponent != null) {
            ctx.sendMessage(Message.raw("Position: " + transformComponent.getPosition()));
        }
    }
}
