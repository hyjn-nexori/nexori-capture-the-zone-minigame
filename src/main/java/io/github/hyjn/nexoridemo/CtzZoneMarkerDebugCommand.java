package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Research/prototype debug command for visualising the Capture The Zone capture area with a stock
 * world {@link ParticleSystem}, before any real gameplay integration.
 *
 * <p>It spawns a particle system at (an optionally calibrated) CTZ capture-zone centre and broadcasts
 * it to every player in the issuing player's world (via {@link ParticleUtil}, the same path the stock
 * {@code /particle spawn} command uses).</p>
 *
 * <p>Defaults: system {@value #DEFAULT_SYSTEM_ID} at scale {@value #DEFAULT_SCALE} with
 * yOffset {@value #DEFAULT_Y_OFFSET} (and xOffset/zOffset {@value #DEFAULT_X_OFFSET}).</p>
 *
 * <p>Important: Hytale has no "remove particle system" packet. A spawned system simply plays out its
 * asset {@code LifeSpan} (≈9s) and disappears on its own. So there is no {@code off}: to keep the zone
 * visible you re-run {@code on} periodically. This command does a single emit per invocation — enough
 * to eyeball the look, sizing and height.</p>
 *
 * <p>Usage (real framework optional args, {@code --name=value}, any order):
 * <pre>
 *   /ctzzonetest on [--systemId=&lt;id&gt;] [--scale=&lt;double&gt;] [--yOffset=&lt;double&gt;] [--xOffset=&lt;double&gt;] [--zOffset=&lt;double&gt;]
 *   /ctzzonetest status
 *
 *   /ctzzonetest on
 *   /ctzzonetest on --scale=1.1 --yOffset=1.0
 *   /ctzzonetest on --scale=1.1 --yOffset=1.0 --xOffset=0.5
 *   /ctzzonetest on --systemId=Totem_Heal_Simple_Test --scale=1.1 --yOffset=1.0
 * </pre></p>
 *
 * <p>Security: gated by the explicit admin permission node {@value #PERMISSION}; automatic permission
 * generation is disabled. This command does not touch gameplay, the leader marker, progress rules,
 * placement, or Nexori. The {@code --xOffset/--zOffset} flags only move the debug visual; they do not
 * change CTZ's real zone-detection logic.</p>
 */
public final class CtzZoneMarkerDebugCommand extends CommandBase {

    /** Explicit admin permission node. Must never be usable by normal players. */
    private static final String PERMISSION = "nexoridemo.ctzzonetest.admin";

    /** Stock Healing Totem ground AoE particle system (ring + lines + sparks + glow); reads as a zone. */
    private static final String DEFAULT_SYSTEM_ID = "Totem_Heal_Simple_Test";

    /** Demo ring-only particle system (ground circumference only); shipped as a .particlesystem asset. */
    private static final String RING_SYSTEM_ID = "Ctz_Zone_Ring_Test";

    // CTZ capture-zone centre (mirrors MidCaptureConfig CAPTURE_CENTER_X/Z and the floor at CAPTURE_MIN_Y).
    // Hardcoded here on purpose so this debug command stays decoupled from gameplay config.
    private static final double ZONE_CENTER_X = 62.0;
    private static final double ZONE_FLOOR_Y = 84.0;
    private static final double ZONE_CENTER_Z = -6.0;

    /** Best-looking scale found in manual testing (≈ CAPTURE_RADIUS_XZ visual fit). */
    private static final double DEFAULT_SCALE = 1.1;

    /** Default vertical offset above the zone floor (raises the visual so it doesn't sink into terrain). */
    private static final double DEFAULT_Y_OFFSET = 1.0;

    /** Default horizontal calibration offsets (debug only). */
    private static final double DEFAULT_X_OFFSET = 0.0;
    private static final double DEFAULT_Z_OFFSET = 0.0;

    private final HytaleLogger logger;
    private final RequiredArg<String> actionArg;
    private final OptionalArg<String> systemIdArg;
    private final OptionalArg<Double> scaleArg;
    private final OptionalArg<Double> yOffsetArg;
    private final OptionalArg<Double> xOffsetArg;
    private final OptionalArg<Double> zOffsetArg;
    private final OptionalArg<String> colorArg;

    public CtzZoneMarkerDebugCommand(@Nonnull HytaleLogger logger) {
        super("ctzzonetest", "Debug: spawn a ParticleSystem at the CTZ zone centre to prototype a zone visual.");
        this.logger = logger;
        requirePermission(PERMISSION);
        this.actionArg = withRequiredArg("action", "on or status.", ArgTypes.STRING);
        // Real framework optional args, used as --name=value flags (any order).
        this.systemIdArg = withOptionalArg("systemId",
            "ParticleSystem id (default " + DEFAULT_SYSTEM_ID + ").", ArgTypes.STRING);
        this.scaleArg = withOptionalArg("scale",
            "Visual scale (default " + DEFAULT_SCALE + ").", ArgTypes.DOUBLE);
        this.yOffsetArg = withOptionalArg("yOffset",
            "Vertical offset above the zone floor (default " + DEFAULT_Y_OFFSET + ").", ArgTypes.DOUBLE);
        this.xOffsetArg = withOptionalArg("xOffset",
            "Debug X calibration offset (default " + DEFAULT_X_OFFSET + ").", ArgTypes.DOUBLE);
        this.zOffsetArg = withOptionalArg("zOffset",
            "Debug Z calibration offset (default " + DEFAULT_Z_OFFSET + ").", ArgTypes.DOUBLE);
        this.colorArg = withOptionalArg("color",
            "Optional packet color override as hex #RRGGBB (default: use the asset's own colors).", ArgTypes.STRING);
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String action = ctx.get(actionArg).trim().toLowerCase(Locale.ROOT);
        if (!action.equals("on") && !action.equals("status")) {
            sendUsage(ctx);
            return;
        }

        Ref<EntityStore> ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            ctx.sendMessage(Message.raw("ctzzonetest: must be run by an online player (needs a world)."));
            return;
        }
        Store<EntityStore> store = ref.getStore();
        EntityStore entityStore = store == null ? null : store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        if (world == null) {
            ctx.sendMessage(Message.raw("ctzzonetest: could not resolve your world."));
            logger.atWarning().log("CTZ_ZONE_TEST resolve_failed step=world senderIndex=" + ref.getIndex());
            return;
        }

        // Real framework optional args (--systemId=, --scale=, --yOffset=, --xOffset=, --zOffset=), any order.
        String systemId = ctx.provided(systemIdArg) && !ctx.get(systemIdArg).isBlank()
            ? ctx.get(systemIdArg).trim() : DEFAULT_SYSTEM_ID;
        double scale = ctx.provided(scaleArg) ? ctx.get(scaleArg) : DEFAULT_SCALE;
        double yOffset = ctx.provided(yOffsetArg) ? ctx.get(yOffsetArg) : DEFAULT_Y_OFFSET;
        double xOffset = ctx.provided(xOffsetArg) ? ctx.get(xOffsetArg) : DEFAULT_X_OFFSET;
        double zOffset = ctx.provided(zOffsetArg) ? ctx.get(zOffsetArg) : DEFAULT_Z_OFFSET;

        // Optional packet color override (#RRGGBB or RRGGBB). null = use the asset's own colors.
        Color color = null;
        if (ctx.provided(colorArg) && !ctx.get(colorArg).isBlank()) {
            color = parseColor(ctx, ctx.get(colorArg).trim());
            if (color == null) {
                return; // parseColor already reported the error.
            }
        }

        if (action.equals("status")) {
            status(ctx, world, systemId, scale, yOffset, xOffset, zOffset);
            return;
        }

        // action == "on"
        if (ParticleSystem.getAssetMap().getAsset(systemId) == null) {
            ctx.sendMessage(Message.raw("ctzzonetest: ParticleSystem '" + systemId + "' not found on this server."));
            logger.atSevere().log("CTZ_ZONE_TEST asset_not_found systemId=" + systemId);
            return;
        }

        final String finalSystemId = systemId;
        final float finalScale = (float) scale;
        final double finalX = ZONE_CENTER_X + xOffset;
        final double finalY = ZONE_FLOOR_Y + yOffset;
        final double finalZ = ZONE_CENTER_Z + zOffset;
        final Color finalColor = color;
        // Particle spawn / packet send must run on the world tick thread.
        world.execute(() -> emit(ctx, world, store, finalSystemId, finalScale, finalX, finalY, finalZ, finalColor));
    }

    /** Parses {@code #RRGGBB} / {@code RRGGBB} into a protocol {@link Color}, or null (with a message) on error. */
    private Color parseColor(@Nonnull CommandContext ctx, @Nonnull String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (hex.length() != 6) {
            ctx.sendMessage(Message.raw("ctzzonetest: invalid color '" + raw + "', expected #RRGGBB."));
            return null;
        }
        try {
            int rgb = Integer.parseInt(hex, 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            return new Color((byte) r, (byte) g, (byte) b);
        } catch (NumberFormatException ex) {
            ctx.sendMessage(Message.raw("ctzzonetest: invalid color '" + raw + "', expected hex #RRGGBB."));
            return null;
        }
    }

    private void status(
        @Nonnull CommandContext ctx,
        @Nonnull World world,
        @Nonnull String systemId,
        double scale,
        double yOffset,
        double xOffset,
        double zOffset
    ) {
        boolean resolvesDefault = ParticleSystem.getAssetMap().getAsset(DEFAULT_SYSTEM_ID) != null;
        boolean resolvesRing = ParticleSystem.getAssetMap().getAsset(RING_SYSTEM_ID) != null;
        double finalX = ZONE_CENTER_X + xOffset;
        double finalY = ZONE_FLOOR_Y + yOffset;
        double finalZ = ZONE_CENTER_Z + zOffset;
        String msg = "ctzzonetest STATUS:"
            + " defaultSystemId=" + DEFAULT_SYSTEM_ID
            + " defaultScale=" + DEFAULT_SCALE
            + " defaultYOffset=" + DEFAULT_Y_OFFSET
            + " defaultXOffset=" + DEFAULT_X_OFFSET
            + " defaultZOffset=" + DEFAULT_Z_OFFSET
            + " | baseCenter=(" + ZONE_CENTER_X + "," + ZONE_FLOOR_Y + "," + ZONE_CENTER_Z + ")"
            + " | finalCenter=(" + finalX + "," + finalY + "," + finalZ + ")"
            + " (scale=" + scale + ", yOffset=" + yOffset + ", xOffset=" + xOffset + ", zOffset=" + zOffset + ")"
            + " | resolves[" + DEFAULT_SYSTEM_ID + "]=" + resolvesDefault
            + " resolves[" + RING_SYSTEM_ID + "]=" + resolvesRing
            + " | world=" + world.getName() + " players=" + world.getPlayerCount();
        ctx.sendMessage(Message.raw(msg));
        logger.atInfo().log("CTZ_ZONE_TEST " + msg);
    }

    private void emit(
        @Nonnull CommandContext ctx,
        @Nonnull World world,
        @Nonnull Store<EntityStore> store,
        @Nonnull String systemId,
        float scale,
        double finalX,
        double finalY,
        double finalZ,
        Color color
    ) {
        // Broadcast to every player currently in this world (explicit viewer list, like /particle spawn).
        List<Ref<EntityStore>> viewers = new ArrayList<>();
        for (PlayerRef pr : world.getPlayerRefs()) {
            if (pr == null || !pr.isValid()) {
                continue;
            }
            Ref<EntityStore> r = pr.getReference();
            if (r != null && r.isValid()) {
                viewers.add(r);
            }
        }

        Vector3d center = new Vector3d(finalX, finalY, finalZ);
        // (id, pos, yaw, pitch, roll, scale, color, viewers, accessor) — color null = use the asset's own colors.
        ParticleUtil.spawnParticleEffect(systemId, center, 0.0f, 0.0f, 0.0f, scale, color, viewers, store);

        String colorText = color == null ? "asset-default"
            : String.format("#%02X%02X%02X", color.red & 0xFF, color.green & 0xFF, color.blue & 0xFF);
        logger.atInfo().log("CTZ_ZONE_TEST on systemId=" + systemId + " scale=" + scale + " color=" + colorText
            + " center=(" + finalX + "," + finalY + "," + finalZ + ")"
            + " world=" + world.getName() + " viewers=" + viewers.size());
        ctx.sendMessage(Message.raw("ctzzonetest ON: spawned '" + systemId + "' (scale=" + scale + ", color=" + colorText
            + ") at (" + finalX + "," + finalY + "," + finalZ + ") for " + viewers.size()
            + " viewer(s). Re-run to keep it visible (it plays out its LifeSpan then fades)."));
    }

    private void sendUsage(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("Usage: /ctzzonetest <on|status> [--systemId=<id>] [--scale=<double>] [--yOffset=<double>] [--xOffset=<double>] [--zOffset=<double>] [--color=#RRGGBB]"));
        ctx.sendMessage(Message.raw("  e.g. /ctzzonetest on --scale=0.9 --yOffset=1.0"));
        ctx.sendMessage(Message.raw("  e.g. /ctzzonetest on --systemId=" + RING_SYSTEM_ID + " --scale=0.9 --yOffset=1.0"));
        ctx.sendMessage(Message.raw("  e.g. /ctzzonetest on --systemId=" + RING_SYSTEM_ID + " --scale=0.9 --yOffset=1.0 --color=#00FF00"));
    }
}
