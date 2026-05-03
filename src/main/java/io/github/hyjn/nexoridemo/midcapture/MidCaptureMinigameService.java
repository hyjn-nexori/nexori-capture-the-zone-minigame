package io.github.hyjn.nexoridemo.midcapture;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriActiveMatchInfo;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMatchPlacementState;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MidCaptureMinigameService {

    private static final long TICK_EXIT_LOG_INTERVAL_MS = 3_000L;

    private final NexoriMinigameApi nexoriApi;
    private final MidCaptureRulesEngine rulesEngine;
    private final HytaleLogger logger;
    private final Map<String, MidCaptureMatchRuntime> matchesById = new LinkedHashMap<>();
    private final Map<UUID, String> matchIdByPlayerUuid = new LinkedHashMap<>();
    private final Map<String, Long> tickExitLogAtEpochMsByKey = new LinkedHashMap<>();

    public MidCaptureMinigameService(@Nonnull NexoriMinigameApi nexoriApi, @Nonnull HytaleLogger logger) {
        this.nexoriApi = nexoriApi;
        this.rulesEngine = new MidCaptureRulesEngine(logger);
        this.logger = logger;
    }

    public synchronized void handlePlayerTick(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        @Nonnull CommandBuffer<EntityStore> commandBuffer,
        long nowEpochMs
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, Universe.get().getPlayerRefComponentType());
        if (player == null || playerRef == null || playerRef.getUuid() == null) {
            maybeLogTickExit(
                ref,
                store,
                nowEpochMs,
                "missing_components player=" + (player != null) + " playerRef=" + (playerRef != null)
            );
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            maybeLogTickExit(ref, store, nowEpochMs, "world_missing");
            forgetPlayer(playerRef.getUuid());
            return;
        }

        Optional<String> activeMatchId = nexoriApi.findActiveMatchId(playerRef.getUuid());
        if (activeMatchId.isEmpty()) {
            maybeLogTickExit(ref, store, nowEpochMs, "active_match_missing world=" + world.getName());
            forgetPlayer(playerRef.getUuid());
            return;
        }

        MidCaptureMatchRuntime match = findOrCreateMatch(activeMatchId.get(), world.getName(), ref, store, nowEpochMs).orElse(null);
        if (match == null) {
            return;
        }
        matchIdByPlayerUuid.put(playerRef.getUuid(), match.getMatchId());
        if (!match.isControlledByThisMod() || match.isResolved()) {
            return;
        }

        String expectedWorldName = MidCaptureConfig.buildInstanceWorldName(match.getMatchId());
        if (!world.getName().equalsIgnoreCase(expectedWorldName)) {
            maybeLogTickExit(
                ref,
                store,
                nowEpochMs,
                "world_mismatch matchId=" + match.getMatchId() + " current=" + world.getName() + " expected=" + expectedWorldName
            );
            forgetPlayer(playerRef.getUuid());
            return;
        }

        match.setWorldName(world.getName());
        MidCapturePlayerRuntime playerRuntime = ensureTrackedPlayer(match, playerRef);
        syncPlacement(match);
        if (!match.isPlacementComplete()) {
            rulesEngine.onWaitingForPlacement(match);
            return;
        }

        rulesEngine.onPlayerTick(match, playerRuntime, nexoriApi, player, ref, store, commandBuffer, nowEpochMs);
        rulesEngine.onGameTick(match, nexoriApi, nowEpochMs);
    }

    public synchronized void handlePlayerDisconnect(@Nonnull UUID playerUuid) {
        forgetPlayer(playerUuid);
    }

    @Nonnull
    public synchronized Optional<MidCaptureHudSnapshot> findHudSnapshot(@Nonnull UUID playerUuid, long nowEpochMs) {
        MidCaptureMatchRuntime match = findMatchRuntimeForPlayer(playerUuid).orElse(null);
        if (match == null || match.isResolved()) {
            return Optional.empty();
        }

        MidCapturePlayerRuntime playerRuntime = match.getPlayersByUuid().get(playerUuid);
        if (playerRuntime == null) {
            return Optional.empty();
        }

        return Optional.of(rulesEngine.buildHudSnapshot(match, playerRuntime, playerUuid, nowEpochMs));
    }

    @Nonnull
    public synchronized Optional<DebugState> findDebugState(@Nonnull UUID playerUuid) {
        MidCaptureMatchRuntime match = findMatchRuntimeForPlayer(playerUuid).orElse(null);
        if (match == null) {
            return Optional.empty();
        }

        MidCapturePlayerRuntime playerRuntime = match.getPlayersByUuid().get(playerUuid);
        if (playerRuntime == null) {
            return Optional.empty();
        }

        boolean insideZone = false;
        if (playerRuntime.getLivePosition() != null) {
            insideZone = rulesEngine.isInsideCaptureZone(playerRuntime.getLivePosition());
        }

        return Optional.of(new DebugState(
            match.getMatchId(),
            match.getWorldName(),
            match.getExpectedPlayers(),
            match.getArrivedPlayers(),
            match.getPlacedPlayers(),
            match.isPlacementComplete(),
            playerRuntime.isHomeRespawnConfigured(),
            playerRuntime.getCaptureProgressSeconds(),
            insideZone,
            match.isResolved(),
            rulesEngine.buildZoneStatusText(match)
        ));
    }

    public boolean isInsideCaptureZone(@Nonnull Vector3d position) {
        return rulesEngine.isInsideCaptureZone(position);
    }

    @Nonnull
    private Optional<MidCaptureMatchRuntime> findMatchRuntimeForPlayer(@Nonnull UUID playerUuid) {
        String matchId = matchIdByPlayerUuid.get(playerUuid);
        if (matchId == null || matchId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matchesById.get(matchId));
    }

    @Nonnull
    private Optional<MidCaptureMatchRuntime> findOrCreateMatch(
        @Nonnull String matchId,
        @Nonnull String worldName,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        long nowEpochMs
    ) {
        MidCaptureMatchRuntime existing = matchesById.get(matchId);
        if (existing != null) {
            return Optional.of(existing);
        }

        NexoriActiveMatchInfo activeMatchInfo = nexoriApi.findActiveMatchInfo(matchId).orElse(null);
        if (activeMatchInfo == null) {
            maybeLogTickExit(ref, store, nowEpochMs, "active_match_info_missing matchId=" + matchId);
            return Optional.empty();
        }

        boolean controlledByThisMod = rulesEngine.rulesEngineId().equals(activeMatchInfo.rulesEngineId())
            && isManualResolutionTrigger(activeMatchInfo.matchResolutionTriggerId());
        MidCaptureMatchRuntime match = new MidCaptureMatchRuntime(
            activeMatchInfo.matchId(),
            worldName,
            activeMatchInfo.queueId(),
            activeMatchInfo.arenaId(),
            activeMatchInfo.rulesEngineId(),
            activeMatchInfo.matchResolutionTriggerId(),
            activeMatchInfo.expectedPlayerUuids(),
            activeMatchInfo.requiredResultPlayerUuids(),
            controlledByThisMod
        );
        matchesById.put(match.getMatchId(), match);

        if (controlledByThisMod) {
            rulesEngine.onMatchAccepted(match);
        } else {
            maybeLogTickExit(
                ref,
                store,
                nowEpochMs,
                "ignored_match matchId=" + matchId
                    + " rulesEngineId=" + activeMatchInfo.rulesEngineId()
                    + " trigger=" + activeMatchInfo.matchResolutionTriggerId()
                    + " expectedRulesEngineId=" + rulesEngine.rulesEngineId()
            );
        }
        return Optional.of(match);
    }

    @Nonnull
    private MidCapturePlayerRuntime ensureTrackedPlayer(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull PlayerRef playerRef
    ) {
        String previousMatchId = matchIdByPlayerUuid.put(playerRef.getUuid(), match.getMatchId());
        if (previousMatchId != null && !previousMatchId.equalsIgnoreCase(match.getMatchId())) {
            detachPlayerFromMatch(previousMatchId, playerRef.getUuid());
        }

        MidCapturePlayerRuntime runtime = match.getPlayersByUuid().computeIfAbsent(
            playerRef.getUuid(),
            ignored -> new MidCapturePlayerRuntime(playerRef.getUuid(), playerRef.getUsername())
        );
        runtime.setPlayerName(playerRef.getUsername());
        return runtime;
    }

    private void syncPlacement(@Nonnull MidCaptureMatchRuntime match) {
        if (match.isPlacementComplete()) {
            return;
        }

        NexoriMatchPlacementState placementState = nexoriApi.findMatchPlacementState(match.getMatchId()).orElse(null);
        if (placementState == null) {
            return;
        }

        match.updatePlacement(
            placementState.expectedPlayers(),
            placementState.arrivedPlayers(),
            placementState.placedPlayers(),
            placementState.placementComplete()
        );
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
        MidCaptureMatchRuntime match = matchesById.get(matchId);
        if (match == null) {
            return;
        }

        match.getPlayersByUuid().remove(playerUuid);

        if (match.getPlayersByUuid().isEmpty()) {
            matchesById.remove(matchId);
        }
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
        logTickExitDebug(
            key,
            nowEpochMs,
            "MID_CAPTURE_TICK_EXIT player=" + username + " reason=" + reason
        );
    }

    private void logTickExitDebug(@Nonnull String key, long nowEpochMs, @Nonnull String message) {
        Long previousLogAt = tickExitLogAtEpochMsByKey.get(key);
        if (previousLogAt != null && nowEpochMs - previousLogAt < TICK_EXIT_LOG_INTERVAL_MS) {
            return;
        }
        tickExitLogAtEpochMsByKey.put(key, nowEpochMs);
        logger.atInfo().log(message);
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
}
