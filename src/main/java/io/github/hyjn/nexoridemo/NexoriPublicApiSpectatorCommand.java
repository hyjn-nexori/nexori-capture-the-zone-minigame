package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureMinigameService;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class NexoriPublicApiSpectatorCommand extends CommandBase {

    private final MidCaptureMinigameService midCaptureMinigameService;
    private final RequiredArg<String> modeArg;
    private final OptionalArg<String> matchIdArg;

    public NexoriPublicApiSpectatorCommand(@Nonnull MidCaptureMinigameService midCaptureMinigameService) {
        super("nexoridemospectator", "Publishes a Capture The Zone spectator request for the command sender.");
        this.setPermissionGroups("OP");
        this.midCaptureMinigameService = midCaptureMinigameService;
        this.modeArg = this.withRequiredArg("mode", "on or off.", ArgTypes.STRING);
        this.matchIdArg = this.withOptionalArg("matchId", "Optional active Nexori match id.", ArgTypes.STRING);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        if (!requireOp(ctx)) {
            return;
        }

        UUID playerUuid = ctx.sender().getUuid();
        if (playerUuid == null) {
            ctx.sendMessage(Message.raw("Cannot run spectator API test without a player UUID."));
            return;
        }

        String mode = ctx.get(modeArg).trim().toLowerCase(Locale.ROOT);
        boolean spectator;
        switch (mode) {
            case "on", "true", "yes" -> spectator = true;
            case "off", "false", "no" -> spectator = false;
            default -> {
                ctx.sendMessage(Message.raw("Usage: /nexoridemospectator <on|off> [matchId]"));
                return;
            }
        }

        midCaptureMinigameService.enqueueSpectatorApiRequest(
            playerUuid,
            spectator,
            ctx.provided(matchIdArg) ? ctx.get(matchIdArg).trim() : ""
        );
        ctx.sendMessage(Message.raw("Published Capture The Zone spectator request."));
    }

    private boolean requireOp(@Nonnull CommandContext context) {
        if (isOp(context)) {
            return true;
        }
        context.sendMessage(Message.raw("You do not have permission to run this command."));
        return false;
    }

    private boolean isOp(@Nonnull CommandContext context) {
        PermissionsModule permissionsModule = PermissionsModule.get();
        if (permissionsModule == null || context.sender() == null) {
            return false;
        }

        UUID senderUuid = context.sender().getUuid();
        if (senderUuid == null) {
            return false;
        }

        Set<String> groups = permissionsModule.getGroupsForUser(senderUuid);
        for (String group : groups) {
            if (group != null && "OP".equalsIgnoreCase(group.trim())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }
}
