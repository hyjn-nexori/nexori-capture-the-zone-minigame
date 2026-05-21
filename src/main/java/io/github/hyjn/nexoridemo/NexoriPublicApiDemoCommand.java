package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriCloseMatchAdmissionReason;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriCloseMatchAdmissionRequest;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriCloseMatchAdmissionResult;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;

import javax.annotation.Nonnull;
import java.util.Locale;

public final class NexoriPublicApiDemoCommand extends CommandBase {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final NexoriMinigameApi minigameApi;
    private final String pluginName;
    private final String pluginVersion;
    private final DefaultArg<String> actionArg;
    private final OptionalArg<String> firstArg;
    private final OptionalArg<String> secondArg;

    public NexoriPublicApiDemoCommand(
        @Nonnull NexoriMinigameApi minigameApi,
        @Nonnull String pluginName,
        @Nonnull String pluginVersion
    ) {
        super("nexoridemo", "Shows the current Nexori public API demo plugin status and runs temporary API checks.");
        this.setPermissionGroup(GameMode.Adventure);
        this.minigameApi = minigameApi;
        this.pluginName = pluginName;
        this.pluginVersion = pluginVersion;
        this.actionArg = this.withDefaultArg("action", "Action to run.", ArgTypes.STRING, "status", "status");
        this.firstArg = this.withOptionalArg("value", "Action value.", ArgTypes.STRING);
        this.secondArg = this.withOptionalArg("extra", "Extra action value.", ArgTypes.STRING);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String action = ctx.get(actionArg).trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> sendStatus(ctx);
            case "closeadmission" -> closeAdmission(ctx);
            default -> {
                ctx.sendMessage(Message.raw("Unknown nexoridemo action '" + action + "'."));
                sendHelp(ctx);
            }
        }
    }

    private void sendStatus(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw(pluginName + " v" + pluginVersion + " loaded."));
        sendHelp(ctx);
    }

    private void sendHelp(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("Nexori demo commands:"));
        ctx.sendMessage(Message.raw("- /nexoridemo status"));
        ctx.sendMessage(Message.raw("- /nexoridemo closeadmission <matchId>"));
        ctx.sendMessage(Message.raw("- /nexoridemospectator <on|off> [matchId]"));
    }

    private void closeAdmission(@Nonnull CommandContext ctx) {
        if (!ctx.provided(firstArg)) {
            ctx.sendMessage(Message.raw("Usage: /nexoridemo closeadmission <matchId>"));
            return;
        }

        String matchId = ctx.get(firstArg).trim();
        NexoriCloseMatchAdmissionResult result = minigameApi.closeMatchAdmission(new NexoriCloseMatchAdmissionRequest(
            matchId,
            NexoriCloseMatchAdmissionReason.ADMIN_FORCED,
            "Temporary nexori-capture-the-zone-minigame command test."
        ));
        String summary = "closeMatchAdmission matchId=" + result.matchId()
            + " status=" + result.status()
            + " closedLocally=" + result.closedLocally()
            + " message=" + result.message();
        LOGGER.atInfo().log(summary);
        ctx.sendMessage(Message.raw(summary));
    }
}
