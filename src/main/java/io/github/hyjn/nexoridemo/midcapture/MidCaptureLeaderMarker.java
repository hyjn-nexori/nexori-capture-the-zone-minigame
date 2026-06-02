package io.github.hyjn.nexoridemo.midcapture;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies a purely-visual "leader" crown marker to the Capture The Zone player with the highest
 * capture progress, and moves it as the lead changes.
 *
 * <p>The marker is a single infinite, visual-only {@link EntityEffect} ({@code Ctz_Crown_Test},
 * wrapping the stock {@code Crown_Gold} ModelVFX) applied via {@code EffectControllerComponent}. The
 * crown sits above the head, follows the player and is removed cleanly; unlike the older glow effect
 * it does not break visually when the player equips armor, so no periodic refresh is needed. Marker
 * state ({@code markedLeaderUuid}) lives on {@link MidCaptureMatchRuntime}; reconciliation runs from
 * the gameplay tick (throttled) and mutates the effect when the leader changes, plus a throttled
 * presence check that re-applies the crown only if it was lost (e.g. death/respawn clears
 * EntityEffects) — never re-applying when it is already active, so there is no stacking or per-tick
 * spam. All effect mutations run on the target's world thread; resolution and cleanup
 * are best-effort and never throw on offline players. It never calls {@code clearEffects()} — only the
 * crown effect is removed, by index.</p>
 */
final class MidCaptureLeaderMarker {

    /** Demo EntityEffect wrapping the stock {@code Crown_Gold} ModelVFX — the only automatic marker effect. */
    private static final String CROWN_EFFECT_ID = "Ctz_Crown_Test";

    /** Marker effect is infinite; a large duration is a defensive fallback if infinite is not honored. */
    private static final float FALLBACK_DURATION_SECONDS = 86_400.0f;

    /** Throttle so the leader is recomputed at most this often, regardless of tick frequency. */
    private static final long RECONCILE_INTERVAL_MS = 500L;

    /**
     * While the leader is unchanged, verify at most this often that the crown is still present and
     * re-apply it only if it was lost (e.g. death/respawn clears EntityEffects). This is a presence
     * check, NOT a forced remove/apply refresh: if the crown is already active it is left untouched.
     */
    private static final long ENSURE_INTERVAL_MS = 1_000L;

    private final HytaleLogger logger;

    MidCaptureLeaderMarker(@Nonnull HytaleLogger logger) {
        this.logger = logger;
    }

    /**
     * Recomputes the progress leader for the match and applies/removes the crown marker when it changes.
     * No-op (other than throttling) while the match has not started or is already resolved.
     */
    void reconcile(@Nonnull MidCaptureMatchRuntime match, long nowEpochMs) {
        if (nowEpochMs - match.getLastLeaderReconcileAtEpochMs() < RECONCILE_INTERVAL_MS) {
            return;
        }
        match.setLastLeaderReconcileAtEpochMs(nowEpochMs);

        // Only mark a leader during live gameplay (after the Nexori start gate) and before resolution.
        if (!match.isStartAllowed() || match.isResolved()) {
            clearMarker(match);
            return;
        }

        UUID newLeader = computeLeader(match);
        UUID oldLeader = match.getMarkedLeaderUuid();
        if (Objects.equals(newLeader, oldLeader)) {
            // Leader unchanged: don't blindly no-op. The crown can be lost on death/respawn (Hytale
            // clears EntityEffects), so periodically verify it is still present and re-apply only if
            // missing. This is a presence check, not a forced remove/apply, so there is no stacking.
            if (newLeader != null && nowEpochMs - match.getLastLeaderMarkerEnsureAtEpochMs() >= ENSURE_INTERVAL_MS) {
                match.setLastLeaderMarkerEnsureAtEpochMs(nowEpochMs);
                MidCapturePlayerRuntime leaderRuntime = match.getPlayersByUuid().get(newLeader);
                double progress = leaderRuntime == null ? -1.0 : leaderRuntime.getCaptureProgressSeconds();
                ensureMarkerPresent(newLeader, match.getMatchId(), progress);
            }
            return;
        }

        if (oldLeader != null) {
            removeMarker(oldLeader, match.getMatchId(), "leader_changed");
        }
        double newProgress = -1.0;
        if (newLeader != null) {
            MidCapturePlayerRuntime leaderRuntime = match.getPlayersByUuid().get(newLeader);
            newProgress = leaderRuntime == null ? -1.0 : leaderRuntime.getCaptureProgressSeconds();
            applyMarker(newLeader, match.getMatchId(), newProgress);
        }
        match.setMarkedLeaderUuid(newLeader);
        // Crown was just (re)applied to the new leader; defer the next presence check by a full interval.
        match.setLastLeaderMarkerEnsureAtEpochMs(nowEpochMs);
        logger.atInfo().log("CTZ_LEADER_MARKER_CHANGED matchId=" + match.getMatchId()
            + " oldLeader=" + oldLeader + " newLeader=" + newLeader
            + " effect=" + CROWN_EFFECT_ID + " progress=" + newProgress);
    }

