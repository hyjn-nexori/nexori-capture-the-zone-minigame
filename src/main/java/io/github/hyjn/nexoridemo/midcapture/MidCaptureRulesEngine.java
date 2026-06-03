package io.github.hyjn.nexoridemo.midcapture;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBinding;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import io.github.hyjn.nexoridemo.midcapture.events.MidCaptureMatchFinishedEvent;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.joml.Vector3d;
import org.joml.Vector3i;

final class MidCaptureRulesEngine {

    static final String RULES_ENGINE_ID = "capture_the_zone";

    private static final long ZONE_DEBUG_LOG_INTERVAL_MS = 3_000L;
    private static final String RESPAWN_PAGE_CLASS_NAME = "com.hypixel.hytale.server.core.entity.entities.player.pages.RespawnPage";

    private final HytaleLogger logger;
    private final MidCaptureEventBus eventBus;
    private final Map<String, Long> zoneDebugLogAtEpochMsByKey = new LinkedHashMap<>();

    MidCaptureRulesEngine(@Nonnull HytaleLogger logger, @Nonnull MidCaptureEventBus eventBus) {
        this.logger = logger;
        this.eventBus = eventBus;
    }

    @Nonnull
    String rulesEngineId() {
        return RULES_ENGINE_ID;
    }

    void onMatchAccepted(@Nonnull MidCaptureMatchRuntime match) {
        logger.atInfo().log(
            "MID_CAPTURE_ACCEPTED_MATCH matchId=" + match.getMatchId()
                + " queueId=" + match.getQueueId()
                + " arenaId=" + match.getArenaId()
                + " rulesEngineId=" + match.getRulesEngineId()
        );
    }

    void onWaitingForPlacement(@Nonnull MidCaptureMatchRuntime match) {
        match.setZoneState(MidCaptureZoneState.PREPARING);
        match.setCapturingPlayerUuid(null);
    }

    void onPlayerTick(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull MidCapturePlayerRuntime playerRuntime,
        @Nonnull Player player,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        long nowEpochMs
    ) {
        PlayerRef playerRef = store.getComponent(ref, Universe.get().getPlayerRefComponentType());
        TransformComponent transformComponent = store.getComponent(ref, TransformComponent.getComponentType());
        if (playerRef == null || transformComponent == null) {
            return;
        }

        updateLivePlayerState(match, playerRuntime, playerRef, transformComponent, store, ref, nowEpochMs);
        configureHomeRespawnIfNeeded(match, playerRuntime, player, transformComponent);
        if (!match.isResolved()) {
            handleRespawnIfNeeded(match, playerRuntime, ref, store, playerRef, nowEpochMs);
        }
    }

    void onGameTick(
        @Nonnull MidCaptureMatchRuntime match,
        long nowEpochMs
    ) {
        if (!match.isResolved()) {
            advanceMatch(match, nowEpochMs);
        }
    }

    /**
     * A player is inside the capture zone when they are within the vertical band AND within the
     * horizontal circle. Height and radius are evaluated separately so the horizontal test can be a
     * true circle (matching the circular {@code Totem_Heal_Simple_Test} visual) while the vertical
     * band stays a simple min/max range.
     */
    boolean isInsideCaptureZone(@Nonnull Vector3d position) {
        return isWithinCaptureHeight(position) && isWithinCaptureRadius(position);
    }

    /** Vertical band check only: unchanged Y range [{@code CAPTURE_MIN_Y}, {@code CAPTURE_MAX_Y}]. */
    boolean isWithinCaptureHeight(@Nonnull Vector3d position) {
        return position.y >= MidCaptureConfig.CAPTURE_MIN_Y
            && position.y <= MidCaptureConfig.CAPTURE_MAX_Y;
    }

    /**
     * Horizontal circle check only (X/Z). Uses squared distance to avoid a sqrt:
     * {@code dx*dx + dz*dz <= radius*radius}. This replaces the old square AABB test, so positions in
     * the former AABB corners (outside the inscribed circle) now correctly count as OUTSIDE the zone —
     * which is expected and makes the logical zone match the circular visual.
     */
    boolean isWithinCaptureRadius(@Nonnull Vector3d position) {
        double dx = position.x - MidCaptureConfig.CAPTURE_CENTER_X;
        double dz = position.z - MidCaptureConfig.CAPTURE_CENTER_Z;
        double radius = MidCaptureConfig.CAPTURE_RADIUS_XZ;
        return (dx * dx + dz * dz) <= (radius * radius);
    }

