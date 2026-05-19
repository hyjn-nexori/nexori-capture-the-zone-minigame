package io.github.hyjn.nexoridemo.midcapture.events;

import javax.annotation.Nonnull;

public record MidCaptureMatchCancelledEvent(
    @Nonnull String matchId,
    @Nonnull String reason,
    long eventAtEpochMs
) {

    public MidCaptureMatchCancelledEvent {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("Match id cannot be blank.");
        }
        if (reason == null) {
            throw new IllegalArgumentException("Reason cannot be null.");
        }
    }
}
