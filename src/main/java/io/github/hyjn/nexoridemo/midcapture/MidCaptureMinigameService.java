package io.github.hyjn.nexoridemo.midcapture;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriActiveMatchInfo;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMatchPlacementState;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriSetPlayerSpectatorResult;
import io.github.hyjn.nexoridemo.midcapture.events.MidCaptureMatchCreatedEvent;
import io.github.hyjn.nexoridemo.midcapture.events.MidCapturePlayerJoinedEvent;
import io.github.hyjn.nexoridemo.midcapture.events.MidCapturePlayerLeftEvent;
import io.github.hyjn.nexoridemo.midcapture.events.MidCaptureSessionClosedEvent;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MidCaptureMinigameService {

    private static final String DEMO_SPECTATOR_MODEL_ID = "Pigeon";

    private static final long TICK_EXIT_LOG_INTERVAL_MS = 3_000L;

    private final NexoriMinigameApi nexoriApi;
    private final MidCaptureRulesEngine rulesEngine;
    private final HytaleLogger logger;
    private final MidCaptureEventBus eventBus;
    private final Map<String, MidCaptureMatchRuntime> matchesById = new LinkedHashMap<>();
    private final Map<UUID, String> matchIdByPlayerUuid = new LinkedHashMap<>();
    private final Map<String, Long> tickExitLogAtEpochMsByKey = new LinkedHashMap<>();
    private final Map<UUID, PendingSpectatorApiRequest> pendingSpectatorApiRequestsByPlayerUuid = new LinkedHashMap<>();

    public MidCaptureMinigameService(@Nonnull NexoriMinigameApi nexoriApi, @Nonnull HytaleLogger logger) {
        this(nexoriApi, logger, new MidCaptureEventBus());
    }

    public MidCaptureMinigameService(
        @Nonnull NexoriMinigameApi nexoriApi,
        @Nonnull HytaleLogger logger,
        @Nonnull MidCaptureEventBus eventBus
    ) {
        this.nexoriApi = nexoriApi;
        this.logger = logger;
        this.eventBus = eventBus;
        this.rulesEngine = new MidCaptureRulesEngine(logger, eventBus);
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

        processPendingSpectatorApiRequest(playerRef);

        Optional<String> activeMatchId = nexoriApi.findActiveMatchId(playerRef.getUuid());
        if (activeMatchId.isEmpty()) {
            maybeLogTickExit(ref, store, nowEpochMs, "active_match_missing world=" + world.getName());
            forgetPlayer(playerRef.getUuid());
            return;
        }

        MidCaptureMatchRuntime match = findOrCreateMatchFromNexori(activeMatchId.get(), world.getName(), ref, store, nowEpochMs).orElse(null);
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
        MidCapturePlayerRuntime playerRuntime = addPlayerToSession(match, playerRef.getUuid(), playerRef.getUsername(), nowEpochMs);
        syncPlacementFromNexori(match);
        if (!match.isPlacementComplete()) {
            rulesEngine.onWaitingForPlacement(match);
            return;
        }

        rulesEngine.onPlayerTick(match, playerRuntime, player, ref, store, commandBuffer, nowEpochMs);
        rulesEngine.onGameTick(match, nowEpochMs);
    }

    public synchronized void handlePlayerDisconnect(@Nonnull UUID playerUuid) {
        pendingSpectatorApiRequestsByPlayerUuid.remove(playerUuid);
        forgetPlayer(playerUuid);
    }

    public synchronized void enqueueSpectatorApiRequest(
        @Nonnull UUID playerUuid,
        boolean spectator,
        @Nonnull String matchId
    ) {
        pendingSpectatorApiRequestsByPlayerUuid.put(
            playerUuid,
            new PendingSpectatorApiRequest(spectator, matchId.trim(), spectator ? DEMO_SPECTATOR_MODEL_ID : "")
        );
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
    private Optional<MidCaptureMatchRuntime> findOrCreateMatchFromNexori(
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

        MidCaptureSessionSpec sessionSpec = findNexoriSessionSpec(matchId).orElse(null);
        if (sessionSpec == null) {
            maybeLogTickExit(ref, store, nowEpochMs, "active_match_info_missing matchId=" + matchId);
            return Optional.empty();
        }

        MidCaptureMatchRuntime match = ensureMatchSession(sessionSpec, worldName, nowEpochMs);
        if (!match.isControlledByThisMod()) {
            maybeLogTickExit(
                ref,
                store,
                nowEpochMs,
                "ignored_match matchId=" + matchId
                    + " rulesEngineId=" + sessionSpec.rulesEngineId()
                    + " trigger=" + sessionSpec.matchResolutionTriggerId()
                    + " expectedRulesEngineId=" + rulesEngine.rulesEngineId()
            );
        }
        return Optional.of(match);
    }

    @Nonnull
    private Optional<MidCaptureSessionSpec> findNexoriSessionSpec(@Nonnull String matchId) {
        NexoriActiveMatchInfo activeMatchInfo = nexoriApi.findActiveMatchInfo(matchId).orElse(null);
        if (activeMatchInfo == null) {
            return Optional.empty();
        }
        return Optional.of(new MidCaptureSessionSpec(
            activeMatchInfo.matchId(),
            activeMatchInfo.queueId(),
            activeMatchInfo.arenaId(),
            activeMatchInfo.rulesEngineId(),
            activeMatchInfo.matchResolutionTriggerId(),
            activeMatchInfo.expectedPlayerUuids(),
            activeMatchInfo.requiredResultPlayerUuids()
        ));
    }

    @Nonnull
    private MidCaptureMatchRuntime ensureMatchSession(
        @Nonnull MidCaptureSessionSpec sessionSpec,
        @Nonnull String worldName,
        long nowEpochMs
    ) {
        MidCaptureMatchRuntime existing = matchesById.get(sessionSpec.matchId());
        if (existing != null) {
            existing.setWorldName(worldName);
            return existing;
        }

        boolean controlledByThisMod = rulesEngine.rulesEngineId().equals(sessionSpec.rulesEngineId())
            && isManualResolutionTrigger(sessionSpec.matchResolutionTriggerId());
        MidCaptureMatchRuntime match = new MidCaptureMatchRuntime(
            sessionSpec.matchId(),
            worldName,
            sessionSpec.queueId(),
            sessionSpec.arenaId(),
            sessionSpec.rulesEngineId(),
            sessionSpec.matchResolutionTriggerId(),
            sessionSpec.expectedPlayerUuids(),
            sessionSpec.requiredResultPlayerUuids(),
            controlledByThisMod
        );
        matchesById.put(match.getMatchId(), match);
        eventBus.publish(new MidCaptureMatchCreatedEvent(
            match.getMatchId(),
            controlledByThisMod ? "MATCH_ACCEPTED" : "MATCH_OBSERVED",
            nowEpochMs
        ));

        if (controlledByThisMod) {
            rulesEngine.onMatchAccepted(match);
        }
        return match;
    }

    @Nonnull
    private MidCapturePlayerRuntime addPlayerToSession(
        @Nonnull MidCaptureMatchRuntime match,
        @Nonnull UUID playerUuid,
        @Nonnull String playerName,
        long nowEpochMs
    ) {
        String previousMatchId = matchIdByPlayerUuid.put(playerUuid, match.getMatchId());
        if (previousMatchId != null && !previousMatchId.equalsIgnoreCase(match.getMatchId())) {
            detachPlayerFromMatch(previousMatchId, playerUuid);
        }

        boolean alreadyTracked = match.getPlayersByUuid().containsKey(playerUuid);
        MidCapturePlayerRuntime runtime = match.getPlayersByUuid().computeIfAbsent(
            playerUuid,
            ignored -> new MidCapturePlayerRuntime(playerUuid, playerName)
        );
        runtime.setPlayerName(playerName);
        if (!alreadyTracked) {
            eventBus.publish(new MidCapturePlayerJoinedEvent(
                match.getMatchId(),
                playerUuid,
                "PLAYER_TRACKED",
                nowEpochMs
            ));
        }
        return runtime;
    }

    private void syncPlacementFromNexori(@Nonnull MidCaptureMatchRuntime match) {
        if (match.isPlacementComplete()) {
            return;
        }

        NexoriMatchPlacementState placementState = nexoriApi.findMatchPlacementState(match.getMatchId()).orElse(null);
        if (placementState == null) {
            return;
        }

        updatePlayerPlacementState(
            match,
            placementState.expectedPlayers(),
            placementState.arrivedPlayers(),
            placementState.placedPlayers(),
            placementState.placementComplete()
        );
    }

    private void updatePlayerPlacementState(
        @Nonnull MidCaptureMatchRuntime match,
        int expectedPlayers,
        int arrivedPlayers,
        int placedPlayers,
        boolean placementComplete
    ) {
        match.updatePlacement(expectedPlayers, arrivedPlayers, placedPlayers, placementComplete);
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

        MidCapturePlayerRuntime removed = match.getPlayersByUuid().remove(playerUuid);
        if (removed == null) {
            return;
        }
        long nowEpochMs = System.currentTimeMillis();
        eventBus.publish(new MidCapturePlayerLeftEvent(
            match.getMatchId(),
            playerUuid,
            "PLAYER_DETACHED",
            nowEpochMs
        ));

        if (match.getPlayersByUuid().isEmpty()) {
            closeSessionIfEmpty(match, nowEpochMs);
        }
    }

    private void closeSessionIfEmpty(@Nonnull MidCaptureMatchRuntime match, long nowEpochMs) {
        if (!match.getPlayersByUuid().isEmpty()) {
            return;
        }
        matchesById.remove(match.getMatchId());
        eventBus.publish(new MidCaptureSessionClosedEvent(
            match.getMatchId(),
            "SESSION_EMPTY",
            nowEpochMs
        ));
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

    private void processPendingSpectatorApiRequest(@Nonnull PlayerRef playerRef) {
        PendingSpectatorApiRequest request = pendingSpectatorApiRequestsByPlayerUuid.remove(playerRef.getUuid());
        if (request == null) {
            return;
        }

        String matchId = request.matchId();
        if (matchId.isBlank()) {
            matchId = nexoriApi.findActiveMatchId(playerRef.getUuid()).orElse("");
        }
        if (matchId.isBlank()) {
            playerRef.sendMessage(Message.raw("No active Nexori match found. Pass matchId explicitly if needed."));
            return;
        }

        NexoriSetPlayerSpectatorResult result = nexoriApi.setPlayerSpectator(
            matchId,
            playerRef.getUuid(),
            request.spectator(),
            "nexori-public-api-demo spectator command",
            request.spectatorModelId()
        );
        String summary = "setPlayerSpectator matchId=" + result.matchId()
            + " playerUuid=" + result.playerUuid()
            + " spectator=" + result.spectator()
            + " status=" + result.status()
            + " message=" + result.message();
        logger.atInfo().log(summary);
        playerRef.sendMessage(Message.raw(summary));
    }

    private record PendingSpectatorApiRequest(boolean spectator, String matchId, String spectatorModelId) {
    }

    private record MidCaptureSessionSpec(
        String matchId,
        String queueId,
        String arenaId,
        String rulesEngineId,
        String matchResolutionTriggerId,
        List<UUID> expectedPlayerUuids,
        List<UUID> requiredResultPlayerUuids
    ) {

        private MidCaptureSessionSpec {
            expectedPlayerUuids = List.copyOf(expectedPlayerUuids);
            requiredResultPlayerUuids = List.copyOf(requiredResultPlayerUuids);
        }
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