    @Nonnull
    private List<MidCaptureHudPlayerLine> buildPlayerLines(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull UUID viewerUuid
    ) {
        List<MidCapturePlayerRuntime> orderedPlayers = new ArrayList<>(match.getPlayersByUuid().values());
        orderedPlayers.sort(
            Comparator.comparing((MidCapturePlayerRuntime playerState) -> !playerState.getPlayerUuid().equals(viewerUuid))
                .thenComparing(Comparator.comparingDouble(MidCapturePlayerRuntime::getCaptureProgressSeconds).reversed())
                .thenComparing(MidCapturePlayerRuntime::getPlayerName, String.CASE_INSENSITIVE_ORDER)
        );

        List<MidCaptureHudPlayerLine> lines = new ArrayList<>(orderedPlayers.size());
        for (MidCapturePlayerRuntime playerState : orderedPlayers) {
            lines.add(new MidCaptureHudPlayerLine(
                playerState.getPlayerName(),
                progressRatio(playerState.getCaptureProgressSeconds()),
                formatProgressPercent(playerState.getCaptureProgressSeconds()),
                playerState.getPlayerUuid().equals(viewerUuid)
            ));
        }
        return lines;
    }

    @Nonnull
    MidCaptureHudSnapshot buildHudSnapshot(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull MidCapturePlayerRuntime playerRuntime,
        @Nonnull UUID viewerUuid,
        long nowEpochMs
    ) {
        List<MidCaptureHudPlayerLine> playerLines = buildPlayerLines(match, viewerUuid);
        String respawnPenaltyText = "Respawn: " + formatPenaltySeconds(playerRuntime.getRespawnPenaltyMs());
        boolean rewardVisible = playerRuntime.getRewardNoticeUntilEpochMs() > nowEpochMs && playerRuntime.getLastRewardAmountMs() > 0L;
        String respawnRewardText = rewardVisible
            ? "Kill bonus: -" + (playerRuntime.getLastRewardAmountMs() / 1000L) + "s"
            : "";
        String respawnRewardColor = rewardVisible
            ? buildRewardFadeColor(playerRuntime.getRewardNoticeUntilEpochMs() - nowEpochMs)
            : "#9FF0A8";
        long respawnRemainingMs = playerRuntime.getRespawnDueAtEpochMs() <= 0L
            ? 0L
            : Math.max(0L, playerRuntime.getRespawnDueAtEpochMs() - nowEpochMs);

        if (!playerRuntime.isAlive() && respawnRemainingMs > 0L) {
            return MidCaptureHudSnapshot.of(
                "MID CONTROL",
                "Respawn in " + formatRespawnSeconds(respawnRemainingMs),
                "#FF7C7C",
                playerLines,
                respawnPenaltyText,
                respawnRewardText,
                respawnRewardColor
            );
        }

        // Placement waiting is now owned by Nexori's blue "waiting for players" card; the minigame HUD
        // is suppressed until the match starts (see MidCaptureMinigameService.findHudSnapshot).
        return MidCaptureHudSnapshot.of(
            "MID CONTROL",
            buildZoneStatusText(match),
            resolveAccentColor(match),
            playerLines,
            respawnPenaltyText,
            respawnRewardText,
            respawnRewardColor
        );
    }

    @Nonnull
    String buildZoneStatusText(@Nonnull MidCaptureMatchRuntime match) {
        return switch (match.getZoneState()) {
            case PREPARING -> "Waiting for placements";
            case EMPTY -> "No one is holding the zone";
            case CONTESTED -> "Zone contested";
            case CAPTURING -> {
                MidCapturePlayerRuntime capturer = match.getCapturingPlayerUuid() == null
                    ? null
                    : match.getPlayersByUuid().get(match.getCapturingPlayerUuid());
                if (capturer == null) {
                    yield "Capturing";
                }
                int secondsLeft = (int) Math.ceil(
                    Math.max(0.0D, MidCaptureConfig.CAPTURE_SECONDS_TO_WIN - capturer.getCaptureProgressSeconds())
                );
                yield capturer.getPlayerName() + " is capturing (" + secondsLeft + "s)";
            }
        };
    }

