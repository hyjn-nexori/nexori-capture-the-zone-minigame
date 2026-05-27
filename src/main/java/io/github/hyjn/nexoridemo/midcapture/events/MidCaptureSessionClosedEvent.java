package io.github.hyjn.nexoridemo.midcapture.events;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public record MidCaptureSessionClosedEvent(
    @Nonnull String matchId,
    @Nonnull String reason,
    @Nonnull List<UUID> playerUuids,
    long eventAtEpochMs
) {

    public MidCaptureSessionClosedEvent {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("Match id cannot be blank.");
        }
        if (reason == null) {
            throw new IllegalArgumentException("Reason cannot be null.");
        }
        playerUuids = playerUuids == null ? List.of() : List.copyOf(playerUuids);
    }
}
