package io.github.hyjn.nexoridemo.midcapture;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMatchPlacementState;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriPlayerResolutionOutcome;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriResolvePlayerOutcome;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriResolvePlayerResult;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MidCaptureService {

    private static final long ZONE_DEBUG_LOG_INTERVAL_MS = 3_000L;

    private final NexoriMinigameApi minigameApi;
    private final HytaleLogger logger;
    private final Map<String, MidCaptureMatchState> matchesById = new LinkedHashMap<>();
    private final Map<UUID, String> matchIdByPlayerUuid = new LinkedHashMap<>();
    private final Map<String, Long> zoneDebugLogAtEpochMsByKey = new LinkedHashMap<>();

    public MidCaptureService(@Nonnull NexoriMinigameApi minigameApi, @Nonnull HytaleLogger logger) {
        this.minigameApi = minigameApi;
        this.logger = logger;
    }

    public synchronized void handlePlayerTick(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        long nowEpochMs
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        TransformComponent transformComponent = store.getComponent(ref, TransformComponent.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, Universe.get().getPlayerRefComponentType());
        if (player == null || transformComponent == null || playerRef == null || playerRef.getUuid() == null) {
            maybeLogTickExit(
                ref,
                store,
                nowEpochMs,
                "missing_components"
                    + " player=" + (player != null)
                    + " transform=" + (transformComponent != null)
                    + " playerRef=" + (playerRef != null)
            );
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            maybeLogTickExit(ref, store, nowEpochMs, "world_missing");
            forgetPlayer(playerRef.getUuid());
            return;
        }

        Optional<String> activeMatchId = minigameApi.findActiveMatchId(playerRef.getUuid());
        if (activeMatchId.isEmpty()) {
            maybeLogTickExit(ref, store, nowEpochMs, "active_match_missing world=" + world.getName());
            forgetPlayer(playerRef.getUuid());
            return;
        }

        String matchId = activeMatchId.get();
        String matchResolutionTriggerId = minigameApi.findMatchResolutionTriggerId(matchId).orElse("");
        if (!isManualResolutionTrigger(matchResolutionTriggerId)) {
            maybeLogTickExit(
                ref,
                store,
                nowEpochMs,
                "non_manual_trigger matchId=" + matchId + " trigger=" + matchResolutionTriggerId
            );
            forgetPlayer(playerRef.getUuid());
            return;
        }

        String expectedWorldName = MidCaptureConfig.buildInstanceWorldName(matchId);
        if (!world.getName().equalsIgnoreCase(expectedWorldName)) {
            maybeLogTickExit(
                ref,
                store,
                nowEpochMs,
                "world_mismatch matchId=" + matchId + " current=" + world.getName() + " expected=" + expectedWorldName
            );
            forgetPlayer(playerRef.getUuid());
            return;
        }

        MidCaptureMatchState matchState = matchesById.computeIfAbsent(matchId, ignored -> new MidCaptureMatchState(matchId, world.getName()));
        matchState.setWorldName(world.getName());
        MidCapturePlayerState playerState = ensureTrackedPlayer(matchState, playerRef);
        Transform playerRefTransform = playerRef.getTransform();
        TransformComponent playerEntityTransformComponent = player.getTransformComponent();
        Vector3d positionFromStoreComponent = transformComponent.getPosition() == null ? null : transformComponent.getPosition().clone();
        Vector3d positionFromPlayerEntity = playerEntityTransformComponent == null || playerEntityTransformComponent.getPosition() == null
            ? null
            : playerEntityTransformComponent.getPosition().clone();
        Vector3d positionFromPlayerRef = playerRefTransform == null || playerRefTransform.getPosition() == null
            ? null
            : playerRefTransform.getPosition().clone();
        Vector3d chosenLivePosition = positionFromPlayerEntity != null
            ? positionFromPlayerEntity
            : (positionFromStoreComponent != null ? positionFromStoreComponent : positionFromPlayerRef);
        playerState.setLivePosition(chosenLivePosition);
        playerState.setAlive(store.getComponent(ref, DeathComponent.getComponentType()) == null);
        maybeLogPositionSources(
            playerState,
            matchId,
            nowEpochMs,
            positionFromStoreComponent,
            positionFromPlayerEntity,
            positionFromPlayerRef,
            chosenLivePosition
        );

        syncPlacementState(matchState, playerState, player, transformComponent);

        if (!matchState.isResolved()) {
            handleRespawnIfNeeded(matchState, playerState, ref, store, playerRef, nowEpochMs);
            advanceMatch(matchState, store, commandBuffer, nowEpochMs);
        } else {
            maybeLogTickExit(ref, store, nowEpochMs, "match_resolved matchId=" + matchState.getMatchId());
        }
    }

    public synchronized void handlePlayerDisconnect(@Nonnull UUID playerUuid) {
        forgetPlayer(playerUuid);
    }

    @Nonnull
    public synchronized Optional<MidCaptureHudSnapshot> findHudSnapshot(@Nonnull UUID playerUuid, long nowEpochMs) {
        MidCaptureMatchState matchState = findMatchStateForPlayer(playerUuid).orElse(null);
        if (matchState == null || matchState.isResolved()) {
            return Optional.empty();
        }

        MidCapturePlayerState playerState = matchState.getPlayersByUuid().get(playerUuid);
        if (playerState == null) {
            return Optional.empty();
        }

        List<MidCaptureHudPlayerLine> playerLines = buildPlayerLines(matchState, playerUuid);
        String respawnPenaltyText = "Respawn: " + formatPenaltySeconds(playerState.getRespawnPenaltyMs());
        boolean rewardVisible = playerState.getRewardNoticeUntilEpochMs() > nowEpochMs && playerState.getLastRewardAmountMs() > 0L;
        String respawnRewardText = rewardVisible
            ? "Kill bonus: -" + (playerState.getLastRewardAmountMs() / 1000L) + "s"
            : "";
        String respawnRewardColor = rewardVisible
            ? buildRewardFadeColor(playerState.getRewardNoticeUntilEpochMs() - nowEpochMs)
            : "#9FF0A8";
        long respawnRemainingMs = playerState.getRespawnDueAtEpochMs() <= 0L
            ? 0L
            : Math.max(0L, playerState.getRespawnDueAtEpochMs() - nowEpochMs);

        if (!playerState.isAlive() && respawnRemainingMs > 0L) {
            return Optional.of(MidCaptureHudSnapshot.of(
                "MID CONTROL",
                "Respawn in " + formatRespawnSeconds(respawnRemainingMs),
                "#FF7C7C",
                playerLines,
                respawnPenaltyText,
                respawnRewardText,
                respawnRewardColor
            ));
        }

        if (!matchState.isPlacementComplete()) {
            return Optional.of(MidCaptureHudSnapshot.of(
                "MID CONTROL",
                "Placed " + matchState.getPlacedPlayers() + " / " + Math.max(matchState.getExpectedPlayers(), 1),
                "#82C7FF",
                playerLines,
                respawnPenaltyText,
                respawnRewardText,
                respawnRewardColor
            ));
        }

        return Optional.of(MidCaptureHudSnapshot.of(
            "MID CONTROL",
            buildZoneStatusText(matchState),
            resolveAccentColor(matchState),
            playerLines,
            respawnPenaltyText,
            respawnRewardText,
            respawnRewardColor
        ));
    }

    @Nonnull
    public synchronized Optional<DebugState> findDebugState(@Nonnull UUID playerUuid) {
        MidCaptureMatchState matchState = findMatchStateForPlayer(playerUuid).orElse(null);
        if (matchState == null) {
            return Optional.empty();
        }

        MidCapturePlayerState playerState = matchState.getPlayersByUuid().get(playerUuid);
        if (playerState == null) {
            return Optional.empty();
        }

        boolean insideZone = false;
        if (playerState.getLivePosition() != null) {
            insideZone = isInsideCaptureZone(playerState.getLivePosition());
        }

        return Optional.of(new DebugState(
            matchState.getMatchId(),
            matchState.getWorldName(),
            matchState.getExpectedPlayers(),
            matchState.getArrivedPlayers(),
            matchState.getPlacedPlayers(),
            matchState.isPlacementComplete(),
            playerState.isHomeRespawnConfigured(),
            playerState.getCaptureProgressSeconds(),
            insideZone,
            matchState.isResolved(),
            buildZoneStatusText(matchState)
        ));
    }

    public boolean isInsideCaptureZone(@Nonnull Vector3d position) {
        return Math.abs(position.getX() - MidCaptureConfig.CAPTURE_CENTER_X) <= MidCaptureConfig.CAPTURE_RADIUS_XZ
            && Math.abs(position.getZ() - MidCaptureConfig.CAPTURE_CENTER_Z) <= MidCaptureConfig.CAPTURE_RADIUS_XZ
            && position.getY() >= MidCaptureConfig.CAPTURE_MIN_Y
            && position.getY() <= MidCaptureConfig.CAPTURE_MAX_Y;
    }

    @Nonnull
    private Optional<MidCaptureMatchState> findMatchStateForPlayer(@Nonnull UUID playerUuid) {
        String matchId = matchIdByPlayerUuid.get(playerUuid);
        if (matchId == null || matchId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matchesById.get(matchId));
    }

    @Nonnull
    private MidCapturePlayerState ensureTrackedPlayer(
        @Nonnull MidCaptureMatchState matchState,
        @Nonnull PlayerRef playerRef
    ) {
        String previousMatchId = matchIdByPlayerUuid.put(playerRef.getUuid(), matchState.getMatchId());
        if (previousMatchId != null && !previousMatchId.equalsIgnoreCase(matchState.getMatchId())) {
            detachPlayerFromMatch(previousMatchId, playerRef.getUuid());
        }

        MidCapturePlayerState state = matchState.getPlayersByUuid().computeIfAbsent(
            playerRef.getUuid(),
            ignored -> new MidCapturePlayerState(playerRef.getUuid(), playerRef.getUsername())
        );
        state.setPlayerName(playerRef.getUsername());
        return state;
    }

    private void syncPlacementState(
        @Nonnull MidCaptureMatchState matchState,
        @Nonnull MidCapturePlayerState playerState,
        @Nonnull Player player,
        @Nonnull TransformComponent transformComponent
    ) {
        NexoriMatchPlacementState placementState = minigameApi.findMatchPlacementState(matchState.getMatchId()).orElse(null);
        if (placementState == null) {
            return;
        }

        matchState.updatePlacement(
            placementState.expectedPlayers(),
            placementState.arrivedPlayers(),
            placementState.placedPlayers(),
            placementState.placementComplete()
        );

        if (!placementState.placementComplete() || playerState.isHomeRespawnConfigured()) {
            return;
        }

        Transform homeSpawn = transformComponent.getTransform().clone();
        configureHomeRespawn(player, matchState.getWorldName(), homeSpawn);
        playerState.setHomeSpawn(homeSpawn);
        playerState.setHomeRespawnConfigured(true);
        logger.atInfo().log(
            "MID_CAPTURE_RESPAWN_SET player=" + playerState.getPlayerName()
                + " matchId=" + matchState.getMatchId()
                + " world=" + matchState.getWorldName()
                + " position=" + homeSpawn.getPosition()
        );
    }

    private void configureHomeRespawn(
        @Nonnull Player player,
        @Nonnull String worldName,
        @Nonnull Transform homeSpawn
    ) {
        Vector3d position = homeSpawn.getPosition().clone();
        Vector3i blockPosition = new Vector3i(
            (int) Math.floor(position.getX()),
            (int) Math.floor(position.getY()),
            (int) Math.floor(position.getZ())
        );
        PlayerRespawnPointData respawnPoint = new PlayerRespawnPointData(blockPosition, position, MidCaptureConfig.HOME_RESPAWN_NAME);
        player.getPlayerConfigData().getPerWorldData(worldName).setRespawnPoints(new PlayerRespawnPointData[] {respawnPoint});
        player.markNeedsSave();
    }

    private void handleRespawnIfNeeded(
        @Nonnull MidCaptureMatchState matchState,
        @Nonnull MidCapturePlayerState playerState,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull PlayerRef playerRef,
        long nowEpochMs
    ) {
        DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
        if (deathComponent == null) {
            playerState.setRespawnDueAtEpochMs(0L);
            return;
        }

        deathComponent.setShowDeathMenu(false);
        deathComponent.setDisplayDataOnDeathScreen(false);

        if (playerState.getRespawnDueAtEpochMs() <= 0L) {
            applyKillRespawnReward(matchState, playerState, deathComponent, store, nowEpochMs);
            long respawnDelayMs = Math.max(0L, playerState.getRespawnPenaltyMs());
            playerState.setRespawnDueAtEpochMs(nowEpochMs + respawnDelayMs);
            logger.atInfo().log(
                "MID_CAPTURE_RESPAWN_SCHEDULED player=" + playerState.getPlayerName()
                    + " delayMs=" + respawnDelayMs
                    + " currentPenaltyMs=" + playerState.getRespawnPenaltyMs()
            );
        }

        if (nowEpochMs < playerState.getRespawnDueAtEpochMs()) {
            return;
        }

        playerState.setLastRespawnAtEpochMs(nowEpochMs);
        playerState.setRespawnDueAtEpochMs(0L);
        try {
            DeathComponent.respawn(store, ref);
            playerState.setRespawnPenaltyMs(playerState.getRespawnPenaltyMs() + MidCaptureConfig.RESPAWN_DELAY_PER_DEATH_MS);
            logger.atInfo().log(
                "MID_CAPTURE_RESPAWN_PENALTY_INCREASED player=" + playerState.getPlayerName()
                    + " newPenaltyMs=" + playerState.getRespawnPenaltyMs()
                    + " addedMs=" + MidCaptureConfig.RESPAWN_DELAY_PER_DEATH_MS
            );
        } catch (Exception exception) {
            logger.atWarning().withCause(exception).log(
                "Failed to respawn mid-capture player " + playerRef.getUuid() + "."
            );
        }
    }

    private void applyKillRespawnReward(
        @Nonnull MidCaptureMatchState matchState,
        @Nonnull MidCapturePlayerState victimState,
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
        if (killerPlayerRef.getUuid().equals(victimState.getPlayerUuid())) {
            return;
        }

        MidCapturePlayerState killerState = matchState.getPlayersByUuid().get(killerPlayerRef.getUuid());
        if (killerState == null) {
            return;
        }

        long previousPenaltyMs = killerState.getRespawnPenaltyMs();
        long nextPenaltyMs = Math.max(0L, previousPenaltyMs - MidCaptureConfig.RESPAWN_KILL_REWARD_MS);
        long rewardAppliedMs = previousPenaltyMs - nextPenaltyMs;
        killerState.setRespawnPenaltyMs(nextPenaltyMs);
        if (rewardAppliedMs > 0L) {
            killerState.setLastRewardAmountMs(rewardAppliedMs);
            killerState.setRewardNoticeUntilEpochMs(nowEpochMs + MidCaptureConfig.RESPAWN_REWARD_HUD_MS);
        }

        logger.atInfo().log(
            "MID_CAPTURE_RESPAWN_REWARD killer=" + killerState.getPlayerName()
                + " killerPenaltyBeforeMs=" + previousPenaltyMs
                + " killerPenaltyAfterMs=" + nextPenaltyMs
                + " victim=" + victimState.getPlayerName()
                + " rewardMs=" + rewardAppliedMs
        );
    }

    private void advanceMatch(
        @Nonnull MidCaptureMatchState matchState,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        long nowEpochMs
    ) {
        long previousAdvanceAt = matchState.getLastAdvanceAtEpochMs();
        if (previousAdvanceAt > 0L && nowEpochMs - previousAdvanceAt < MidCaptureConfig.WORLD_ADVANCE_INTERVAL_MS) {
            return;
        }
        matchState.setLastAdvanceAtEpochMs(nowEpochMs);

        if (!matchState.isPlacementComplete()) {
            return;
        }

        double deltaSeconds = previousAdvanceAt <= 0L
            ? MidCaptureConfig.WORLD_ADVANCE_INTERVAL_MS / 1000.0D
            : Math.max(0.0D, (nowEpochMs - previousAdvanceAt) / 1000.0D);

        List<MidCapturePlayerState> insidePlayers = new ArrayList<>();
        for (MidCapturePlayerState playerState : matchState.getPlayersByUuid().values()) {
            ZonePresenceEvaluation evaluation = evaluatePlayerZonePresence(matchState, playerState);
            maybeLogZoneEvaluation(matchState, playerState, evaluation, nowEpochMs);
            if (evaluation.counted()) {
                insidePlayers.add(playerState);
            }
        }

        if (insidePlayers.size() == 1) {
            MidCapturePlayerState capturer = insidePlayers.get(0);
            matchState.setZoneState(MidCaptureZoneState.CAPTURING);
            matchState.setCapturingPlayerUuid(capturer.getPlayerUuid());
            for (MidCapturePlayerState playerState : matchState.getPlayersByUuid().values()) {
                if (playerState.getPlayerUuid().equals(capturer.getPlayerUuid())) {
                    playerState.setCaptureProgressSeconds(clampProgress(
                        playerState.getCaptureProgressSeconds() + deltaSeconds
                    ));
                }
            }
        } else if (insidePlayers.size() > 1) {
            matchState.setZoneState(MidCaptureZoneState.CONTESTED);
            matchState.setCapturingPlayerUuid(null);
        } else {
            matchState.setZoneState(MidCaptureZoneState.EMPTY);
            matchState.setCapturingPlayerUuid(null);
        }

        MidCapturePlayerState winner = matchState.getPlayersByUuid().values().stream()
            .max(Comparator.comparingDouble(MidCapturePlayerState::getCaptureProgressSeconds)
                .thenComparing(MidCapturePlayerState::getPlayerName))
            .orElse(null);
        if (winner != null && winner.getCaptureProgressSeconds() >= MidCaptureConfig.CAPTURE_SECONDS_TO_WIN) {
            resolveWinner(matchState, commandBuffer, winner);
        }
    }

    @Nonnull
    private ZonePresenceEvaluation evaluatePlayerZonePresence(
        @Nonnull MidCaptureMatchState matchState,
        @Nonnull MidCapturePlayerState playerState
    ) {
        PlayerRef playerRef = Universe.get().getPlayer(playerState.getPlayerUuid());
        if (playerRef == null || !playerRef.isValid() || playerRef.getReference() == null) {
            return new ZonePresenceEvaluation(false, false, "player_ref_missing", "<unknown>");
        }

        Vector3d position = playerState.getLivePosition();
        String positionText = position == null ? "<unknown>" : position.toString();
        boolean geometryInside = position != null && isInsideCaptureZone(position);

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        Store<EntityStore> playerStore = playerEntityRef.getStore();
        Player player = playerStore.getComponent(playerEntityRef, Player.getComponentType());
        TransformComponent liveTransformComponent = playerStore.getComponent(playerEntityRef, TransformComponent.getComponentType());
        Vector3d liveRefPosition = liveTransformComponent == null || liveTransformComponent.getPosition() == null
            ? null
            : liveTransformComponent.getPosition().clone();
        if (player == null || player.getWorld() == null) {
            return new ZonePresenceEvaluation(false, geometryInside, "player_world_missing", positionText);
        }

        if (!player.getWorld().getName().equalsIgnoreCase(matchState.getWorldName())) {
            return new ZonePresenceEvaluation(false, geometryInside, "wrong_world:" + player.getWorld().getName(), positionText);
        }

        if (liveTransformComponent != null && liveTransformComponent.getPosition() != null) {
            position = liveTransformComponent.getPosition().clone();
            positionText = position.toString();
            geometryInside = isInsideCaptureZone(position);
        }

        if (!playerState.isAlive()) {
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
        @Nonnull MidCaptureMatchState matchState,
        @Nonnull MidCapturePlayerState playerState,
        @Nonnull ZonePresenceEvaluation evaluation,
        long nowEpochMs
    ) {
        if (evaluation.counted()) {
            logZoneDebug(
                "counted-" + playerState.getPlayerUuid(),
                nowEpochMs,
                "MID_CAPTURE_ZONE player=" + playerState.getPlayerName()
                    + " matchId=" + matchState.getMatchId()
                    + " counted=true position=" + evaluation.positionText()
            );
            return;
        }

        logZoneDebug(
            "rejected-" + playerState.getPlayerUuid() + "-" + evaluation.reason(),
            nowEpochMs,
            "MID_CAPTURE_ZONE player=" + playerState.getPlayerName()
                + " matchId=" + matchState.getMatchId()
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
        @Nonnull MidCapturePlayerState playerState,
        @Nonnull String matchId,
        long nowEpochMs,
        Vector3d positionFromStoreComponent,
        Vector3d positionFromPlayerEntity,
        Vector3d positionFromPlayerRef,
        Vector3d chosenLivePosition
    ) {
        logZoneDebug(
            "position-sources-" + playerState.getPlayerUuid(),
            nowEpochMs,
            "MID_CAPTURE_POSITION_SOURCES player=" + playerState.getPlayerName()
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

    private void maybeLogTickExit(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        long nowEpochMs,
        @Nonnull String reason
    ) {
        PlayerRef tickPlayerRef = store.getComponent(ref, Universe.get().getPlayerRefComponentType());
        String playerKey = tickPlayerRef == null || tickPlayerRef.getUuid() == null
            ? "unknown"
            : tickPlayerRef.getUuid().toString();
        String username = tickPlayerRef == null ? "<unknown>" : tickPlayerRef.getUsername();
        String key = "tick-exit-" + playerKey + "-" + reason;
        logZoneDebug(
            key,
            nowEpochMs,
            "MID_CAPTURE_TICK_EXIT player=" + username + " reason=" + reason
        );
    }

    private void resolveWinner(
        @Nonnull MidCaptureMatchState matchState,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        @Nonnull MidCapturePlayerState winner
    ) {
        matchState.setResolved(true);
        for (MidCapturePlayerState playerState : matchState.getPlayersByUuid().values()) {
            NexoriPlayerResolutionOutcome outcome = playerState.getPlayerUuid().equals(winner.getPlayerUuid())
                ? NexoriPlayerResolutionOutcome.WIN
                : NexoriPlayerResolutionOutcome.LOSS;
            NexoriResolvePlayerResult result = minigameApi.resolvePlayerOutcome(
                matchState.getMatchId(),
                playerState.getPlayerUuid(),
                outcome,
                MidCaptureConfig.RETURN_DELAY_SECONDS,
                outcome == NexoriPlayerResolutionOutcome.WIN ? "mid_capture_win" : "mid_capture_loss"
            );
            if (result.outcome() != NexoriResolvePlayerOutcome.UPDATED) {
                logger.atWarning().log(
                    "Failed to resolve mid-capture player outcome matchId=" + matchState.getMatchId()
                        + " playerUuid=" + playerState.getPlayerUuid()
                        + " outcome=" + outcome
                        + " result=" + result.outcome()
                );
            }
        }
    }

    private void decayProgress(@Nonnull MidCapturePlayerState playerState, double deltaSeconds) {
        playerState.setCaptureProgressSeconds(clampProgress(
            playerState.getCaptureProgressSeconds() - (deltaSeconds * MidCaptureConfig.PROGRESS_DECAY_PER_SECOND)
        ));
    }

    private double clampProgress(double rawProgressSeconds) {
        return Math.max(0.0D, Math.min(MidCaptureConfig.CAPTURE_SECONDS_TO_WIN, rawProgressSeconds));
    }

    private boolean isManualResolutionTrigger(@Nonnull String rawTriggerId) {
        return rawTriggerId.isBlank() || "none".equalsIgnoreCase(rawTriggerId);
    }

    private void forgetPlayer(@Nonnull UUID playerUuid) {
        String matchId = matchIdByPlayerUuid.remove(playerUuid);
        if (matchId == null || matchId.isBlank()) {
            return;
        }
        detachPlayerFromMatch(matchId, playerUuid);
    }

    private void detachPlayerFromMatch(@Nonnull String matchId, @Nonnull UUID playerUuid) {
        MidCaptureMatchState matchState = matchesById.get(matchId);
        if (matchState == null) {
            return;
        }

        matchState.getPlayersByUuid().remove(playerUuid);

        if (matchState.getPlayersByUuid().isEmpty()) {
            matchesById.remove(matchId);
        }
    }

    @Nonnull
    private List<MidCaptureHudPlayerLine> buildPlayerLines(
        @Nonnull MidCaptureMatchState matchState,
        @Nonnull UUID viewerUuid
    ) {
        List<MidCapturePlayerState> orderedPlayers = new ArrayList<>(matchState.getPlayersByUuid().values());
        orderedPlayers.sort(
            Comparator.comparing((MidCapturePlayerState playerState) -> !playerState.getPlayerUuid().equals(viewerUuid))
                .thenComparing(Comparator.comparingDouble(MidCapturePlayerState::getCaptureProgressSeconds).reversed())
                .thenComparing(MidCapturePlayerState::getPlayerName, String.CASE_INSENSITIVE_ORDER)
        );

        List<MidCaptureHudPlayerLine> lines = new ArrayList<>(orderedPlayers.size());
        for (MidCapturePlayerState playerState : orderedPlayers) {
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
    private String buildZoneStatusText(@Nonnull MidCaptureMatchState matchState) {
        return switch (matchState.getZoneState()) {
            case PREPARING -> "Waiting for placements";
            case EMPTY -> "No one is holding the zone";
            case CONTESTED -> "Zone contested";
            case CAPTURING -> {
                MidCapturePlayerState capturer = matchState.getCapturingPlayerUuid() == null
                    ? null
                    : matchState.getPlayersByUuid().get(matchState.getCapturingPlayerUuid());
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
    private String resolveAccentColor(@Nonnull MidCaptureMatchState matchState) {
        return switch (matchState.getZoneState()) {
            case PREPARING, EMPTY -> "#82C7FF";
            case CAPTURING -> "#9FF0A8";
            case CONTESTED -> "#FFD36E";
        };
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

    public record DebugState(
        String matchId,
        String worldName,
        int expectedPlayers,
        int arrivedPlayers,
        int placedPlayers,
        boolean placementComplete,
        boolean homeRespawnConfigured,
        double captureProgressSeconds,
        boolean insideCaptureZone,
        boolean resolved,
        String zoneStatusText
    ) {
    }

    private record ZonePresenceEvaluation(
        boolean counted,
        boolean geometryInside,
        String reason,
        String positionText
    ) {
    }
}
