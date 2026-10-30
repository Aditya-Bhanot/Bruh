package releon.ru.api.drag.impl;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.utils.repository.staff.StaffUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Staff extends HudPanel {
    private static final float ROW_STEP = 12.0F;
    private static final float PANEL_ANIM = 0.24F;
    private static final float ROW_ANIM = 0.22F;
    private static final Comparator<RowState> ROW_STATE_COMPARATOR = Comparator.comparingDouble(RowState::offset);

    private final List<RowEntry> rowEntries = new ArrayList<>();
    private final List<StaffEntry> cachedStaffEntries = new ArrayList<>();
    private final List<RowState> rowStates = new ArrayList<>();
    private final Map<String, PlayerInfo> tabPlayers = new HashMap<>();
    private final Map<String, Player> worldPlayers = new HashMap<>();
    private final SmoothAnimation panelAnimation = new SmoothAnimation();
    private final SmoothAnimation iconAlphaAnimation = new SmoothAnimation();
    private boolean iconAlphaForward = true;
    public Staff() {
        super("staff", "Staff", 140.0F, 70.0F, 96.0F, 23.0F);
    }

    @Override
    public void render() {
        StaffState state = logics();
        if (state == null) {
            return;
        }
        renderStaff(state);
    }

    private StaffState logics() {
        List<StaffEntry> entries = staffEntries();
        boolean preview = entries.isEmpty() && editPreview();
        boolean targetVisible = !entries.isEmpty() || preview;

        drag.hitExpansion(0.0F, 17.0F, 0.0F, 0.0F);
        panelAnimation.update();
        panelAnimation.run(targetVisible ? 1.0 : 0.0, PANEL_ANIM, targetVisible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);

        iconAlphaAnimation.update();
        if (!iconAlphaAnimation.isAlive()) {
            iconAlphaAnimation.run(iconAlphaForward ? 1.0 : 0.0, 1.55, Easings.EXPO_IN_OUT);
            iconAlphaForward = !iconAlphaForward;
        }

        for (RowEntry entry : rowEntries) {
            entry.active = false;
            entry.alpha.update();
            entry.y.update();
        }

        int targetRows = 0;
        if (preview) {
            RowEntry entry = row("__preview", new StaffEntry("Moderator", "Active", defaultSkin("Moderator"), ColorUtil.rgba(85, 255, 140, 235)), 0.0F);
            entry.active = true;
            entry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
            entry.y.run(0.0F, ROW_ANIM, Easings.EXPO_OUT, true);
            targetRows = 1;
        } else {
            for (StaffEntry staff : entries) {
                float targetY = targetRows * ROW_STEP;
                RowEntry entry = row(staff.name.toLowerCase(), staff, targetY);
                entry.active = true;
                entry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
                entry.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
                targetRows++;
            }
        }

        for (RowEntry entry : rowEntries) {
            if (!entry.active) {
                entry.alpha.run(0.0, ROW_ANIM, Easings.EXPO_IN, true);
            }
        }
        rowEntries.removeIf(entry -> !entry.active && entry.alpha.get() <= 0.01F && !entry.alpha.isAlive());

        float panelAlpha = panelAnimation.get();
        boolean visible = targetVisible || panelAlpha > 0.01F || !rowEntries.isEmpty();
        contentVisible(visible);
        if (!visible) {
            return null;
        }

        float width = 96.0F;
        for (RowEntry entry : rowEntries) {
            if (entry.alpha.get() > 0.01F || entry.active) {
                width = Math.max(width, Render2D.textWidth(TEXT_FONT, entry.name, 6.0F) + 46.0F);
            }
        }
        size(width, Math.max(1, targetRows) * 11.0F + 10.0F);

        rowStates.clear();
        for (RowEntry entry : rowEntries) {
            float alpha = entry.alpha.get();
            if (alpha > 0.01F || entry.active) {
                rowStates.add(new RowState(entry.name, entry.status, entry.skin, entry.dotColor, entry.y.get(), alpha));
            }
        }
        if (rowStates.size() > 1) {
            rowStates.sort(ROW_STATE_COMPARATOR);
        }

        return new StaffState(rowStates, iconAlphaAnimation.get(), panelAlpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderStaff(StaffState state) {
        float headerY = state.y - 17.0F;
        float headerHeight = 17.0F;
        float rowY = state.y + 5.0F;
        String headerText = "Staff List";
        float headerTextSize = 6.0F;
        float headerTextX = state.x + 10.0F;
        float headerTextY = headerY + (headerHeight - headerTextSize) * 0.5F - 0.5F;
        float headerTextWidth = Render2D.textWidth(TEXT_FONT, headerText, headerTextSize);
        float headerTextDotX = headerTextX + headerTextWidth + 3.0F;
        float iconSize = 8.0F;
        float iconWidth = Render2D.textWidth(FontType.MAINMENUSCREEN, "c", iconSize);
        float iconX = state.x + state.width - iconWidth - 8.0F;
        float iconY = headerY + (headerHeight - iconSize) * 0.5F + 1.0F;
        float glowSize = 22.0F;
        float glowX = iconX + iconWidth * 0.5F - glowSize * 0.5F;
        float glowY = headerY + (headerHeight - glowSize) * 0.5F;
        float sideRectY = headerY + (headerHeight - 9.0F) * 0.5F + 0.5F;
        float sideDotY = headerY + (headerHeight - 4.0F) * 0.5F + 0.5F;
        int glowAlpha = Math.round(128.0F * state.alpha);
        int glow = ColorUtil.rgba(247, 133, 255, glowAlpha);
        int purple = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * state.alpha));
        int headerTextColor = ColorUtil.rgba(255, 255, 255, Math.round(255.0F * state.alpha));
        int rowRectColor = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * state.alpha));

        HudRenderCompat.background(Render2D.blurBuilder()
                .rectangle(state.x, headerY, state.width, headerHeight)
                .radius(4.0F, 4.0F, 2.0F, 2.0F)
                .blurRadius(15.0F)
                .smoothness(1.2F)
                .color(ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)))
                .build());

        HudRenderCompat.background(Render2D.blurBuilder()
                .rectangle(state.x, state.y, state.width, state.height)
                .radius(2.0F, 2.0F, 4.0F, 4.0F)
                .blurRadius(15.0F)
                .smoothness(1.2F)
                .color(ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)))
                .build());

        if (glowAlpha > 0) {
            HudRenderCompat.glow("releon:textures/particles/ghost-glow.png", glowX - 13.0F, glowY + 0.5F, glowSize + 25.0F, glowSize, 0.0F, glow);
        }
        Render2D.rect(state.x, sideRectY, 2.5F, 9.0F, 0.0F, 2.0F, 2.0F, 0.0F, rowRectColor);
        Render2D.rect(state.x + 3.5F, sideDotY, 4.0F, 4.0F, 4.0F, rowRectColor);
        Render2D.rect(headerTextDotX, sideDotY, 4.0F, 4.0F, 4.0F, rowRectColor);
        Render2D.text(TEXT_FONT, headerText, headerTextX, headerTextY, headerTextSize, headerTextColor);
        Render2D.text(FontType.MAINMENUSCREEN, "c", iconX, iconY, iconSize, purple);

        for (RowState row : state.rows) {
            float rowAlpha = state.alpha * row.alpha;
            float currentY = rowY + row.offset;
            float headSize = 8F;
            float headX = state.x + 5.5F;
            float headY = currentY + 2F;
            float dotX = headX + headSize + 2.5F;
            float dotY = currentY + 4.5F;
            float nameX = dotX + 4.0F;
            float statusSize = 6F;
            float statusX = state.x + state.width - statusSize - 9F;
            float statusY = currentY + 3.0F;
            String visibleName = trimToWidth(row.name, TEXT_FONT, 6.0F, statusX - nameX - 6.0F);
            int leftDotColor = ColorUtil.rgba(128, 128, 128, Math.round(210.0F * rowAlpha));

            renderHead(row, headX, headY, headSize, rowAlpha);
            Render2D.rect(dotX, dotY, 3.2F, 3.2F, 3.2F, leftDotColor);
            Render2D.text(TEXT_FONT, visibleName, nameX, currentY + 1.5F, 6.0F, ColorUtil.multAlpha(TEXT_COLOR, rowAlpha));
            Render2D.rect(statusX, statusY, statusSize, statusSize, 2, ColorUtil.multAlpha(row.dotColor, rowAlpha));
        }
    }

    private void renderHead(RowState row, float x, float y, float size, float alpha) {
        int color = ColorUtil.rgba(255, 255, 255, Math.round(255.0F * alpha));
        String texture = row.skin == null || row.skin.isBlank() ? defaultSkin(row.name) : row.skin;
        boolean base = renderSkinPart(texture, x, y, size, 8.0F / 64.0F, 8.0F / 64.0F, 16.0F / 64.0F, 16.0F / 64.0F, color);
        boolean overlay = renderSkinPart(texture, x, y, size, 40.0F / 64.0F, 8.0F / 64.0F, 48.0F / 64.0F, 16.0F / 64.0F, color);
        if (base || overlay) {
            return;
        }

        Render2D.image(texture, x, y, size, 2.0F, color);
    }

    private boolean renderSkinPart(String texture, float x, float y, float size, float u0, float v0, float u1, float v1, int color) {
        if (texture == null || texture.isBlank() || color >>> 24 == 0) {
            return false;
        }
        Render2D.imageUvNearest(texture, x, y, size, size, 2.0F, 1.0F, u0, v0, u1, v1, color);
        return true;
    }

    private RowEntry row(String key, StaffEntry staff, float targetY) {
        for (RowEntry entry : rowEntries) {
            if (entry.key.equals(key)) {
                entry.name = staff.name;
                entry.status = staff.status;
                entry.skin = staff.skin;
                entry.dotColor = staff.dotColor;
                return entry;
            }
        }
        RowEntry entry = new RowEntry(key, staff);
        entry.alpha.set(0.0);
        entry.y.set(targetY + 4.0F);
        rowEntries.add(entry);
        return entry;
    }

    private List<StaffEntry> staffEntries() {
        cachedStaffEntries.clear();
        if (mc.level == null || mc.getConnection() == null) {
            return cachedStaffEntries;
        }

        tabPlayers.clear();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            tabPlayers.put(StaffUtils.normalizeName(info.getProfile().name()).toLowerCase(), info);
        }

        worldPlayers.clear();
        for (Player player : mc.level.players()) {
            worldPlayers.put(StaffUtils.normalizeName(player.getName().getString()).toLowerCase(), player);
        }

        for (String staff : StaffUtils.getStaffNames()) {
            String key = StaffUtils.normalizeName(staff).toLowerCase();
            if (key.isEmpty()) {
                continue;
            }

            Player player = worldPlayers.get(key);
            PlayerInfo info = tabPlayers.get(key);
            if (player != null) {
                cachedStaffEntries.add(new StaffEntry(staff, "Near", skin(player, info, staff), ColorUtil.rgba(255, 198, 76, 235)));
            } else if (info != null) {
                cachedStaffEntries.add(new StaffEntry(staff, "Active", skin(null, info, staff), ColorUtil.rgba(85, 255, 140, 235)));
            } else {
                cachedStaffEntries.add(new StaffEntry(staff, "Spec", defaultSkin(staff), ColorUtil.rgba(255, 92, 92, 235)));
            }
        }
        return cachedStaffEntries;
    }

    private String skin(Player player, PlayerInfo info, String name) {
        try {
            if (player instanceof AbstractClientPlayer clientPlayer) {
                return clientPlayer.getSkin().body().texturePath().toString();
            }
            if (info != null) {
                return info.getSkin().body().texturePath().toString();
            }
        } catch (RuntimeException ignored) {
        }
        return defaultSkin(name);
    }

    private String defaultSkin(String name) {
        return ((name == null ? 0 : name.hashCode()) & 1) == 0
                ? "minecraft:textures/entity/player/wide/steve.png"
                : "minecraft:textures/entity/player/slim/alex.png";
    }

    private static final class RowEntry {
        private final String key;
        private final SmoothAnimation alpha = new SmoothAnimation();
        private final SmoothAnimation y = new SmoothAnimation();
        private String name;
        private String status;
        private String skin;
        private int dotColor;
        private boolean active;

        private RowEntry(String key, StaffEntry staff) {
            this.key = key;
            this.name = staff.name;
            this.status = staff.status;
            this.skin = staff.skin;
            this.dotColor = staff.dotColor;
        }
    }

    private record StaffEntry(String name, String status, String skin, int dotColor) {
    }

    private record RowState(String name, String status, String skin, int dotColor, float offset, float alpha) {
    }

    private record StaffState(List<RowState> rows, float iconAlpha, float alpha, float x, float y, float width, float height) {
    }
}