    @Nonnull
    private String resolveAccentColor(@Nonnull MidCaptureMatchRuntime match) {
        return switch (match.getZoneState()) {
            case PREPARING, EMPTY -> "#82C7FF";
            case CAPTURING -> "#9FF0A8";
            case CONTESTED -> "#FFD36E";
        };
    }

    private void updateLivePlayerState(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull MidCapturePlayerRuntime playerRuntime,
        @Nonnull PlayerRef playerRef,
        @Nonnull TransformComponent transformComponent,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        long nowEpochMs
    ) {
        Transform playerRefTransform = playerRef.getTransform();
        Vector3d positionFromStoreComponent = transformComponent.getPosition() == null ? null : new Vector3d(transformComponent.getPosition());
        Vector3d positionFromPlayerRef = playerRefTransform == null || playerRefTransform.getPosition() == null
            ? null
            : new Vector3d(playerRefTransform.getPosition());
        Vector3d chosenLivePosition = positionFromStoreComponent != null ? positionFromStoreComponent : positionFromPlayerRef;
        playerRuntime.setLivePosition(chosenLivePosition);
        playerRuntime.setAlive(store.getComponent(ref, DeathComponent.getComponentType()) == null);
        maybeLogPositionSources(
            playerRuntime,
            match.getMatchId(),
            nowEpochMs,
            positionFromStoreComponent,
            null,
            positionFromPlayerRef,
            chosenLivePosition
        );
    }

    private void configureHomeRespawnIfNeeded(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull MidCapturePlayerRuntime playerRuntime,
        @Nonnull Player player,
        @Nonnull TransformComponent transformComponent
    ) {
        if (!match.isStartAllowed() || playerRuntime.isHomeRespawnConfigured()) {
            return;
        }

        Transform homeSpawn = transformComponent.getTransform().clone();
        configureHomeRespawn(player, match.getWorldName(), homeSpawn);
        playerRuntime.setHomeSpawn(homeSpawn);
        playerRuntime.setHomeRespawnConfigured(true);
        logger.atInfo().log(
            "MID_CAPTURE_RESPAWN_SET player=" + playerRuntime.getPlayerName()
                + " matchId=" + match.getMatchId()
                + " world=" + match.getWorldName()
                + " position=" + homeSpawn.getPosition()
        );
    }

    private void configureHomeRespawn(
        @Nonnull Player player,
        @Nonnull String worldName,
        @Nonnull Transform homeSpawn
    ) {
        Vector3d position = new Vector3d(homeSpawn.getPosition());
        Vector3i blockPosition = new Vector3i(
            (int) Math.floor(position.x),
            (int) Math.floor(position.y),
            (int) Math.floor(position.z)
        );
        PlayerRespawnPointData respawnPoint = new PlayerRespawnPointData(blockPosition, position, MidCaptureConfig.HOME_RESPAWN_NAME);
        player.getPlayerConfigData().getPerWorldData(worldName).setRespawnPoints(new PlayerRespawnPointData[] {respawnPoint});
        player.markNeedsSave();
    }

