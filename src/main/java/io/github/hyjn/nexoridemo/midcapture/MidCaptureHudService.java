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

    private final MidCaptureService midCaptureService;
    private final HytaleLogger logger;
    private final ConcurrentMap<UUID, HyUIHud> activeHuds = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, MidCaptureHudSnapshot> renderedStates = new ConcurrentHashMap<>();

    public MidCaptureHudService(
        @Nonnull MidCaptureService midCaptureService,
        @Nonnull HytaleLogger logger
    ) {
        this.midCaptureService = midCaptureService;
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

        MidCaptureHudSnapshot nextState = midCaptureService.findHudSnapshot(playerUuid, nowEpochMs).orElse(null);
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

        PanelBuilder root = PanelBuilder.panel()
            .withId("mid-capture-root")
            .withAnchor(new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setTop(135)
                .setHeight(450))
            .withHitTestVisible(false);

        LabelBuilder title = LabelBuilder.label()
            .withId("mid-capture-title")
            .withText(state.titleText())
            .withAnchor(new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setTop(0)
                .setHeight(42))
            .withHitTestVisible(false)
            .withStyle(new HyUIStyle()
                .setFontSize(34)
                .setRenderBold(true)
                .setTextColor(state.accentColor())
                .setOutlineColor("#000000")
                .setAlignment(Alignment.Center));

        LabelBuilder main = LabelBuilder.label()
            .withId("mid-capture-main")
            .withText(state.mainText())
            .withAnchor(new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setTop(48)
                .setHeight(46))
            .withHitTestVisible(false)
            .withStyle(new HyUIStyle()
                .setFontSize(42)
                .setRenderBold(true)
                .setTextColor(TITLE_TEXT_COLOR)
                .setOutlineColor("#000000")
                .setAlignment(Alignment.Center));

        LabelBuilder detail = LabelBuilder.label()
            .withId("mid-capture-detail")
            .withText(state.detailText())
            .withAnchor(new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setTop(100)
                .setHeight(28))
            .withHitTestVisible(false)
            .withStyle(new HyUIStyle()
                .setFontSize(24)
                .setTextColor(DETAIL_TEXT_COLOR)
                .setOutlineColor("#000000")
                .setAlignment(Alignment.Center));

        LabelBuilder status = LabelBuilder.label()
            .withId("mid-capture-status")
            .withText(state.statusText())
            .withAnchor(new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setTop(132)
                .setHeight(30))
            .withHitTestVisible(false)
            .withStyle(new HyUIStyle()
                .setFontSize(28)
                .setRenderBold(true)
                .setTextColor(state.accentColor())
                .setOutlineColor("#000000")
                .setAlignment(Alignment.Center));

        root.addChild(title);
        root.addChild(main);
        root.addChild(detail);
        root.addChild(status);

        int top = 186;
        for (int index = 0; index < state.scoreboardLines().size(); index++) {
            root.addChild(LabelBuilder.label()
                .withId("mid-capture-score-" + index)
                .withText(state.scoreboardLines().get(index))
                .withAnchor(new HyUIAnchor()
                    .setLeft(0)
                    .setRight(0)
                    .setTop(top + (index * 28))
                    .setHeight(24))
                .withHitTestVisible(false)
                .withStyle(new HyUIStyle()
                    .setFontSize(22)
                    .setTextColor(SCOREBOARD_TEXT_COLOR)
                    .setOutlineColor("#000000")
                    .setAlignment(Alignment.Center)));
        }

        hud.addElement(root);
        return hud;
    }
}
