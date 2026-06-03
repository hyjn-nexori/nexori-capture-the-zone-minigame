package io.github.hyjn.nexoridemo.midcapture;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the Capture The Zone capture area visible during a live match by periodically (re)emitting a
 * pair of cyan ring {@link ParticleSystem}s — a lower ring on the floor that delimits the zone, and an
 * upper ring that marks the zone's height.
 *
 * <p>This is the first real, gameplay-driven use of the {@code Ctz_Zone_Ring_Test} prototype validated
 * with {@code /ctzzonetest}. It mirrors that command's emit path (broadcast to the world's players via
 * {@link ParticleUtil}). Because Hytale has no "remove particle" packet, the marker is kept alive by
 * re-emitting just before the system's {@code LifeSpan} elapses; to stop, we simply stop re-emitting and
 * the particles expire on their own. State (the re-emit throttle) lives on {@link MidCaptureMatchRuntime}.</p>
 *
 * <p>Colour is a fixed cyan ({@code #00FFFF}) for now; per-state colours (neutral/capturing/contested)
 * are a later iteration. This service does not change zone detection, progress rules, the leader marker,
 * the HUD, placement, or Nexori.</p>
 */
final class MidCaptureZoneMarkerService {

    /** The ring ParticleSystem (demo asset) validated via /ctzzonetest. */
    private static final String SYSTEM_ID = "Ctz_Zone_Ring_Test";

    /** Visual scale, matching the calibrated /ctzzonetest value. */
    private static final float SCALE = 0.8f;

    /** Lower ring: just above the zone floor (delimits the area on the ground). */
    private static final double LOWER_Y_OFFSET = 1.1;

    /** Upper ring: marks the top of the zone's height band. */
    private static final double UPPER_Y_OFFSET = 14.0;

    /** Fixed cyan #00FFFF tint (per-state colours come later). */
    private static final Color CYAN = new Color((byte) 0x00, (byte) 0xFF, (byte) 0xFF);

    /**
     * Re-emit cadence. The system's asset {@code LifeSpan} is ~9s, so re-emitting every ~7.5s keeps the
     * rings continuously visible with a small overlap and without visible flicker.
     */
    private static final long EMIT_INTERVAL_MS = 7_500L;

    private final HytaleLogger logger;
    private boolean warnedMissingAsset;

    MidCaptureZoneMarkerService(@Nonnull HytaleLogger logger) {
        this.logger = logger;
    }

    /**
     * Re-emits the two zone rings if the match is live and the throttle interval has elapsed. Runs on the
     * world tick thread (called from the gameplay tick), so it emits particles directly. No-op while the
     * match has not started or is already resolved — that is how emission naturally stops at match end.
     */
    void reconcile(@Nonnull MidCaptureMatchRuntime match, @Nonnull World world, @Nonnull Store<EntityStore> store, long nowEpochMs) {
        if (!match.isStartAllowed() || match.isResolved()) {
            return;
        }
        if (nowEpochMs - match.getLastZoneMarkerEmitAtEpochMs() < EMIT_INTERVAL_MS) {
            return;
        }

        ParticleSystem system = ParticleSystem.getAssetMap().getAsset(SYSTEM_ID);
        if (system == null) {
            if (!warnedMissingAsset) {
                warnedMissingAsset = true;
                logger.atWarning().log("CTZ_ZONE_MARKER asset_missing systemId=" + SYSTEM_ID
                    + " matchId=" + match.getMatchId() + " (zone rings disabled).");
            }
            return;
        }

        match.setLastZoneMarkerEmitAtEpochMs(nowEpochMs);

        // Broadcast to everyone currently in the match world; a backfill sees it on the next re-emit.
        List<Ref<EntityStore>> viewers = new ArrayList<>();
        for (PlayerRef pr : world.getPlayerRefs()) {
            if (pr == null || !pr.isValid()) {
                continue;
            }
            Ref<EntityStore> r = pr.getReference();
            if (r != null && r.isValid()) {
                viewers.add(r);
            }
        }
        if (viewers.isEmpty()) {
            return;
        }

        emitRing(store, viewers, LOWER_Y_OFFSET);
        emitRing(store, viewers, UPPER_Y_OFFSET);

        logger.atFine().log("CTZ_ZONE_MARKER emitted matchId=" + match.getMatchId()
            + " world=" + world.getName() + " viewers=" + viewers.size());
    }

    private void emitRing(@Nonnull Store<EntityStore> store, @Nonnull List<Ref<EntityStore>> viewers, double yOffset) {
        Vector3d pos = new Vector3d(
            MidCaptureConfig.CAPTURE_CENTER_X,
            MidCaptureConfig.CAPTURE_MIN_Y + yOffset,
            MidCaptureConfig.CAPTURE_CENTER_Z
        );
        // (id, pos, yaw, pitch, roll, scale, color, viewers, accessor)
        ParticleUtil.spawnParticleEffect(SYSTEM_ID, pos, 0.0f, 0.0f, 0.0f, SCALE, CYAN, viewers, store);
    }
}
