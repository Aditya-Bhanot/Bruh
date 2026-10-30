package releon.ru.api.module.impl.visual;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AntiBot;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.api.module.impl.visual.esp.EspColors;
import releon.ru.api.module.impl.visual.esp.EspGeometry;
import releon.ru.api.module.impl.visual.esp.EspHealthTracker;
import releon.ru.api.module.impl.visual.esp.EspTagRenderer;
import releon.ru.api.module.impl.visual.esp.EspVisualRenderer;
import releon.ru.api.module.impl.visual.esp.PlayerTagResolver;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.repository.friend.FriendUtils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ESP extends Module {
    private static final Comparator<RenderEntry> RENDER_DISTANCE_COMPARATOR = Comparator.comparingDouble(RenderEntry::distanceSqr).reversed();
    private static final Comparator<CompactItemRowAccumulator> COMPACT_ITEM_ROW_COMPARATOR = Comparator
            .comparing(CompactItemRowAccumulator::id)
            .thenComparing(CompactItemRowAccumulator::name);

    private final MultiModeSetting targets = register(new MultiModeSetting("Targets", "Entities rendered by ESP.",
            new String[]{"Players", "Items"}, "Players", "Items"));
    private final MultiModeSetting elements = register(new MultiModeSetting("Render", "ESP parts to draw.",
            new String[]{"Name", "Potions", "Equipment"}, "Name", "Potions", "Equipment"));
    private final MultiModeSetting tagElements = register(new MultiModeSetting("Tag Elements", "Parts shown inside player name tags.",
            new String[]{"Head", "Friend Prefix", "Donation", "Prefix", "Suffix", "Health"}, "Head", "Friend Prefix", "Donation", "Prefix", "Suffix", "Health"));
    private final ModeSetting itemMode = register(new ModeSetting("Items Mode", "Ground item ESP label style.", "Normal", "Normal", "Compact"));
    private final NumberSetting range = register(new NumberSetting("Range", "Maximum ESP render distance.", 128.0, 16.0, 256.0, 1.0));
    private final ColorSetting playerColor = register(new ColorSetting("Player Color", "Player ESP color.", new Color(127, 242, 255, 210)));
    private final ColorSetting friendColor = register(new ColorSetting("Friend Color", "Friend ESP color.", new Color(70, 255, 120, 220)));

    private static ESP instance;

    private final List<Entity> trackedEntities = new ArrayList<>();
    private final Set<Entity> seenEntities = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<RenderEntry> renderEntries = new ArrayList<>();
    private final List<RenderEntry> compactItemEntries = new ArrayList<>();
    private final List<CompactItemCluster> compactItemClusters = new ArrayList<>();
    private final EspHealthTracker healthTracker = new EspHealthTracker();
    private final PlayerTagResolver playerTagResolver = new PlayerTagResolver();
    private final EspTagRenderer tagRenderer = new EspTagRenderer(playerTagResolver, healthTracker);

    public ESP() {
        super("ESP", "Highlights players and items through walls.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static boolean shouldHideVanillaName(Entity entity) {
        return instance != null && instance.isEnabled() && instance.shouldHideVanillaNameInternal(entity);
    }

    public static boolean shouldSuppressServerTagEntity(Entity entity) {
        return instance != null && instance.isEnabled() && instance.shouldSuppressServerTagEntityInternal(entity);
    }

    public static void captureServerHealth(Entity entity, Component scoreText) {
        if (instance != null && instance.isEnabled()) {
            instance.healthTracker.capture(entity, scoreText);
        }
    }

    public static Float resolveHudHealth(LivingEntity entity) {
        return resolveHudHealth(entity, true);
    }

    public static Float resolveHudHealth(LivingEntity entity, boolean includeAbsorption) {
        return instance != null && instance.isEnabled() && entity != null
                ? instance.healthTracker.resolveDisplayHealth(entity, includeAbsorption)
                : null;
    }

    @Override
    public void onTick(Minecraft client) {
        healthTracker.prune();
        rebuildTargets(client);
    }

    @Override
    protected void onDisable() {
        trackedEntities.clear();
        healthTracker.clear();
    }

    @SubscribeEvent
    private void onWorldRender(WorldRenderEvent event) {
        if (!elements.isSelected("3D Box") || mc.player == null || mc.level == null) {
            return;
        }

        for (Entity entity : trackedEntities) {
            if (!isValidTarget(entity)) {
                continue;
            }

            AABB box = EspGeometry.interpolatedBox(entity, event.getPartialTicks()).inflate(0.02D);
            int color = colorFor(entity);
            EspVisualRenderer.draw3DCornerBox(box, color, EspVisualRenderer.resolve3DLineWidth(mc, box));
        }

    }

    @SubscribeEvent
    private void onDraw(DrawEvent event) {
        if (mc.player == null || mc.level == null || mc.getWindow() == null) {
            return;
        }

        EspGeometry.ProjectionContext context = EspGeometry.createProjectionContext(mc, event.getPartialTicks());
        if (context == null) {
            return;
        }

        boolean draw2DBox = elements.isSelected("2D Box");
        boolean drawName = elements.isSelected("Name");
        boolean drawHealth = tagElements.isSelected("Health");
        boolean drawPotions = elements.isSelected("Potions");
        boolean drawEquipment = elements.isSelected("Equipment");
        if (!draw2DBox && !drawName && !drawPotions && !drawEquipment) {
            return;
        }

        List<RenderEntry> entries = collectRenderEntries(context);
        if (entries.isEmpty()) {
            return;
        }

        if (entries.size() > 1) {
            entries.sort(RENDER_DISTANCE_COMPARATOR);
        }

        GuiGraphics graphics = event.getGraphics();
        if (drawEquipment || drawName) {
            RenderItem.beginFrame(graphics);
        }

        boolean compactItems = itemMode.is("Compact");
        compactItemEntries.clear();
        for (RenderEntry entry : entries) {
            Entity entity = entry.entity();
            EspGeometry.ScreenBox box = entry.screenBox();
            float alpha = alphaForDistance(entry.distanceSqr());

            if (draw2DBox) {
                EspVisualRenderer.draw2DBox(box, entry.color(), alpha);
            }
            if (drawEquipment && entity instanceof Player player) {
                tagRenderer.drawEquipment(player, box, entry.topAnchor(), alpha);
            }
            if (drawPotions && entity instanceof LivingEntity living) {
                tagRenderer.drawPotions(living, box, alpha);
            }
            if (compactItems && drawName && entity instanceof ItemEntity) {
                compactItemEntries.add(entry);
                continue;
            }
            if (drawName) {
                tagRenderer.drawTag(entity, box, entry.topAnchor(), drawHealth, alpha, tagElements, mc, graphics);
            }
        }

        if (!compactItemEntries.isEmpty()) {
            drawCompactItemTags(compactItemEntries, graphics, context);
        }

        if (drawEquipment || drawName) {
            RenderItem.flush();
        }
    }

    private void drawCompactItemTags(List<RenderEntry> itemEntries, GuiGraphics graphics, EspGeometry.ProjectionContext context) {
        compactItemClusters.clear();
        for (RenderEntry entry : itemEntries) {
            if (!(entry.entity() instanceof ItemEntity item)) {
                continue;
            }
            Vec3 position = item.getPosition(context.tickDelta());
            if (!EspGeometry.isFinite(position)) {
                continue;
            }

            CompactItemCluster cluster = null;
            for (CompactItemCluster candidate : compactItemClusters) {
                if (candidate.accepts(position)) {
                    cluster = candidate;
                    break;
                }
            }
            if (cluster == null) {
                cluster = new CompactItemCluster(position);
                compactItemClusters.add(cluster);
            }
            cluster.add(item, position, alphaForDistance(entry.distanceSqr()));
        }

        for (CompactItemCluster cluster : compactItemClusters) {
            Vec3 anchor = cluster.anchor();
            EspGeometry.ScreenPoint screen = EspGeometry.projectWorldToScreenSpace(anchor, context);
            if (screen == null || screen.z() < 0.0D || screen.z() > 1.0D) {
                continue;
            }
            double marginX = context.screenWidth() * 0.35D;
            double marginY = context.screenHeight() * 0.35D;
            if (screen.x() < -marginX || screen.x() > context.screenWidth() + marginX
                    || screen.y() < -marginY || screen.y() > context.screenHeight() + marginY) {
                continue;
            }
            EspVisualRenderer.drawCompactItemTag(cluster.rows(), screen.x(), screen.y(), cluster.alpha(), graphics);
        }
    }

    private List<RenderEntry> collectRenderEntries(EspGeometry.ProjectionContext context) {
        renderEntries.clear();
        for (Entity entity : trackedEntities) {
            if (!isValidTarget(entity)) {
                continue;
            }

            EspGeometry.ScreenBox screenBox = EspGeometry.projectEntityBox(entity, context);
            if (screenBox == null || !EspGeometry.isOnScreen(screenBox, context)) {
                continue;
            }

            EspGeometry.ScreenAnchor topAnchor = EspGeometry.resolveTopAnchor(entity, context, screenBox);
            Vec3 interpolated = entity.getPosition(context.tickDelta());
            renderEntries.add(new RenderEntry(entity, screenBox, topAnchor, context.cameraPos().distanceToSqr(interpolated), colorFor(entity)));
        }
        return renderEntries;
    }

    private void rebuildTargets(Minecraft client) {
        trackedEntities.clear();
        if (client == null || client.level == null || client.player == null) {
            return;
        }

        seenEntities.clear();
        if (targets.isSelected("Players")) {
            for (Player player : client.level.players()) {
                addIfValid(seenEntities, player);
            }
        }

        if (targets.isSelected("Items")) {
            for (Entity entity : client.level.entitiesForRendering()) {
                if (!(entity instanceof Player)) {
                    addIfValid(seenEntities, entity);
                }
            }
        }
    }

    private void addIfValid(Set<Entity> seen, Entity entity) {
        if (isValidTarget(entity) && seen.add(entity)) {
            trackedEntities.add(entity);
        }
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == null || entity.isRemoved() || mc.player == null || mc.level == null) {
            return false;
        }
        if (entity == mc.player && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
            return false;
        }
        if (range.getFloat() > 0.0F && entity != mc.player && mc.player.distanceToSqr(entity) > range.getFloat() * range.getFloat()) {
            return false;
        }
        if (!EspGeometry.isFinite(entity.position())) {
            return false;
        }

        if (entity instanceof Player player) {
            return targets.isSelected("Players") && !player.isSpectator() && !AntiBot.shouldIgnore(player);
        }
        if (entity instanceof ItemEntity item) {
            return targets.isSelected("Items") && !item.getItem().isEmpty();
        }
        return false;
    }

    private boolean shouldHideVanillaNameInternal(Entity entity) {
        if (!shouldHidePlayerLabels()) {
            return false;
        }
        if (entity instanceof Player player) {
            return isPlayerLabelTarget(player);
        }
        return isServerTagEntity(entity) && isPlayerServerTagEntity(entity);
    }

    private boolean shouldSuppressServerTagEntityInternal(Entity entity) {
        if (!shouldHidePlayerLabels() || !isServerTagEntity(entity)) {
            return false;
        }
        return isPlayerServerTagEntity(entity);
    }

    private boolean shouldHidePlayerLabels() {
        return elements.isSelected("Name") && targets.isSelected("Players") && mc.player != null && mc.level != null;
    }

    private boolean isPlayerLabelTarget(Player player) {
        if (player == null || player.isRemoved() || player.isSpectator() || AntiBot.shouldIgnore(player)) {
            return false;
        }
        if (player == mc.player && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
            return false;
        }
        if (range.getFloat() > 0.0F && player != mc.player && mc.player.distanceToSqr(player) > range.getFloat() * range.getFloat()) {
            return false;
        }
        return EspGeometry.isFinite(player.position());
    }

    private boolean isServerTagEntity(Entity entity) {
        if (entity == null || entity instanceof Player) {
            return false;
        }
        if (entity instanceof TextDisplay) {
            return true;
        }
        if (entity instanceof ArmorStand armorStand) {
            return armorStand.hasCustomName() || armorStand.shouldShowName() || armorStand.isMarker();
        }
        return entity.hasCustomName() && entity.shouldShowName();
    }

    private boolean isPlayerServerTagEntity(Entity tagEntity) {
        if (tagEntity == null || !EspGeometry.isFinite(tagEntity.position())) {
            return false;
        }

        String tagText = serverTagText(tagEntity);
        if (tagText.isBlank()) {
            return false;
        }

        for (Player player : mc.level.players()) {
            if (isPlayerLabelTarget(player) && isAttachedPlayerServerTag(tagEntity, player, tagText)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAttachedPlayerServerTag(Entity tagEntity, Player player, String tagText) {
        if (containsPlayerName(tagText, player)) {
            return isNearPlayerNameLabel(tagEntity, player);
        }
        return looksLikeHealthTag(tagText) && isNearPlayerHealthLabel(tagEntity, player);
    }

    private boolean isNearPlayerNameLabel(Entity tagEntity, Player player) {
        double dx = tagEntity.getX() - player.getX();
        double dz = tagEntity.getZ() - player.getZ();
        double horizontalLimit = 1.1D;
        if (dx * dx + dz * dz > horizontalLimit * horizontalLimit) {
            return false;
        }

        double aboveFeet = tagEntity.getY() - player.getY();
        return aboveFeet >= 1.2D && aboveFeet <= 4.4D;
    }

    private boolean isNearPlayerHealthLabel(Entity tagEntity, Player player) {
        double dx = tagEntity.getX() - player.getX();
        double dz = tagEntity.getZ() - player.getZ();
        double horizontalLimit = 0.95D;
        if (dx * dx + dz * dz > horizontalLimit * horizontalLimit) {
            return false;
        }

        double aboveFeet = tagEntity.getY() - player.getY();
        return aboveFeet >= 1.25D && aboveFeet <= 3.35D;
    }

    private String serverTagText(Entity entity) {
        Component text = null;
        if (entity instanceof TextDisplay textDisplay) {
            text = textDisplay.getText();
        }
        if (text == null) {
            text = entity.getCustomName();
        }
        return cleanServerTagText(text == null ? "" : text.getString());
    }

    private boolean containsPlayerName(String tagText, Player player) {
        String compactTag = compactSearchText(tagText);
        String scoreboardName = compactSearchText(player.getScoreboardName());
        String displayName = compactSearchText(player.getName().getString());
        return (!scoreboardName.isBlank() && compactTag.contains(scoreboardName))
                || (!displayName.isBlank() && compactTag.contains(displayName));
    }

    private boolean looksLikeHealthTag(String tagText) {
        String cleaned = cleanServerTagText(tagText).toLowerCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return false;
        }

        boolean hasHealthMarker = cleaned.contains("hp")
                || cleaned.contains("хп")
                || cleaned.indexOf('\u2764') >= 0
                || cleaned.indexOf('\u2665') >= 0;
        int digits = 0;
        int invalid = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (Character.isDigit(c)) {
                digits++;
            } else if (!Character.isWhitespace(c)
                    && c != '.'
                    && c != ','
                    && c != '/'
                    && c != '\u2764'
                    && c != '\u2665'
                    && c != '['
                    && c != ']'
                    && c != '('
                    && c != ')'
                    && c != 'h'
                    && c != 'p'
                    && c != '\u0445'
                    && c != '\u043F') {
                invalid++;
            }
        }
        return digits > 0 && invalid == 0 && (hasHealthMarker || cleaned.trim().length() <= 4);
    }

    private String cleanServerTagText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(text.length());
        boolean skipFormatting = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (skipFormatting) {
                skipFormatting = false;
                continue;
            }
            if (c == '\u00A7') {
                skipFormatting = true;
                continue;
            }
            if (!Character.isISOControl(c)) {
                builder.append(c);
            }
        }
        return builder.toString().trim();
    }

    private String compactSearchText(String text) {
        String cleaned = cleanServerTagText(text).toLowerCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private int colorFor(Entity entity) {
        if (entity instanceof Player player) {
            return FriendUtils.isFriend(player) ? friendColor.getValue().getRGB() : playerColor.getValue().getRGB();
        }
        return EspColors.ITEM;
    }

    private float alphaForDistance(double distanceSqr) {
        double distance = Math.sqrt(Math.max(0.0D, distanceSqr));
        return (float) Mth.clamp(1.0D - (distance - 96.0D) / 80.0D, 0.48D, 1.0D);
    }

    private record RenderEntry(
            Entity entity,
            EspGeometry.ScreenBox screenBox,
            EspGeometry.ScreenAnchor topAnchor,
            double distanceSqr,
            int color
    ) {
    }

    private static final class CompactItemCluster {
        private double worldX;
        private double worldY;
        private double worldZ;
        private double anchorY;
        private float alpha;
        private int entries;
        private final Map<CompactItemKey, CompactItemRowAccumulator> rows = new LinkedHashMap<>();
        private final List<CompactItemRowAccumulator> sortedRows = new ArrayList<>();
        private final List<EspVisualRenderer.ItemTagRow> renderRows = new ArrayList<>();

        private CompactItemCluster(Vec3 first) {
            this.worldX = first.x;
            this.worldY = first.y;
            this.worldZ = first.z;
            this.anchorY = first.y + 0.72D;
        }

        private boolean accepts(Vec3 position) {
            double dx = position.x - worldX;
            double dy = Math.abs(position.y - worldY);
            double dz = position.z - worldZ;
            return dx * dx + dz * dz <= 7.29D && dy <= 1.75D;
        }

        private void add(ItemEntity item, Vec3 position, float entryAlpha) {
            double nextWeight = entries + 1.0D;
            worldX = (worldX * entries + position.x) / nextWeight;
            worldY = (worldY * entries + position.y) / nextWeight;
            worldZ = (worldZ * entries + position.z) / nextWeight;
            anchorY = Math.max(anchorY, position.y + 0.72D);
            alpha = Math.max(alpha, entryAlpha);
            entries++;

            ItemStack stack = item.getItem();
            String name = cleanItemName(stack.getHoverName().getString());
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            CompactItemKey key = new CompactItemKey(id, name);
            rows.computeIfAbsent(key, ignored -> new CompactItemRowAccumulator(stack.copy(), id, name)).add(stack.getCount());
        }

        private List<EspVisualRenderer.ItemTagRow> rows() {
            sortedRows.clear();
            sortedRows.addAll(rows.values());
            if (sortedRows.size() > 1) {
                sortedRows.sort(COMPACT_ITEM_ROW_COMPARATOR);
            }

            renderRows.clear();
            for (CompactItemRowAccumulator row : sortedRows) {
                renderRows.add(new EspVisualRenderer.ItemTagRow(row.stack(), row.name(), row.count()));
            }
            return renderRows;
        }

        private Vec3 anchor() {
            return new Vec3(worldX, anchorY, worldZ);
        }

        private float alpha() {
            return alpha;
        }
    }

    private record CompactItemKey(String id, String name) {
    }

    private static final class CompactItemRowAccumulator {
        private final ItemStack stack;
        private final String id;
        private final String name;
        private int count;

        private CompactItemRowAccumulator(ItemStack stack, String id, String name) {
            this.stack = stack;
            this.id = id;
            this.name = name;
            this.stack.setCount(1);
        }

        private void add(int amount) {
            count = Math.max(1, Math.min(Integer.MAX_VALUE, count + Math.max(1, amount)));
        }

        private String id() {
            return id;
        }

        private ItemStack stack() {
            return stack;
        }

        private String name() {
            return name;
        }

        private int count() {
            return count;
        }
    }

    private static String cleanItemName(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(text.length());
        boolean skipFormatting = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (skipFormatting) {
                skipFormatting = false;
                continue;
            }
            if (c == '\u00A7') {
                skipFormatting = true;
                continue;
            }
            if (!Character.isISOControl(c)) {
                builder.append(c);
            }
        }
        return builder.toString().trim();
    }
}