    /** Removes the crown from the currently-marked leader (if any) and clears the state. Best-effort. */
    void clearMarker(@Nonnull MidCaptureMatchRuntime match) {
        UUID oldLeader = match.getMarkedLeaderUuid();
        if (oldLeader == null) {
            return;
        }
        removeMarker(oldLeader, match.getMatchId(), "cleared");
        match.setMarkedLeaderUuid(null);
        logger.atInfo().log("CTZ_LEADER_MARKER_CLEARED matchId=" + match.getMatchId()
            + " player=" + oldLeader + " effect=" + CROWN_EFFECT_ID);
    }

    /** If the leaving player held the crown, remove it and clear state. Reconcile re-picks next tick. */
    void onPlayerLeft(@Nonnull MidCaptureMatchRuntime match, @Nonnull UUID playerUuid) {
        if (!playerUuid.equals(match.getMarkedLeaderUuid())) {
            return;
        }
        removeMarker(playerUuid, match.getMatchId(), "player_left");
        match.setMarkedLeaderUuid(null);
        logger.atInfo().log("CTZ_LEADER_MARKER_CLEARED matchId=" + match.getMatchId()
            + " player=" + playerUuid + " effect=" + CROWN_EFFECT_ID + " reason=player_left");
    }

    /**
     * Leader = eligible player with the highest capture progress (> 0). Hysteresis: keep the current
     * leader unless another player strictly exceeds their progress; deterministic tie-break (lowest
     * UUID) only when there is no current leader to keep.
     */
    private UUID computeLeader(@Nonnull MidCaptureMatchRuntime match) {
        UUID current = match.getMarkedLeaderUuid();
        double currentProgress = -1.0;
        MidCapturePlayerRuntime currentRuntime = current == null ? null : match.getPlayersByUuid().get(current);
        if (currentRuntime != null && currentRuntime.getCaptureProgressSeconds() > 0.0) {
            currentProgress = currentRuntime.getCaptureProgressSeconds();
        }

        UUID best = null;
        double bestProgress = 0.0;
        for (Map.Entry<UUID, MidCapturePlayerRuntime> entry : match.getPlayersByUuid().entrySet()) {
            double progress = entry.getValue().getCaptureProgressSeconds();
            if (progress <= 0.0) {
                continue;
            }
            if (best == null
                || progress > bestProgress
                || (progress == bestProgress && entry.getKey().compareTo(best) < 0)) {
                best = entry.getKey();
                bestProgress = progress;
            }
        }

        if (best == null) {
            return null;
        }
        // Keep the current leader unless someone strictly exceeds them (currentProgress < global max).
        if (current != null && currentProgress >= bestProgress) {
            return current;
        }
        return best;
    }

    private void applyMarker(@Nonnull UUID playerUuid, @Nonnull String matchId, double progress) {
        withTargetEffectController(playerUuid, matchId, "apply", (ref, store, ec) -> {
            int index = EntityEffect.getAssetMap().getIndexOrDefault(CROWN_EFFECT_ID, -1);
            if (index < 0) {
                logger.atSevere().log("CTZ_LEADER_MARKER asset_missing effect=" + CROWN_EFFECT_ID
                    + " matchId=" + matchId + " player=" + playerUuid);
                return;
            }
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
            if (effect == null) {
                logger.atSevere().log("CTZ_LEADER_MARKER asset_null effect=" + CROWN_EFFECT_ID
                    + " index=" + index + " matchId=" + matchId);
                return;
            }
            boolean hadBefore = ec.hasEffect(index);
            boolean applied = ec.addEffect(ref, effect, FALLBACK_DURATION_SECONDS, OverlapBehavior.OVERWRITE, store);
            boolean hasAfter = ec.hasEffect(index);
            logger.atInfo().log("CTZ_LEADER_MARKER_APPLIED matchId=" + matchId + " player=" + playerUuid
                + " effect=" + CROWN_EFFECT_ID + " index=" + index + " applied=" + applied
                + " hadBefore=" + hadBefore + " hasAfter=" + hasAfter
                + " activeAfter=" + Arrays.toString(ec.getActiveEffectIndexes()) + " progress=" + progress);
        });
    }

