package io.github.hyjn.nexoridemo.midcapture;

import au.ellie.hyui.builders.Alignment;
import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIHud;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PanelBuilder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MidCaptureHudService {

    private static final String TITLE_TEXT_COLOR = "#FFFFFF";
    private static final String DETAIL_TEXT_COLOR = "#D6E1EF";
    private static final String SCOREBOARD_TEXT_COLOR = "#EAF4FF";
    private static final String CENTER_TITLE_COLOR = "#F4D26B";
    private static final String EMPTY_STATUS_COLOR = "#F6F2E7";
    private static final String CAPTURING_STATUS_COLOR = "#A8F0B7";
    private static final String CONTESTED_STATUS_COLOR = "#FFD36E";
    private static final String PREPARING_STATUS_COLOR = "#E8F1FF";
    private final MidCaptureMinigameService midCaptureMinigameService;
    private final HytaleLogger logger;
    private final ConcurrentMap<UUID, HyUIHud> activeHuds = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, MidCaptureHudSnapshot> renderedStates = new ConcurrentHashMap<>();

    public MidCaptureHudService(
        @Nonnull MidCaptureMinigameService midCaptureMinigameService,
        @Nonnull HytaleLogger logger
    ) {
        this.midCaptureMinigameService = midCaptureMinigameService;
        this.logger = logger;
    }

    public void handlePlayerTick(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull Store<EntityStore> store,
        long nowEpochMs
    ) {
        PlayerRef playerRef = store.getComponent(ref, Universe.get().getPlayerRefComponentType());
        if (playerRef == null) {
            return;
        }
        refresh(playerRef, nowEpochMs);
    }

    public void remove(@Nonnull PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        renderedStates.remove(playerUuid);
        HyUIHud hud = activeHuds.remove(playerUuid);
        if (hud == null) {
            return;
        }

        try {
            hud.remove();
        } catch (Exception exception) {
            logger.atWarning().withCause(exception).log("Failed to remove mid-capture HUD for " + playerUuid + ".");
        }
    }

    private void refresh(@Nonnull PlayerRef playerRef, long nowEpochMs) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        MidCaptureHudSnapshot nextState = midCaptureMinigameService.findHudSnapshot(playerUuid, nowEpochMs).orElse(null);
        if (nextState == null) {
            remove(playerRef);
            return;
        }

        MidCaptureHudSnapshot previousState = renderedStates.get(playerUuid);
        if (nextState.equals(previousState)) {
            return;
        }

        HudBuilder builder = buildHud(playerRef, nextState);
        HyUIHud existing = activeHuds.get(playerUuid);

        try {
            if (existing == null) {
                activeHuds.put(playerUuid, builder.show(playerRef));
            } else {
                existing.update(builder);
            }
            renderedStates.put(playerUuid, nextState);
        } catch (Exception exception) {
            logger.atWarning().withCause(exception).log("Failed to update mid-capture HUD for " + playerUuid + ".");
        }
    }

    @Nonnull
    private HudBuilder buildHud(@Nonnull PlayerRef playerRef, @Nonnull MidCaptureHudSnapshot state) {
        HudBuilder hud = HudBuilder.hudForPlayer(playerRef);

        PanelBuilder centerRoot = PanelBuilder.panel()
            .withId("mid-capture-center-root")
            .withAnchor(new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setTop(86)
                .setHeight(120))
            .withHitTestVisible(false);

        LabelBuilder title = LabelBuilder.label()
            .withId("mid-capture-title")
            .withText(state.titleText())
            .withAnchor(new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setTop(0)
                .setHeight(40))
            .withHitTestVisible(false)
            .withStyle(new HyUIStyle()
                .setFontSize(32)
                .setRenderBold(true)
                .setTextColor(CENTER_TITLE_COLOR)
                .setOutlineColor("#000000")
                .setAlignment(Alignment.Center));

        LabelBuilder status = LabelBuilder.label()
            .withId("mid-capture-status")
            .withText(state.statusText())
            .withAnchor(new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setTop(48)
                .setHeight(44))
            .withHitTestVisible(false)
            .withStyle(new HyUIStyle()
                .setFontSize(30)
                .setRenderBold(true)
                .setTextColor(resolveStatusColor(state))
                .setOutlineColor("#000000")
                .setAlignment(Alignment.Center));

        centerRoot.addChild(title);
        centerRoot.addChild(status);

        PanelBuilder sidebarRoot = PanelBuilder.panel()
            .withId("mid-capture-sidebar-root")
            .withAnchor(new HyUIAnchor()
                .setLeft(36)
                .setTop(120)
                .setWidth(390)
                .setHeight(Math.max(92, 24 + (state.playerLines().size() * 48))))
            .withHitTestVisible(false);

        LabelBuilder sidebarTitle = LabelBuilder.label()
            .withId("mid-capture-sidebar-title")
            .withText("Capture Progress")
            .withAnchor(new HyUIAnchor()
                .setLeft(16)
                .setRight(16)
                .setTop(8)
                .setHeight(22))
            .withHitTestVisible(false)
            .withStyle(new HyUIStyle()
                .setFontSize(18)
                .setRenderBold(true)
                .setTextColor("#F4E8BE")
                .setOutlineColor("#000000")
                .setAlignment(Alignment.Start));
        sidebarRoot.addChild(sidebarTitle);

        int top = 34;
        for (int index = 0; index < state.playerLines().size(); index++) {
            MidCaptureHudPlayerLine line = state.playerLines().get(index);
            int rowHeight = line.self() ? 28 : 24;

            sidebarRoot.addChild(LabelBuilder.label()
                .withId("mid-capture-player-line-" + index)
                .withText((index + 1) + ". " + line.playerName() + " " + line.progressText())
                .withAnchor(new HyUIAnchor()
                    .setLeft(10)
                    .setRight(10)
                    .setTop(top)
                    .setHeight(rowHeight))
                .withHitTestVisible(false)
                .withStyle(new HyUIStyle()
                    .setFontSize(line.self() ? 20 : 16)
                    .setRenderBold(true)
                    .setTextColor(line.self() ? "#F4D26B" : "#F6F2E7")
                    .setOutlineColor("#000000")
                    .setAlignment(Alignment.Start)
                    .setWrap(true)));
            top += rowHeight + 4;
        }

        PanelBuilder respawnRoot = PanelBuilder.panel()
            .withId("mid-capture-respawn-root")
            .withAnchor(new HyUIAnchor()
                .setRight(36)
                .setTop(120)
                .setWidth(260)
                .setHeight(84))
            .withHitTestVisible(false);

        respawnRoot.addChild(LabelBuilder.label()
            .withId("mid-capture-respawn-value")
            .withText(state.respawnPenaltyText())
            .withAnchor(new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setTop(0)
                .setHeight(48))
            .withHitTestVisible(false)
            .withStyle(new HyUIStyle()
                .setFontSize(28)
                .setRenderBold(true)
                .setTextColor("#F4D26B")
                .setOutlineColor("#000000")
                .setAlignment(Alignment.End)));

        if (!state.respawnRewardText().isBlank()) {
            respawnRoot.addChild(LabelBuilder.label()
                .withId("mid-capture-respawn-reward")
                .withText(state.respawnRewardText())
                .withAnchor(new HyUIAnchor()
                    .setLeft(0)
                    .setRight(0)
                    .setTop(50)
                    .setHeight(28))
                .withHitTestVisible(false)
                .withStyle(new HyUIStyle()
                    .setFontSize(24)
                    .setRenderBold(true)
                    .setTextColor(state.respawnRewardColor())
                    .setOutlineColor("#000000")
                    .setAlignment(Alignment.End)));
        }

        hud.addElement(centerRoot);
        hud.addElement(sidebarRoot);
        hud.addElement(respawnRoot);
        return hud;
    }

    @Nonnull
    private String resolveStatusColor(@Nonnull MidCaptureHudSnapshot state) {
        return switch (state.accentColor()) {
            case "#9FF0A8" -> CAPTURING_STATUS_COLOR;
            case "#FFD36E" -> CONTESTED_STATUS_COLOR;
            case "#82C7FF" -> PREPARING_STATUS_COLOR;
            case "#FF7C7C" -> "#FF7C7C";
            default -> EMPTY_STATUS_COLOR;
        };
    }
}
