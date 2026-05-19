package io.github.hyjn.nexoridemo.nexori;

import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriListenerRegistration;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMatchCompletionStatus;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMatchLifecycleEvent;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMatchLifecycleListener;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMatchPlacementState;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMatchResultPlayerOutcome;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriMinigameApi;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriPlayerMatchLifecycleEvent;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriPlayerPlacementLifecycleEvent;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriReturnPlayerResult;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriReturnPlayerStatus;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriSetPlayerOutcomeResult;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriSetPlayerOutcomeStatus;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriSetPlayerSpectatorResult;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriSetPlayerSpectatorStatus;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriSubmitFinalMatchResultRequest;
import io.github.hyjn.nexori.plugin.api.minigame.NexoriSubmitFinalMatchResultResult;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureConfig;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureEventBus;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureListenerRegistration;
import io.github.hyjn.nexoridemo.midcapture.MidCaptureMinigameService;
import io.github.hyjn.nexoridemo.midcapture.events.MidCaptureMatchFinishedEvent;
import io.github.hyjn.nexoridemo.midcapture.events.MidCapturePlayerBecameSpectatorEvent;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CaptureTheZoneNexoriIntegration implements AutoCloseable {

    private final HytaleLogger logger;
    private final NexoriMinigameApi nexoriApi;
    private final MidCaptureMinigameService midCaptureService;
    private final MidCaptureEventBus eventBus;
    private final String rulesEngineId;
    private final List<MidCaptureListenerRegistration> midCaptureRegistrations = new ArrayList<>();
    private final Set<String> reportedMatchIds = new LinkedHashSet<>();
    private NexoriListenerRegistration nexoriLifecycleRegistration;
    private boolean started;
    private boolean closed;

    public CaptureTheZoneNexoriIntegration(
        @Nonnull HytaleLogger logger,
        @Nonnull NexoriMinigameApi nexoriApi,
        @Nonnull MidCaptureMinigameService midCaptureService,
        @Nonnull MidCaptureEventBus eventBus,
        @Nonnull String rulesEngineId
    ) {
        this.logger = logger;
        this.nexoriApi = nexoriApi;
        this.midCaptureService = midCaptureService;
        this.eventBus = eventBus;
        this.rulesEngineId = rulesEngineId;
    }

    public synchronized void start() {
        if (started || closed) {
            return;
        }
        started = true;
        nexoriLifecycleRegistration = nexoriApi.registerMatchLifecycleListener(rulesEngineId, new NexoriToMidCaptureListener());
        midCaptureRegistrations.add(eventBus.register(MidCaptureMatchFinishedEvent.class, this::handleMatchFinished));
        midCaptureRegistrations.add(eventBus.register(MidCapturePlayerBecameSpectatorEvent.class, this::handlePlayerBecameSpectator));
        logger.atInfo().log("Capture The Zone Nexori integration started rulesEngineId=" + rulesEngineId + ".");
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        if (nexoriLifecycleRegistration != null) {
            nexoriLifecycleRegistration.close();
            nexoriLifecycleRegistration = null;
        }
        for (MidCaptureListenerRegistration registration : List.copyOf(midCaptureRegistrations)) {
            registration.close();
        }
        midCaptureRegistrations.clear();
        logger.atInfo().log("Capture The Zone Nexori integration closed rulesEngineId=" + rulesEngineId + ".");
    }

    private void handleMatchFinished(@Nonnull MidCaptureMatchFinishedEvent event) {
        if (reportedMatchIds.contains(event.matchId())) {
            logger.atInfo().log("Skipping duplicate CTZ match finish report matchId=" + event.matchId() + ".");
            return;
        }
        List<UUID> participants = event.participantPlayerUuids();
        if (participants.isEmpty()) {
            logger.atWarning().log("Cannot report CTZ match finish without participants matchId=" + event.matchId() + ".");
            return;
        }
        reportedMatchIds.add(event.matchId());

        for (UUID playerUuid : participants) {
            boolean winner = playerUuid.equals(event.winnerPlayerUuid());
            NexoriSetPlayerOutcomeResult outcomeResult = nexoriApi.setPlayerOutcome(
                event.matchId(),
                playerUuid,
                winner ? NexoriMatchResultPlayerOutcome.WIN : NexoriMatchResultPlayerOutcome.LOSS,
                winner ? "mid_capture_win" : "mid_capture_loss"
            );
            if (outcomeResult.status() != NexoriSetPlayerOutcomeStatus.UPDATED
                && outcomeResult.status() != NexoriSetPlayerOutcomeStatus.MATCH_ALREADY_COMPLETED) {
                logger.atWarning().log(
                    "Failed to store CTZ player outcome in Nexori matchId=" + event.matchId()
                        + " playerUuid=" + playerUuid
                        + " status=" + outcomeResult.status()
                        + " message=" + outcomeResult.message()
                );
            }
        }

        NexoriSubmitFinalMatchResultResult result = nexoriApi.submitFinalMatchResult(new NexoriSubmitFinalMatchResultRequest(
            event.matchId(),
            event.reason(),
            buildFinalResultCustomData(event)
        ));
        if (result.matchStatus() != NexoriMatchCompletionStatus.ACCEPTED
            && result.matchStatus() != NexoriMatchCompletionStatus.ALREADY_SUBMITTED) {
            logger.atWarning().log(
                "Failed to submit CTZ final result to Nexori matchId=" + event.matchId()
                    + " matchStatus=" + result.matchStatus()
                    + " backendReportStatus=" + result.backendReportStatus()
                    + " message=" + result.message()
            );
            return;
        }

        for (UUID playerUuid : participants) {
            NexoriReturnPlayerResult returnResult = nexoriApi.returnPlayerToLobby(
                event.matchId(),
                playerUuid,
                MidCaptureConfig.RETURN_DELAY_SECONDS,
                "mid_capture_match_completed"
            );
            if (returnResult.status() != NexoriReturnPlayerStatus.SCHEDULED) {
                logger.atWarning().log(
                    "Failed to schedule CTZ player return in Nexori matchId=" + event.matchId()
                        + " playerUuid=" + playerUuid
                        + " status=" + returnResult.status()
                        + " message=" + returnResult.message()
                );
            }
        }
        logger.atInfo().log(
            "Reported CTZ match finish to Nexori matchId=" + event.matchId()
                + " winnerPlayerUuid=" + event.winnerPlayerUuid()
                + " matchStatus=" + result.matchStatus()
                + " backendReportStatus=" + result.backendReportStatus()
        );
    }

    private void handlePlayerBecameSpectator(@Nonnull MidCapturePlayerBecameSpectatorEvent event) {
        NexoriSetPlayerSpectatorResult result = nexoriApi.setPlayerSpectator(
            event.matchId(),
            event.playerUuid(),
            true,
            event.reason()
        );
        if (result.status() != NexoriSetPlayerSpectatorStatus.UPDATED
            && result.status() != NexoriSetPlayerSpectatorStatus.MATCH_ALREADY_COMPLETED) {
            logger.atWarning().log(
                "Failed to set CTZ player spectator in Nexori matchId=" + event.matchId()
                    + " playerUuid=" + event.playerUuid()
                    + " status=" + result.status()
                    + " message=" + result.message()
            );
        }
    }

    @Nonnull
    private JsonObject buildFinalResultCustomData(@Nonnull MidCaptureMatchFinishedEvent event) {
        JsonObject customData = new JsonObject();
        customData.addProperty("mode", "mid_capture");
        customData.addProperty("capturePointId", "mid");
        customData.addProperty("winnerPlayerUuid", event.winnerPlayerUuid().toString());
        customData.addProperty("participantCount", event.participantPlayerUuids().size());
        customData.addProperty("finishedAtEpochMs", event.finishedAtEpochMs());
        return customData;
    }

    @Nonnull
    private MidCaptureMinigameService.MidCaptureSessionSpec toSessionSpec(@Nonnull NexoriMatchLifecycleEvent event) {
        return new MidCaptureMinigameService.MidCaptureSessionSpec(
            event.matchId(),
            event.queueId(),
            event.arenaId(),
            event.rulesEngineId(),
            event.matchResolutionTriggerId(),
            event.expectedPlayerUuids(),
            event.requiredResultPlayerUuids()
        );
    }

    private void applyPlacement(@Nonnull NexoriMatchLifecycleEvent event) {
        NexoriMatchPlacementState placementState = event.placementState();
        if (placementState == null) {
            return;
        }
        midCaptureService.updatePlayerPlacementState(
            event.matchId(),
            placementState.expectedPlayers(),
            placementState.arrivedPlayers(),
            placementState.placedPlayers(),
            placementState.placementComplete()
        );
    }

    private String worldName(@Nonnull NexoriMatchLifecycleEvent event) {
        return MidCaptureConfig.buildInstanceWorldName(event.matchId());
    }

    private final class NexoriToMidCaptureListener implements NexoriMatchLifecycleListener {

        @Override
        public void onMatchCreated(@Nonnull NexoriMatchLifecycleEvent event) {
            midCaptureService.createOrUpdateSession(toSessionSpec(event), worldName(event), event.eventAtEpochMs());
            applyPlacement(event);
        }

        @Override
        public void onPlayerArrived(@Nonnull NexoriPlayerMatchLifecycleEvent event) {
            midCaptureService.createOrUpdateSession(toSessionSpec(event.match()), worldName(event.match()), event.eventAtEpochMs());
            midCaptureService.addPlayerToSession(
                event.match().matchId(),
                event.playerUuid(),
                event.playerName().isBlank() ? event.playerUuid().toString() : event.playerName(),
                event.eventAtEpochMs()
            );
            applyPlacement(event.match());
        }

        @Override
        public void onPlayerPlacementConfirmed(@Nonnull NexoriPlayerPlacementLifecycleEvent event) {
            applyPlacement(event.player().match());
        }

        @Override
        public void onMatchPlacementCompleted(@Nonnull NexoriMatchLifecycleEvent event) {
            midCaptureService.createOrUpdateSession(toSessionSpec(event), worldName(event), event.eventAtEpochMs());
            applyPlacement(event);
        }

        @Override
        public void onMatchCompleted(@Nonnull NexoriMatchLifecycleEvent event) {
            logger.atInfo().log("Observed Nexori match completed for CTZ matchId=" + event.matchId() + ".");
        }

        @Override
        public void onMatchRuntimeClosed(@Nonnull NexoriMatchLifecycleEvent event) {
            midCaptureService.closeSession(event.matchId(), "NEXORI_MATCH_RUNTIME_CLOSED", event.eventAtEpochMs());
        }
    }
}