    private void handleRespawnIfNeeded(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull MidCapturePlayerRuntime playerRuntime,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull PlayerRef playerRef,
        long nowEpochMs
    ) {
        DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
        Player player = store.getComponent(ref, Player.getComponentType());
        if (deathComponent == null) {
            if (playerRuntime.getRespawnDueAtEpochMs() > nowEpochMs) {
                logger.atInfo().log(
                    "MID_CAPTURE_RESPAWN_EXTERNAL_OR_EARLY player=" + playerRuntime.getPlayerName()
                        + " matchId=" + match.getMatchId()
                        + " nowMs=" + nowEpochMs
                        + " dueAtMs=" + playerRuntime.getRespawnDueAtEpochMs()
                        + " remainingMs=" + (playerRuntime.getRespawnDueAtEpochMs() - nowEpochMs)
                        + " respawnPagePatched=" + playerRuntime.isRespawnPagePatched()
                );
            }
            playerRuntime.setRespawnDueAtEpochMs(0L);
            playerRuntime.setRespawnPagePatched(false);
            return;
        }

        deathComponent.setShowDeathMenu(true);
        deathComponent.setDisplayDataOnDeathScreen(false);
        patchActiveRespawnPage(ref, store, player, playerRuntime);

        if (playerRuntime.getRespawnDueAtEpochMs() <= 0L) {
            applyKillRespawnReward(match, playerRuntime, deathComponent, store, nowEpochMs);
            long respawnDelayMs = Math.max(0L, playerRuntime.getRespawnPenaltyMs());
            playerRuntime.setRespawnDueAtEpochMs(nowEpochMs + respawnDelayMs);
            logger.atInfo().log(
                "MID_CAPTURE_RESPAWN_SCHEDULED player=" + playerRuntime.getPlayerName()
                    + " delayMs=" + respawnDelayMs
                    + " currentPenaltyMs=" + playerRuntime.getRespawnPenaltyMs()
            );
        }

        if (nowEpochMs < playerRuntime.getRespawnDueAtEpochMs()) {
            long remainingMs = playerRuntime.getRespawnDueAtEpochMs() - nowEpochMs;
            if (remainingMs > 0 && remainingMs <= 250L) {
                logger.atInfo().log(
                    "MID_CAPTURE_RESPAWN_COUNTDOWN player=" + playerRuntime.getPlayerName()
                        + " matchId=" + match.getMatchId()
                        + " remainingMs=" + remainingMs
                        + " dueAtMs=" + playerRuntime.getRespawnDueAtEpochMs()
                );
            }
            return;
        }

        playerRuntime.setLastRespawnAtEpochMs(nowEpochMs);
        playerRuntime.setRespawnDueAtEpochMs(0L);
        playerRuntime.setRespawnPagePatched(false);
        logger.atInfo().log(
            "MID_CAPTURE_RESPAWN_TRIGGER player=" + playerRuntime.getPlayerName()
                + " matchId=" + match.getMatchId()
                + " nowMs=" + nowEpochMs
                + " penaltyBeforeIncreaseMs=" + playerRuntime.getRespawnPenaltyMs()
        );
        try {
            DeathComponent.respawn(store, ref);
            playerRuntime.setRespawnPenaltyMs(playerRuntime.getRespawnPenaltyMs() + MidCaptureConfig.RESPAWN_DELAY_PER_DEATH_MS);
            long addedPenaltySeconds = Math.max(0L, MidCaptureConfig.RESPAWN_DELAY_PER_DEATH_MS / 1000L);
            playerRef.sendMessage(Message.raw(
                "Respawn penalty added: +" + addedPenaltySeconds + "s for your next death."
            ));
            logger.atInfo().log(
                "MID_CAPTURE_RESPAWN_PENALTY_INCREASED player=" + playerRuntime.getPlayerName()
                    + " newPenaltyMs=" + playerRuntime.getRespawnPenaltyMs()
                    + " addedMs=" + MidCaptureConfig.RESPAWN_DELAY_PER_DEATH_MS
            );
        } catch (Exception exception) {
            logger.atWarning().withCause(exception).log(
                "Failed to respawn mid-capture player " + playerRef.getUuid() + "."
            );
        }
    }