    /**
     * Presence check for the current leader: if the crown is missing (e.g. cleared by death/respawn),
     * re-apply it; if it is already active, do nothing. Best-effort — if the player is not resolvable
     * (dead/respawning/offline) this is a quiet no-op and the internal state is left intact so a later
     * tick retries once the entity is resolvable again. Never stacks: it only applies when absent.
     */
    private void ensureMarkerPresent(@Nonnull UUID playerUuid, @Nonnull String matchId, double progress) {
        withTargetEffectController(playerUuid, matchId, "ensure", (ref, store, ec) -> {
            int index = EntityEffect.getAssetMap().getIndexOrDefault(CROWN_EFFECT_ID, -1);
            if (index < 0) {
                logger.atSevere().log("CTZ_LEADER_MARKER asset_missing effect=" + CROWN_EFFECT_ID
                    + " matchId=" + matchId + " player=" + playerUuid);
                return;
            }
            if (ec.hasEffect(index)) {
                // Already present: leave it alone (no per-tick info log, no re-apply, no stacking).
                logger.atFine().log("CTZ_LEADER_MARKER_PRESENT matchId=" + matchId + " player=" + playerUuid
                    + " effect=" + CROWN_EFFECT_ID + " index=" + index);
                return;
            }
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
            if (effect == null) {
                logger.atSevere().log("CTZ_LEADER_MARKER asset_null effect=" + CROWN_EFFECT_ID
                    + " index=" + index + " matchId=" + matchId);
                return;
            }
            boolean applied = ec.addEffect(ref, effect, FALLBACK_DURATION_SECONDS, OverlapBehavior.OVERWRITE, store);
            logger.atInfo().log("CTZ_LEADER_MARKER_REAPPLIED matchId=" + matchId + " player=" + playerUuid
                + " effect=" + CROWN_EFFECT_ID + " index=" + index + " applied=" + applied
                + " hasAfter=" + ec.hasEffect(index)
                + " activeAfter=" + Arrays.toString(ec.getActiveEffectIndexes())
                + " progress=" + progress + " reason=missing_after_respawn");
        });
    }

    private void removeMarker(@Nonnull UUID playerUuid, @Nonnull String matchId, @Nonnull String reason) {
        withTargetEffectController(playerUuid, matchId, "remove", (ref, store, ec) -> {
            int index = EntityEffect.getAssetMap().getIndexOrDefault(CROWN_EFFECT_ID, -1);
            if (index < 0) {
                return;
            }
            boolean had = ec.hasEffect(index);
            // Remove ONLY the crown effect by index. Never clearEffects().
            ec.removeEffect(ref, index, store);
            logger.atInfo().log("CTZ_LEADER_MARKER_REMOVED matchId=" + matchId + " player=" + playerUuid
                + " effect=" + CROWN_EFFECT_ID + " index=" + index + " hadEffect=" + had
                + " activeAfter=" + Arrays.toString(ec.getActiveEffectIndexes()) + " reason=" + reason);
        });
    }

    /**
     * Resolves the player entity (PlayerRef -> Ref -> Store -> EntityStore -> World), then runs the
     * mutation on the world thread with the resolved {@link EffectControllerComponent}. Best-effort:
     * logs and returns quietly if the player is offline or anything cannot be resolved.
     */
    private void withTargetEffectController(
        @Nonnull UUID playerUuid,
        @Nonnull String matchId,
        @Nonnull String op,
        @Nonnull EffectControllerAction action
    ) {
        Universe universe = Universe.get();
        PlayerRef playerRef = universe == null ? null : universe.getPlayer(playerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            logger.atFine().log("CTZ_LEADER_MARKER skip op=" + op + " reason=player_offline matchId=" + matchId
                + " player=" + playerUuid);
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            logger.atWarning().log("CTZ_LEADER_MARKER skip op=" + op + " reason=no_ref matchId=" + matchId
                + " player=" + playerUuid);
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            logger.atWarning().log("CTZ_LEADER_MARKER skip op=" + op + " reason=no_store matchId=" + matchId
                + " player=" + playerUuid);
            return;
        }
        EntityStore entityStore = store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        if (world == null) {
            logger.atWarning().log("CTZ_LEADER_MARKER skip op=" + op + " reason=no_world matchId=" + matchId
                + " player=" + playerUuid);
            return;
        }
        world.execute(() -> {
            EffectControllerComponent ec = store.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ec == null) {
                logger.atWarning().log("CTZ_LEADER_MARKER skip op=" + op + " reason=no_effect_controller matchId="
                    + matchId + " player=" + playerUuid);
                return;
            }
            action.run(ref, store, ec);
        });
    }

    @FunctionalInterface
    private interface EffectControllerAction {
        void run(Ref<EntityStore> ref, Store<EntityStore> store, EffectControllerComponent ec);
    }
}
