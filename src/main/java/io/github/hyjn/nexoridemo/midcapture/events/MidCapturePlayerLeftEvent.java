package io.github.hyjn.nexoridemo.midcapture.events;

import javax.annotation.Nonnull;
import java.util.UUID;

public record MidCapturePlayerLeftEvent(
    @Nonnull String matchId,
    @Nonnull UUID playerUuid,
    @Nonnull String reason,
    long eventAtEpochMs
) {

    public MidCapturePlayerLeftEvent {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("Match id cannot be blank.");
        }
        if (playerUuid == null) {
            throw new IllegalArgumentException("Player UUID cannot be null.");
        }
        if (reason == null) {
            throw new IllegalArgumentException("Reason cannot be null.");
        }
    }
}