    private void applyKillRespawnReward(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull MidCapturePlayerRuntime victimRuntime,
        @Nonnull DeathComponent deathComponent,
        @Nonnull Store<EntityStore> store,
        long nowEpochMs
    ) {
        Damage deathInfo = deathComponent.getDeathInfo();
        if (deathInfo == null || !(deathInfo.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> killerEntityRef = entitySource.getRef();
        if (killerEntityRef == null || !killerEntityRef.isValid()) {
            return;
        }

        PlayerRef killerPlayerRef = store.getComponent(killerEntityRef, Universe.get().getPlayerRefComponentType());
        if (killerPlayerRef == null || killerPlayerRef.getUuid() == null) {
            return;
        }
        if (killerPlayerRef.getUuid().equals(victimRuntime.getPlayerUuid())) {
            return;
        }

        MidCapturePlayerRuntime killerRuntime = match.getPlayersByUuid().get(killerPlayerRef.getUuid());
        if (killerRuntime == null) {
            return;
        }

        long previousPenaltyMs = killerRuntime.getRespawnPenaltyMs();
        long nextPenaltyMs = Math.max(0L, previousPenaltyMs - MidCaptureConfig.RESPAWN_KILL_REWARD_MS);
        long rewardAppliedMs = previousPenaltyMs - nextPenaltyMs;
        killerRuntime.setRespawnPenaltyMs(nextPenaltyMs);
        if (rewardAppliedMs > 0L) {
            killerRuntime.setLastRewardAmountMs(rewardAppliedMs);
            killerRuntime.setRewardNoticeUntilEpochMs(nowEpochMs + MidCaptureConfig.RESPAWN_REWARD_HUD_MS);
        }

        logger.atInfo().log(
            "MID_CAPTURE_RESPAWN_REWARD killer=" + killerRuntime.getPlayerName()
                + " killerPenaltyBeforeMs=" + previousPenaltyMs
                + " killerPenaltyAfterMs=" + nextPenaltyMs
                + " victim=" + victimRuntime.getPlayerName()
                + " rewardMs=" + rewardAppliedMs
        );
    }

    private void advanceMatch(
        @Nonnull MidCaptureMatchRuntime match,
        long nowEpochMs
    ) {
        long previousAdvanceAt = match.getLastAdvanceAtEpochMs();
        if (previousAdvanceAt > 0L && nowEpochMs - previousAdvanceAt < MidCaptureConfig.WORLD_ADVANCE_INTERVAL_MS) {
            return;
        }
        match.setLastAdvanceAtEpochMs(nowEpochMs);

        if (!match.isStartAllowed()) {
            return;
        }

        double deltaSeconds = previousAdvanceAt <= 0L
            ? MidCaptureConfig.WORLD_ADVANCE_INTERVAL_MS / 1000.0D
            : Math.max(0.0D, (nowEpochMs - previousAdvanceAt) / 1000.0D);

        List<MidCapturePlayerRuntime> insidePlayers = new ArrayList<>();
        for (MidCapturePlayerRuntime playerRuntime : match.getPlayersByUuid().values()) {
            ZonePresenceEvaluation evaluation = evaluatePlayerZonePresence(match, playerRuntime);
            maybeLogZoneEvaluation(match, playerRuntime, evaluation, nowEpochMs);
            if (evaluation.counted()) {
                insidePlayers.add(playerRuntime);
            }
        }

        if (insidePlayers.size() == 1) {
            MidCapturePlayerRuntime capturer = insidePlayers.get(0);
            match.setZoneState(MidCaptureZoneState.CAPTURING);
            match.setCapturingPlayerUuid(capturer.getPlayerUuid());
            for (MidCapturePlayerRuntime playerRuntime : match.getPlayersByUuid().values()) {
                if (playerRuntime.getPlayerUuid().equals(capturer.getPlayerUuid())) {
                    playerRuntime.setCaptureProgressSeconds(clampProgress(
                        playerRuntime.getCaptureProgressSeconds() + deltaSeconds
                    ));
                }
            }
        } else if (insidePlayers.size() > 1) {
            match.setZoneState(MidCaptureZoneState.CONTESTED);
            match.setCapturingPlayerUuid(null);
            for (MidCapturePlayerRuntime playerRuntime : insidePlayers) {
                playerRuntime.setCaptureProgressSeconds(clampProgress(
                    playerRuntime.getCaptureProgressSeconds() + deltaSeconds
                ));
            }
        } else {
            match.setZoneState(MidCaptureZoneState.EMPTY);
            match.setCapturingPlayerUuid(null);
        }

        MidCapturePlayerRuntime winner = match.getPlayersByUuid().values().stream()
            .max(Comparator.comparingDouble(MidCapturePlayerRuntime::getCaptureProgressSeconds)
                .thenComparing(MidCapturePlayerRuntime::getPlayerName))
            .orElse(null);
        if (winner != null && winner.getCaptureProgressSeconds() >= MidCaptureConfig.CAPTURE_SECONDS_TO_WIN) {
            resolveWinner(match, winner, nowEpochMs);
        }
    }

    @Nonnull
    private ZonePresenceEvaluation evaluatePlayerZonePresence(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull MidCapturePlayerRuntime playerRuntime
    ) {
        PlayerRef playerRef = Universe.get().getPlayer(playerRuntime.getPlayerUuid());
        if (playerRef == null || !playerRef.isValid() || playerRef.getReference() == null) {
            return new ZonePresenceEvaluation(false, false, "player_ref_missing", "<unknown>");
        }

        Vector3d position = playerRuntime.getLivePosition();
        String positionText = position == null ? "<unknown>" : position.toString();
        boolean geometryInside = position != null && isInsideCaptureZone(position);

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        Store<EntityStore> playerStore = playerEntityRef.getStore();
        Player player = playerStore.getComponent(playerEntityRef, Player.getComponentType());
        TransformComponent liveTransformComponent = playerStore.getComponent(playerEntityRef, TransformComponent.getComponentType());
        Vector3d liveRefPosition = liveTransformComponent == null || liveTransformComponent.getPosition() == null
            ? null
            : new Vector3d(liveTransformComponent.getPosition());
        if (player == null || player.getWorld() == null) {
            return new ZonePresenceEvaluation(false, geometryInside, "player_world_missing", positionText);
        }

        if (!player.getWorld().getName().equalsIgnoreCase(match.getWorldName())) {
            return new ZonePresenceEvaluation(false, geometryInside, "wrong_world:" + player.getWorld().getName(), positionText);
        }

        if (liveTransformComponent != null && liveTransformComponent.getPosition() != null) {
            position = new Vector3d(liveTransformComponent.getPosition());
            positionText = position.toString();
            geometryInside = isInsideCaptureZone(position);
        }

        if (!playerRuntime.isAlive()) {
            return new ZonePresenceEvaluation(false, geometryInside, "player_dead", positionText);
        }

        if (position == null) {
            return new ZonePresenceEvaluation(false, false, "transform_missing", "<unknown>");
        }

        if (!geometryInside) {
            return new ZonePresenceEvaluation(
                false,
                false,
                "outside_zone used=" + positionText + " liveRef=" + formatVector(liveRefPosition),
                positionText
            );
        }

        return new ZonePresenceEvaluation(true, true, "inside_zone", positionText);
    }

    private void maybeLogZoneEvaluation(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull MidCapturePlayerRuntime playerRuntime,
        @Nonnull ZonePresenceEvaluation evaluation,
        long nowEpochMs
    ) {
        if (evaluation.counted()) {
            logZoneDebug(
                "counted-" + playerRuntime.getPlayerUuid(),
                nowEpochMs,
                "MID_CAPTURE_ZONE player=" + playerRuntime.getPlayerName()
                    + " matchId=" + match.getMatchId()
                    + " counted=true position=" + evaluation.positionText()
            );
            return;
        }

        logZoneDebug(
            "rejected-" + playerRuntime.getPlayerUuid() + "-" + evaluation.reason(),
            nowEpochMs,
            "MID_CAPTURE_ZONE player=" + playerRuntime.getPlayerName()
                + " matchId=" + match.getMatchId()
                + " counted=false reason=" + evaluation.reason()
                + " position=" + evaluation.positionText()
        );
    }

    private void logZoneDebug(@Nonnull String key, long nowEpochMs, @Nonnull String message) {
        Long previousLogAt = zoneDebugLogAtEpochMsByKey.get(key);
        if (previousLogAt != null && nowEpochMs - previousLogAt < ZONE_DEBUG_LOG_INTERVAL_MS) {
            return;
        }
        zoneDebugLogAtEpochMsByKey.put(key, nowEpochMs);
        logger.atInfo().log(message);
    }

    private void maybeLogPositionSources(
        @Nonnull MidCapturePlayerRuntime playerRuntime,
        @Nonnull String matchId,
        long nowEpochMs,
        Vector3d positionFromStoreComponent,
        Vector3d positionFromPlayerEntity,
        Vector3d positionFromPlayerRef,
        Vector3d chosenLivePosition
    ) {
        logZoneDebug(
            "position-sources-" + playerRuntime.getPlayerUuid(),
            nowEpochMs,
            "MID_CAPTURE_POSITION_SOURCES player=" + playerRuntime.getPlayerName()
                + " matchId=" + matchId
                + " store=" + formatVector(positionFromStoreComponent)
                + " playerEntity=" + formatVector(positionFromPlayerEntity)
                + " playerRef=" + formatVector(positionFromPlayerRef)
                + " chosen=" + formatVector(chosenLivePosition)
        );
    }

    @Nonnull
    private String formatVector(Vector3d vector) {
        return vector == null ? "<null>" : vector.toString();
    }

    private void resolveWinner(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull MidCapturePlayerRuntime winner,
        long nowEpochMs
    ) {
        if (match.isResolved()) {
            return;
        }
        if (!RULES_ENGINE_ID.equals(match.getRulesEngineId())) {
            logger.atWarning().log(
                "Failed to resolve mid-capture match result matchId=" + match.getMatchId()
                    + " reason=rules_engine_mismatch"
                    + " expected=" + RULES_ENGINE_ID
                    + " actual=" + match.getRulesEngineId()
            );
            return;
        }
        List<UUID> participantPlayerUuids = match.getRequiredResultPlayerUuids();
        if (participantPlayerUuids == null || participantPlayerUuids.isEmpty()) {
            logger.atWarning().log(
                "Failed to resolve mid-capture match result matchId=" + match.getMatchId()
                    + " reason=required_players_missing"
            );
            return;
        }
        match.setResolved(true);
        eventBus.publish(new MidCaptureMatchFinishedEvent(
            match.getMatchId(),
            winner.getPlayerUuid(),
            participantPlayerUuids,
            "mid_capture_point_captured",
            nowEpochMs
        ));
        logger.atInfo().log(
            "Finished mid-capture match locally matchId=" + match.getMatchId()
                + " winnerPlayerUuid=" + winner.getPlayerUuid()
                + " participantCount=" + participantPlayerUuids.size()
        );
    }

    private void decayProgress(@Nonnull MidCapturePlayerRuntime playerRuntime, double deltaSeconds) {
        playerRuntime.setCaptureProgressSeconds(clampProgress(
            playerRuntime.getCaptureProgressSeconds() - (deltaSeconds * MidCaptureConfig.PROGRESS_DECAY_PER_SECOND)
        ));
    }

    private double clampProgress(double rawProgressSeconds) {
        return Math.max(0.0D, Math.min(MidCaptureConfig.CAPTURE_SECONDS_TO_WIN, rawProgressSeconds));
    }

    @Nonnull
    private static String formatProgressPercent(double progressSeconds) {
        int percent = (int) Math.round((progressSeconds / MidCaptureConfig.CAPTURE_SECONDS_TO_WIN) * 100.0D);
        return Math.max(0, percent) + "%";
    }

    @Nonnull
    private static String formatRespawnSeconds(long remainingMs) {
        return String.format("%.1fs", remainingMs / 1000.0D);
    }

    @Nonnull
    private static String formatPenaltySeconds(long penaltyMs) {
        return String.format("%.1fs", penaltyMs / 1000.0D);
    }

    @Nonnull
    private static String buildRewardFadeColor(long rewardRemainingMs) {
        long clampedRemaining = Math.max(0L, Math.min(MidCaptureConfig.RESPAWN_REWARD_HUD_MS, rewardRemainingMs));
        double ratio = clampedRemaining / (double) MidCaptureConfig.RESPAWN_REWARD_HUD_MS;
        int startR = 0x9F;
        int startG = 0xF0;
        int startB = 0xA8;
        int endR = 0x3A;
        int endG = 0x55;
        int endB = 0x3E;
        int r = (int) Math.round(endR + ((startR - endR) * ratio));
        int g = (int) Math.round(endG + ((startG - endG) * ratio));
        int b = (int) Math.round(endB + ((startB - endB) * ratio));
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static float progressRatio(double progressSeconds) {
        return (float) Math.max(0.0D, Math.min(1.0D, progressSeconds / MidCaptureConfig.CAPTURE_SECONDS_TO_WIN));
    }

    private void patchActiveRespawnPage(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        Player player,
        @Nonnull MidCapturePlayerRuntime playerRuntime
    ) {
        if (player == null || playerRuntime.isRespawnPagePatched()) {
            return;
        }
        PageManager pageManager = player.getPageManager();
        if (pageManager == null || pageManager.getCustomPage() == null) {
            return;
        }
        if (!RESPAWN_PAGE_CLASS_NAME.equals(pageManager.getCustomPage().getClass().getName())) {
            return;
        }

        UICommandBuilder commands = new UICommandBuilder();
        commands.set("#RespawnButton.Visible", false);
        commands.set("#RespawnButton.Disabled", true);
        commands.set("#DeathData.Visible", false);

        CustomPage patch = new CustomPage(
            RESPAWN_PAGE_CLASS_NAME,
            false,
            false,
            pageManager.getCustomPage().getLifetime(),
            commands.getCommands(),
            new CustomUIEventBinding[0]
        );
        pageManager.updateCustomPage(patch);
        playerRuntime.setRespawnPagePatched(true);

        logger.atInfo().log(
            "MID_CAPTURE_RESPAWN_PAGE_PATCHED player=" + playerRuntime.getPlayerName()
                + " key=" + RESPAWN_PAGE_CLASS_NAME
                + " hidden=[#RespawnButton,#DeathData]"
        );
    }

    private record ZonePresenceEvaluation(
        boolean counted,
        boolean geometryInside,
        String reason,
        String positionText
    ) {
    }
}
