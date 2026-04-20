package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;

public final class NexoriPublicApiDemoCommand extends CommandBase {

    private final String pluginName;
    private final String pluginVersion;

    public NexoriPublicApiDemoCommand(@Nonnull String pluginName, @Nonnull String pluginVersion) {
        super("nexoridemo", "Shows the current Nexori public API demo plugin status.");
        this.setPermissionGroup(GameMode.Adventure);
        this.pluginName = pluginName;
        this.pluginVersion = pluginVersion;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw(pluginName + " v" + pluginVersion + " loaded."));
    }
}
