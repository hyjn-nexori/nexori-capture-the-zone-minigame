package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureStandaloneDriver;

import javax.annotation.Nonnull;
import java.util.Locale;

public final class MidCaptureLocalCommand extends AbstractPlayerCommand {

    private final MidCaptureStandaloneDriver standaloneDriver;
    private final DefaultArg<String> actionArg;

    public MidCaptureLocalCommand(@Nonnull MidCaptureStandaloneDriver standaloneDriver) {
        super("midcapture", "Controls local Capture The Zone standalone sessions.");
        this.standaloneDriver = standaloneDriver;
        this.actionArg = this.withDefaultArg("action", "start, stop, or status.", ArgTypes.STRING, "status", "status");
        setPermissionGroups("OP");
    }

    @Override
    protected void execute(
        @Nonnull CommandContext context,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world
    ) {
        String action = context.get(actionArg).trim().toLowerCase(Locale.ROOT);
        long nowEpochMs = System.currentTimeMillis();
        switch (action) {
            case "start" -> start(context, playerRef, world, nowEpochMs);
            case "stop" -> stop(context, nowEpochMs);
            case "status" -> status(context);
            default -> {
                context.sendMessage(Message.raw("Unknown midcapture action '" + action + "'."));
                context.sendMessage(Message.raw("Usage: /midcapture <start|stop|status>"));
            }
        }
    }

    private void start(
        @Nonnull CommandContext context,
        @Nonnull PlayerRef playerRef,
        @Nonnull World world,
        long nowEpochMs
    ) {
        MidCaptureStandaloneDriver.LocalStartResult result = standaloneDriver.startForPlayer(playerRef, world, nowEpochMs);
        context.sendMessage(Message.raw(result.message()));
    }

    private void stop(@Nonnull CommandContext context, long nowEpochMs) {
        MidCaptureStandaloneDriver.LocalStopResult result = standaloneDriver.stop("LOCAL_COMMAND_STOP", nowEpochMs);
        context.sendMessage(Message.raw(result.message()));
    }

    private void status(@Nonnull CommandContext context) {
        MidCaptureStandaloneDriver.LocalStatus status = standaloneDriver.describeStatus();
        if (!status.active()) {
            context.sendMessage(Message.raw("No local Capture The Zone session is active."));
            return;
        }
        context.sendMessage(Message.raw(
            "Local Capture The Zone session active matchId=" + status.matchId()
                + " world=" + status.worldName()
                + " playerUuid=" + status.playerUuid()
        ));
    }
}
