package io.github.hyjn.nexoridemo;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Phase 1 debug-only admin command for the Capture The Zone leader marker prototype.
 *
 * <p>Applies / removes / inspects visual {@link EntityEffect}s on a target player to validate whether
 * a player can be marked visually (highlight/glow/tint/crown) via {@code EffectControllerComponent},
 * before integrating with real gameplay. It uses existing infinite, purely-visual effects:</p>
 * <ul>
 *   <li>{@value #DEFAULT_EFFECT_ID} — stock legendary-drop glow (default).</li>
 *   <li>{@value #CROWN_EFFECT_ID} — demo asset wrapping the stock {@code Crown_Gold} ModelVFX.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   /ctzmarktest on &lt;player&gt; [effectId]      (default effectId = Drop_Legendary)
 *   /ctzmarktest off &lt;player&gt; [effectId]     (default effectId = Drop_Legendary)
 *   /ctzmarktest both &lt;player&gt;               (applies Drop_Legendary AND Ctz_Crown_Test)
 *   /ctzmarktest status &lt;player&gt;
 * </pre></p>
 *
 * <p>Security: gated by the explicit admin permission node {@value #PERMISSION}. Automatic permission
 * generation is also disabled so the node is the only, intentional gate.</p>
 */
public final class CtzLeaderMarkerDebugCommand extends CommandBase {

    /** Explicit admin permission node. This command must never be usable by normal players. */
    private static final String PERMISSION = "nexoridemo.ctzmarktest.admin";

    /** Default stock effect (legendary-drop glow). */
    private static final String DEFAULT_EFFECT_ID = "Drop_Legendary";

    /** Demo EntityEffect asset wrapping the stock {@code Crown_Gold} ModelVFX. */
    private static final String CROWN_EFFECT_ID = "Ctz_Crown_Test";

    /**
     * The marker effects are {@code "Infinite": true}, so the duration is effectively ignored; a large
     * fallback value is passed defensively in case the infinite flag is not honored by addEffect.
     */
    private static final float FALLBACK_DURATION_SECONDS = 86_400.0f;

    private final HytaleLogger logger;
    private final RequiredArg<String> actionArg;
    private final RequiredArg<PlayerRef> targetArg;
    private final OptionalArg<String> effectIdArg;

    public CtzLeaderMarkerDebugCommand(@Nonnull HytaleLogger logger) {
        super("ctzmarktest", "Debug: apply/remove/inspect visual EntityEffect marker(s) on a player.");
        this.logger = logger;
        requirePermission(PERMISSION);
        this.actionArg = withRequiredArg("action", "on, off, both, or status.", ArgTypes.STRING);
        this.targetArg = withRequiredArg("player", "Target player name or uuid.", ArgTypes.PLAYER_REF);
        this.effectIdArg = withOptionalArg("effectId",
            "Optional EntityEffect id (default Drop_Legendary). Ignored for both/status.", ArgTypes.STRING);
    }

    @Override
    protected boolean canGeneratePermission() {
        // Belt-and-suspenders: never auto-generate a permission node; PERMISSION is the only gate.
        return false;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String action = ctx.get(actionArg).trim().toLowerCase(Locale.ROOT);
        if (!action.equals("on") && !action.equals("off") && !action.equals("both") && !action.equals("status")) {
            ctx.sendMessage(Message.raw("Usage: /ctzmarktest <on|off|both|status> <player> [effectId]"));
            return;
        }

        PlayerRef target = ctx.get(targetArg);
        if (target == null || !target.isValid() || target.getUuid() == null) {
            ctx.sendMessage(Message.raw("ctzmarktest: target player is offline or not resolvable."));
            return;
        }

        Ref<EntityStore> ref = target.getReference();
        if (ref == null || !ref.isValid()) {
            ctx.sendMessage(Message.raw("ctzmarktest: could not resolve the target entity ref."));
            logger.atWarning().log("CTZ_MARK_TEST resolve_failed step=ref player=" + target.getUuid());
            return;
        }

        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            ctx.sendMessage(Message.raw("ctzmarktest: could not resolve the target world store."));
            logger.atWarning().log("CTZ_MARK_TEST resolve_failed step=store player=" + target.getUuid());
            return;
        }

        EntityStore entityStore = store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        if (world == null) {
            ctx.sendMessage(Message.raw("ctzmarktest: could not resolve the target world."));
            logger.atWarning().log("CTZ_MARK_TEST resolve_failed step=world player=" + target.getUuid());
            return;
        }

        final String requestedEffectId = ctx.provided(effectIdArg) ? ctx.get(effectIdArg).trim() : null;
        final String playerName = target.getUsername();
        final UUID playerUuid = target.getUuid();
        final int entityIndex = ref.getIndex();

        // Component mutations and reads must run on the world's tick thread.
        world.execute(() -> dispatch(ctx, action, requestedEffectId, store, ref, playerName, playerUuid, entityIndex));
    }

    private void dispatch(
        @Nonnull CommandContext ctx,
        @Nonnull String action,
        String requestedEffectId,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull String playerName,
        @Nonnull UUID playerUuid,
        int entityIndex
    ) {
        EffectControllerComponent ec = store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (ec == null) {
            logger.atWarning().log("CTZ_MARK_TEST no_effect_controller player=" + playerName + " uuid=" + playerUuid
                + " entityIndex=" + entityIndex);
            ctx.sendMessage(Message.raw("ctzmarktest: target has no EffectControllerComponent."));
            return;
        }

        switch (action) {
            case "on" -> applyOne(ctx, ec, store, ref,
                requestedEffectId == null || requestedEffectId.isBlank() ? DEFAULT_EFFECT_ID : requestedEffectId,
                playerName, playerUuid, entityIndex);
            case "off" -> removeOne(ctx, ec, store, ref,
                requestedEffectId == null || requestedEffectId.isBlank() ? DEFAULT_EFFECT_ID : requestedEffectId,
                playerName, playerUuid, entityIndex);
            case "both" -> {
                applyOne(ctx, ec, store, ref, DEFAULT_EFFECT_ID, playerName, playerUuid, entityIndex);
                applyOne(ctx, ec, store, ref, CROWN_EFFECT_ID, playerName, playerUuid, entityIndex);
            }
            case "status" -> status(ctx, ec, playerName, playerUuid, entityIndex);
            default -> { /* validated earlier */ }
        }
    }

    private void applyOne(
        @Nonnull CommandContext ctx,
        @Nonnull EffectControllerComponent ec,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull String effectId,
        @Nonnull String playerName,
        @Nonnull UUID playerUuid,
        int entityIndex
    ) {
        int index = EntityEffect.getAssetMap().getIndexOrDefault(effectId, -1);
        if (index < 0) {
            ctx.sendMessage(Message.raw("ctzmarktest: effect asset '" + effectId + "' not found on this server."));
            logger.atSevere().log("CTZ_MARK_TEST asset_not_found effect=" + effectId);
            return;
        }
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
        if (effect == null) {
            ctx.sendMessage(Message.raw("ctzmarktest: effect asset '" + effectId + "' resolved to null."));
            logger.atSevere().log("CTZ_MARK_TEST asset_null effect=" + effectId + " index=" + index);
            return;
        }
        boolean applied = ec.addEffect(ref, effect, FALLBACK_DURATION_SECONDS, OverlapBehavior.OVERWRITE, store);
        logger.atInfo().log("CTZ_MARK_TEST on player=" + playerName + " uuid=" + playerUuid
            + " entityIndex=" + entityIndex + " effect=" + effectId + " index=" + index
            + " applied=" + applied + " activeAfter=" + Arrays.toString(ec.getActiveEffectIndexes()));
        ctx.sendMessage(Message.raw("ctzmarktest ON: '" + effectId + "' -> " + playerName
            + " (index=" + index + ", applied=" + applied + ")."));
    }

    private void removeOne(
        @Nonnull CommandContext ctx,
        @Nonnull EffectControllerComponent ec,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull String effectId,
        @Nonnull String playerName,
        @Nonnull UUID playerUuid,
        int entityIndex
    ) {
        int index = EntityEffect.getAssetMap().getIndexOrDefault(effectId, -1);
        if (index < 0) {
            ctx.sendMessage(Message.raw("ctzmarktest: effect asset '" + effectId + "' not found on this server."));
            logger.atSevere().log("CTZ_MARK_TEST asset_not_found effect=" + effectId);
            return;
        }
        boolean hadEffect = ec.hasEffect(index);
        // Remove ONLY this effect by its index. Never clearEffects() (would wipe legitimate effects).
        ec.removeEffect(ref, index, store);
        logger.atInfo().log("CTZ_MARK_TEST off player=" + playerName + " uuid=" + playerUuid
            + " entityIndex=" + entityIndex + " effect=" + effectId + " index=" + index
            + " hadEffect=" + hadEffect + " activeAfter=" + Arrays.toString(ec.getActiveEffectIndexes()));
        ctx.sendMessage(Message.raw("ctzmarktest OFF: removed '" + effectId + "' from " + playerName
            + " (wasPresent=" + hadEffect + ")."));
    }

    private void status(
        @Nonnull CommandContext ctx,
        @Nonnull EffectControllerComponent ec,
        @Nonnull String playerName,
        @Nonnull UUID playerUuid,
        int entityIndex
    ) {
        int[] active = ec.getActiveEffectIndexes();
        Int2ObjectMap<ActiveEntityEffect> activeMap = ec.getActiveEffects();
        StringBuilder details = new StringBuilder();
        for (int idx : active) {
            ActiveEntityEffect a = activeMap == null ? null : activeMap.get(idx);
            EntityEffect asset = EntityEffect.getAssetMap().getAsset(idx);
            details.append(idx).append(':')
                .append(asset == null ? "?" : asset.getId())
                .append(a != null && a.isInfinite() ? "(inf)" : "")
                .append(' ');
        }
        String detailStr = details.toString().trim();

        int dropIndex = EntityEffect.getAssetMap().getIndexOrDefault(DEFAULT_EFFECT_ID, -1);
        int crownIndex = EntityEffect.getAssetMap().getIndexOrDefault(CROWN_EFFECT_ID, -1);
        boolean hasDrop = dropIndex >= 0 && ec.hasEffect(dropIndex);
        boolean hasCrown = crownIndex >= 0 && ec.hasEffect(crownIndex);

        logger.atInfo().log("CTZ_MARK_TEST status player=" + playerName + " uuid=" + playerUuid
            + " entityIndex=" + entityIndex + " hasComponent=true"
            + " has(" + DEFAULT_EFFECT_ID + ")=" + hasDrop
            + " has(" + CROWN_EFFECT_ID + ")=" + hasCrown
            + " active=[" + detailStr + "]");
        ctx.sendMessage(Message.raw("ctzmarktest STATUS " + playerName + ": "
            + DEFAULT_EFFECT_ID + "=" + hasDrop + " " + CROWN_EFFECT_ID + "=" + hasCrown
            + " active=[" + detailStr + "]"));
    }
}
