package io.github.hyjn.nexoridemo.midcapture.events;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public record MidCaptureMatchFinishedEvent(
    @Nonnull String matchId,
    @Nonnull UUID winnerPlayerUuid,
    @Nonnull List<UUID> participantPlayerUuids,
    @Nonnull String reason,
    long finishedAtEpochMs
) {

    public MidCaptureMatchFinishedEvent {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("Match id cannot be blank.");
        }
        if (winnerPlayerUuid == null) {
            throw new IllegalArgumentException("Winner player UUID cannot be null.");
        }
        if (participantPlayerUuids == null) {
            throw new IllegalArgumentException("Participant player UUIDs cannot be null.");
        }
        participantPlayerUuids = List.copyOf(participantPlayerUuids);
        if (reason == null) {
            throw new IllegalArgumentException("Reason cannot be null.");
        }
    }
}
