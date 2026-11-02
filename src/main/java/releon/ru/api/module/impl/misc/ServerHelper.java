package releon.ru.api.module.impl.misc;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.drag.impl.Notifications;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.utils.inventory.InventoryTask;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.inventory.movement.MovementController;
import releon.ru.utils.inventory.script.Script;
import releon.ru.mixin.accessor.ItemCooldownInstanceAccessor;
import releon.ru.mixin.accessor.ItemCooldownsAccessor;
import releon.ru.utils.math.MathUtils;
import releon.ru.utils.network.Network;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.repository.friend.FriendUtils;
import releon.ru.utils.sounds.SoundManager;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ServerHelper extends Module {
    private static final long PRE_USE_STOP_DELAY_MS = 0L;
    private static final int FRIEND_COLOR = ColorUtil.rgba(85, 255, 85, 255);
    private static final int FRIEND_FILL_COLOR = ColorUtil.rgba(85, 255, 85, 55);

    private final ModeSetting mode = register(new ModeSetting("Тип сервера", "Позволяет выбрать тип сервера.", "FunTime",
            "ReallyWorld", "HolyWorld", "FunTime"));
    private final ModeSetting swapMode = register(new ModeSetting("Режим свапа", "Способ свапа предметов.", "Legit",
            "Instant", "Legit"));

    private final BooleanSetting autoLootSetting = register(new BooleanSetting("Авто лут", "Кража лута с ботов на ивенте.", true));
    private final BooleanSetting autoShulkerSetting = register(new BooleanSetting("Авто шалкер", "Автоматически кладет лут в шалкер.", true));
    private final BooleanSetting autoRepairSetting = register(new BooleanSetting("Авто ремонт", "Авто ремонтирует броню пузырем опыта.", true));
    private final BooleanSetting consumablesSetting = register(new BooleanSetting("Таймер расходников", "Отображает время до окончания расходников.", true));
    private final BindSetting funtimeAhSearchBind = register(new BindSetting("Поиск на аукционе", "Клавиша для /ah search по предмету в руке.", KeyBind.NONE));
    private final BooleanSetting autoPointSetting = register(new BooleanSetting("Авто поинт", "Отображает информацию об ивенте.", true));
    private final ColorSetting boxFillColor = register(new ColorSetting("Цвет заливки", "Цвет заливки бокса.", new Color(130, 32, 16, 40)));
    private final ColorSetting boxLineColor = register(new ColorSetting("Цвет линий", "Цвет линий бокса.", new Color(130, 32, 16, 255)));

    private final List<KeyBindingEntry> keyBindings = new ArrayList<>();
    private final Map<String, ItemInfo> itemConfig = new HashMap<>();
    private final Map<String, Boolean> lastKeyStates = new HashMap<>();
    private final ArrayDeque<String> itemQueue = new ArrayDeque<>();
    private final Map<Item, Integer> pendingLootItems = new HashMap<>();
    private final Map<BlockPos, BlockState> blockStateMap = new HashMap<>();
    private final List<Structure> structures = new ArrayList<>();
    private final List<ServerEventData> serverEvents = new ArrayList<>();

    private final StopWatch itemTimer = new StopWatch();
    private final StopWatch ahSearchTimer = new StopWatch();
    private final StopWatch itemsWatch = new StopWatch();
    private final StopWatch shulkerWatch = new StopWatch();
    private final StopWatch repairWatch = new StopWatch();
    private final Script script = new Script();
    private final Script shulkerScript = new Script();
    private final MovementController movement = new MovementController();

    private ActionState actionState = ActionState.IDLE;
    private int originalSlot = -1;
    private int originalSourceSlot = -1;
    private Slot pendingInventorySlot;
    private int pendingHotbarSlot = -1;
    private long actionTimer;
    private long stopMovementUntil;
    private boolean keysOverridden;
    private boolean wasForwardPressed;
    private boolean wasBackPressed;
    private boolean wasLeftPressed;
    private boolean wasRightPressed;
    private boolean wasJumpPressed;
    private boolean ahSearchBindWasPressed;
    private UUID entityUUID;

    public ServerHelper() {
        super("Server Helper", "Помощник для серверов.", ModuleCategory.MISC);

        autoLootSetting.visibleWhen(() -> mode.is("HolyWorld"));
        autoShulkerSetting.visibleWhen(() -> mode.is("HolyWorld"));
        autoRepairSetting.visibleWhen(() -> mode.is("HolyWorld"));
        consumablesSetting.visibleWhen(() -> mode.is("FunTime"));
        funtimeAhSearchBind.visibleWhen(() -> mode.is("FunTime"));
        autoPointSetting.visibleWhen(() -> mode.is("FunTime"));
        boxFillColor.visibleWhen(() -> mode.is("FunTime"));
        boxLineColor.visibleWhen(() -> mode.is("FunTime"));

        addBind("antiflight", Items.FIREWORK_STAR, "Анти полет", "Клавиша анти полета.", () -> mode.is("ReallyWorld"), 0.0F);
        addBind("expscroll", Items.FLOWER_BANNER_PATTERN, "Свиток опыта", "Клавиша свитка опыта.", () -> mode.is("ReallyWorld"), 0.0F);
        addBind("dtrap", Items.PRISMARINE_SHARD, "Взрывная трапка", "Клавиша взрывной трапки.", () -> mode.is("HolyWorld"), 5.0F);
        addBind("trap_holy", Items.POPPED_CHORUS_FRUIT, "Обычная трапка", "Клавиша обычной трапки.", () -> mode.is("HolyWorld"), 0.0F);
        addBind("stan", Items.NETHER_STAR, "Стан", "Клавиша стана.", () -> mode.is("HolyWorld"), 30.0F);
        addBind("ditem", Items.FIRE_CHARGE, "Взрывная штучка", "Клавиша взрывной штучки.", () -> mode.is("HolyWorld"), 5.0F);
        addBind("snow", Items.SNOWBALL, "Снежок заморозка", "Клавиша снежка.", () -> mode.is("HolyWorld") || mode.is("FunTime"), 7.0F);
        addBind("bojaura", Items.PHANTOM_MEMBRANE, "Божья аура", "Клавиша божьей ауры.", () -> mode.is("FunTime"), 2.0F);
        addBind("trap", Items.NETHERITE_SCRAP, "Трапка", "Клавиша трапки.", () -> mode.is("FunTime"), 0.0F);
        addBind("plast", Items.DRIED_KELP, "Пласт", "Клавиша пласта.", () -> mode.is("FunTime"), 0.0F);
        addBind("sugar", Items.SUGAR, "Явная пыль", "Клавиша явной пыли.", () -> mode.is("FunTime"), 10.0F);
        addBind("fireSwirl", Items.FIRE_CHARGE, "Огненный смерч", "Клавиша огненного смерча.", () -> mode.is("FunTime"), 10.0F);
        addBind("disorientation", Items.ENDER_EYE, "Дезориентация", "Клавиша дезориентации.", () -> mode.is("FunTime"), 10.0F);
        addBind("windcharge", Items.WIND_CHARGE, "Заряд ветра", "Клавиша заряда ветра.", () -> mode.is("FunTime"), 10.0F);
        addBind("tikva", Items.JACK_O_LANTERN, "Светильник Джека", "Клавиша светильника Джека.", () -> mode.is("HolyWorld"), 0.0F);
        addBind("exp", Items.EXPERIENCE_BOTTLE, "Пузырь опыта", "Клавиша пузыря опыта.", () -> mode.is("HolyWorld"), 0.0F);
        addBind("shulker1", Items.PINK_SHULKER_BOX, "Рюкзак 1 уровня", "Клавиша рюкзака 1 уровня.", () -> mode.is("HolyWorld"), 0.0F);
        addBind("shulker2", Items.BLUE_SHULKER_BOX, "Рюкзак 2 уровня", "Клавиша рюкзака 2 уровня.", () -> mode.is("HolyWorld"), 0.0F);
        addBind("shulker3", Items.RED_SHULKER_BOX, "Рюкзак 3 уровня", "Клавиша рюкзака 3 уровня.", () -> mode.is("HolyWorld"), 0.0F);
        addBind("shulker4", Items.PINK_SHULKER_BOX, "Рюкзак 4 уровня", "Клавиша рюкзака 4 уровня.", () -> mode.is("HolyWorld"), 0.0F);
        addBind("hlopushka", Items.SPLASH_POTION, "Хлопушка", "Клавиша хлопушки.", () -> mode.is("FunTime"), 0.0F);
        addBind("holywater", Items.SPLASH_POTION, "Святая вода", "Клавиша святой воды.", () -> mode.is("FunTime"), 0.0F);
        addBind("gnev", Items.SPLASH_POTION, "Зелье Гнева", "Клавиша зелья гнева.", () -> mode.is("FunTime"), 0.0F);
        addBind("paladin", Items.SPLASH_POTION, "Зелье Палладина", "Клавиша зелья палладина.", () -> mode.is("FunTime"), 0.0F);
        addBind("assassin", Items.SPLASH_POTION, "Зелье Ассасина", "Клавиша зелья ассасина.", () -> mode.is("FunTime"), 0.0F);
        addBind("radiation", Items.SPLASH_POTION, "Зелье Радиации", "Клавиша зелья радиации.", () -> mode.is("FunTime"), 0.0F);
        addBind("snotvornoe", Items.SPLASH_POTION, "Снотворное", "Клавиша снотворного.", () -> mode.is("FunTime"), 0.0F);

        addLoreItem("sugar", Items.SUGAR, "Явная пыль", "явная пыль",
                List.of("световая вспышка", "радиус: 10 блоков", "свечение", "слепота"));
        addLoreItem("disorientation", Items.ENDER_EYE, "Дезориентация", "дезориентация",
                List.of("чем ближе цель, тем дольше длительность эффектов"));
        addLoreItem("trap", Items.NETHERITE_SCRAP, "Трапка", "трапка",
                List.of("нерушимая клетка", "длительность: 15 секунд"));
        addLoreItem("plast", Items.DRIED_KELP, "Пласт", "пласт",
                List.of("нерушимая стена", "вертикальный:", "горизонтальный:"));
        addLoreItem("fireSwirl", Items.FIRE_CHARGE, "Огненный смерч", "огненный смерч",
                List.of("огненная волна", "радиус: 10 блоков", "поджог"));
        addSimpleItem("windcharge", Items.WIND_CHARGE, "Заряд ветра", "заряд ветра");
        addLoreItem("snow", Items.SNOWBALL, "Снежок заморозка", "снежок заморозка",
                List.of("ледяная сфера", "радиус: 7 блоков", "заморозка", "слабость"));
        addLoreItem("bojaura", Items.PHANTOM_MEMBRANE, "Божья аура", "божья аура",
                List.of("божественная аура", "радиус: 2 блока", "снятие всех эффектов", "невидимость"));

        addPotionItem("hlopushka", "Хлопушка", "хлопушка",
                new EffectRequirement(MobEffects.SLOWNESS, 9),
                new EffectRequirement(MobEffects.SPEED, 4),
                new EffectRequirement(MobEffects.BLINDNESS, 9),
                new EffectRequirement(MobEffects.GLOWING, 0));
        addPotionItem("holywater", "Святая вода", "святая вода",
                new EffectRequirement(MobEffects.REGENERATION, 2),
                new EffectRequirement(MobEffects.INVISIBILITY, 1),
                new EffectRequirement(MobEffects.INSTANT_HEALTH, 1));
        addPotionItem("gnev", "Зелье Гнева", "зелье гнева",
                new EffectRequirement(MobEffects.STRENGTH, 4),
                new EffectRequirement(MobEffects.SLOWNESS, 3));
        addPotionItem("paladin", "Зелье Палладина", "зелье палладина",
                new EffectRequirement(MobEffects.RESISTANCE, 0),
                new EffectRequirement(MobEffects.FIRE_RESISTANCE, 0),
                new EffectRequirement(MobEffects.INVISIBILITY, 0),
                new EffectRequirement(MobEffects.HEALTH_BOOST, 2));
        addPotionItem("assassin", "Зелье Ассасина", "зелье ассасина",
                new EffectRequirement(MobEffects.STRENGTH, 3),
                new EffectRequirement(MobEffects.SPEED, 2),
                new EffectRequirement(MobEffects.HASTE, 0),
                new EffectRequirement(MobEffects.INSTANT_DAMAGE, 1));
        addPotionItem("radiation", "Зелье Радиации", "зелье радиации",
                new EffectRequirement(MobEffects.POISON, 1),
                new EffectRequirement(MobEffects.WITHER, 1),
                new EffectRequirement(MobEffects.SLOWNESS, 2),
                new EffectRequirement(MobEffects.HUNGER, 4),
                new EffectRequirement(MobEffects.GLOWING, 0));
        addPotionItem("snotvornoe", "Снотворное", "снотворное",
                new EffectRequirement(MobEffects.WEAKNESS, 1),
                new EffectRequirement(MobEffects.MINING_FATIGUE, 1),
                new EffectRequirement(MobEffects.WITHER, 2),
                new EffectRequirement(MobEffects.BLINDNESS, 0));

        addSimpleItem("antiflight", Items.FIREWORK_STAR, "Анти полет", "анти полет");
        addSimpleItem("expscroll", Items.FLOWER_BANNER_PATTERN, "Свиток опыта", "свиток опыта");
        addSimpleItem("dtrap", Items.PRISMARINE_SHARD, "Взрывная трапка", "взрывная трапка");
        addSimpleItem("trap_holy", Items.POPPED_CHORUS_FRUIT, "Обычная трапка", "трапка");
        addSimpleItem("stan", Items.NETHER_STAR, "Стан", "стан");
        addSimpleItem("ditem", Items.FIRE_CHARGE, "Взрывная штучка", "взрывная");
        addSimpleItem("tikva", Items.JACK_O_LANTERN, "Светильник Джека", "светильник джека");
        addSimpleItem("exp", Items.EXPERIENCE_BOTTLE, "Пузырь опыта", "пузырь опыта");
        addSimpleItem("shulker1", Items.PINK_SHULKER_BOX, "Рюкзак 1 уровня", "рюкзак (i уровень)");
        addSimpleItem("shulker2", Items.BLUE_SHULKER_BOX, "Рюкзак 2 уровня", "рюкзак (ii уровень)");
        addSimpleItem("shulker3", Items.RED_SHULKER_BOX, "Рюкзак 3 уровня", "рюкзак (iii уровень)");
        addSimpleItem("shulker4", Items.PINK_SHULKER_BOX, "Рюкзак 4 уровня", "рюкзак (iv уровень)");
    }

    public List<HudBind> getHudBinds(float tickDelta) {
        if (mc.player == null || mc.level == null) {
            return List.of();
        }

        List<HudBind> binds = new ArrayList<>();
        for (KeyBindingEntry bind : keyBindings) {
            if (!bind.setting.isVisible() || !bind.setting.getValue().isBound()) {
                continue;
            }

            ItemInfo info = itemConfig.get(bind.key);
            Slot slot = findSlotByBinding(bind);
            ItemStack stack = slot == null ? bind.item.getDefaultInstance() : slot.getItem().copy();
            int count = countMatchingBinding(bind, info);
            float cooldown = stack.isEmpty() || !mc.player.getCooldowns().isOnCooldown(stack)
                    ? 0.0F
                    : mc.player.getCooldowns().getCooldownPercent(stack, tickDelta);
            String displayName = info == null ? stack.getHoverName().getString() : info.displayName;
            binds.add(new HudBind(bind.key, bind.item, bind.setting, stack, count, cooldown, displayName));
        }
        return binds;
    }

    public List<HudEvent> getHudEvents() {
        if (mc.player == null || mc.level == null) {
            return List.of();
        }

        List<HudEvent> events = new ArrayList<>();
        for (ServerEventData event : serverEvents) {
            if (event.anarchy != Network.getAnarchyMode() || !matchesWorld(event.world)) {
                continue;
            }

            double open = (event.timeOpen - System.currentTimeMillis()) / 1000.0D;
            double end = (event.timeEnd - System.currentTimeMillis()) / 1000.0D;
            String timer = open > 0.0D ? "Start " + formatSeconds(open) : end > 0.0D ? "End " + formatSeconds(end) : "Done";
            double distance = Math.sqrt(mc.player.distanceToSqr(event.vec.x, event.vec.y, event.vec.z));
            events.add(new HudEvent(event.name, event.lvl, event.owner, timer, distance));
        }
        return events;
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        if (keysOverridden) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
        }
        resetState();
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive() || mc.player == null || mc.level == null) {
            return;
        }

        if (event.getPacket() instanceof ClientboundTakeItemEntityPacket packet
                && autoShulkerSetting.isVisible() && autoShulkerSetting.getValue()
                && packet.getPlayerId() == mc.player.getId()) {
            Entity entity = mc.level.getEntity(packet.getItemId());
            if (entity instanceof ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getItem();
                if (stack.get(DataComponents.CONTAINER) == null) {
                    pendingLootItems.merge(stack.getItem(), Math.max(1, stack.getCount()), Integer::sum);
                    shulkerWatch.reset();
                }
            }
        }

        if (event.getPacket() instanceof ClientboundSectionBlocksUpdatePacket packet
                && consumablesSetting.isVisible() && consumablesSetting.getValue()) {
            packet.runUpdates((pos, state) -> blockStateMap.put(pos.immutable(), state));
            if (blockStateMap.size() > 50 && blockStateMap.size() < 600) {
                packet.runUpdates((pos, state) -> {
                    Vec3 vec = Vec3.atCenterOf(pos);
                    if (isTrap(pos.above(2))) {
                        addStructure(Items.NETHERITE_SCRAP, vec, System.currentTimeMillis() + 15_000L);
                    } else if (isBigTrap(pos.above(3))) {
                        addStructure(Items.NETHERITE_SCRAP, vec, System.currentTimeMillis() + 30_000L);
                    }
                });
            }
        }

        if (event.getPacket() instanceof ClientboundSystemChatPacket packet) {
            if (autoPointSetting.isVisible() && autoPointSetting.getValue()) {
                processServerPoint(packet.content());
            }
            processExperienceCooldown(packet.content().getString());
        }

        if (event.getPacket() instanceof ClientboundOpenScreenPacket packet && packet.getTitle().getString().contains("Рюкзак")) {
            shulkerWatch.reset();
        }
    }

    @SubscribeEvent
    private void onRotationUpdate(RotationUpdateEvent event) {
        if (!event.isPre() || mc.player == null || mc.level == null) {
            return;
        }
        if (mc.screen != null) {
            clearPressedBindState();
            return;
        }

        long now = System.currentTimeMillis();

        if (now < stopMovementUntil || (keysOverridden && actionState != ActionState.IDLE)) {
            blockMovement();
        }

        processKeyBindings();
        processFuntimeAhSearchCombo();

        if (actionState != ActionState.IDLE) {
            processItemAction();
        }

        processItemQueue();
        processServerAutomation();
    }

    @SubscribeEvent
    private void onWorldRender(WorldRenderEvent event) {
        if (mc.player == null || mc.level == null || mc.getWindow() == null || mc.screen != null) {
            return;
        }

        long handle = mc.getWindow().handle();
        int lineColor = mode.is("FunTime") ? boxLineColor.getValue().getRGB() : 0xFF822010;
        int fillColor = mode.is("FunTime") ? boxFillColor.getValue().getRGB() : 0x28822010;
        BlockPos playerPos = mc.player.blockPosition();
        Vec3 smooth = MathUtils.interpolate(new Vec3(mc.player.xo, mc.player.yo, mc.player.zo), mc.player.position())
                .subtract(Vec3.atLowerCornerOf(playerPos));

        for (KeyBindingEntry bind : keyBindings) {
            if (!bind.setting.isVisible() || !bind.setting.getValue().isDown(handle) || findSlotByBinding(bind) == null) {
                continue;
            }

            switch (bind.setting.getName()) {
                case "Трапка", "Обычная трапка" -> drawItemCube(playerPos, smooth, 1.99F, lineColor, fillColor);
                case "Дезориентация", "Огненный смерч", "Явная пыль" ->
                        Render3D.drawRadiusCircle(MathUtils.interpolate(mc.player), bind.distance, validDistance(bind.distance) ? FRIEND_COLOR : lineColor);
                case "Взрывная штучка" ->
                        Render3D.drawRadiusCircle(MathUtils.interpolate(mc.player), 5.0F, validDistance(5.0F) ? FRIEND_COLOR : lineColor);
                case "Пласт" -> Render3D.drawPlastShape(playerPos, smooth, lineColor, fillColor);
                case "Взрывная трапка" -> drawItemCube(playerPos, smooth, 3.99F, lineColor, fillColor);
                case "Стан" -> drawItemCube(playerPos, smooth, 15.01F, lineColor, fillColor);
                case "Снежок заморозка" ->
                        Render3D.drawRadiusCircle(MathUtils.interpolate(mc.player), 7.0F, validDistance(7.0F) ? FRIEND_COLOR : lineColor);
                case "Божья аура" ->
                        Render3D.drawRadiusCircle(MathUtils.interpolate(mc.player), 2.0F, validDistance(2.0F) ? FRIEND_COLOR : lineColor);
                default -> {
                }
            }
        }
    }

    @SubscribeEvent
    private void onDraw(DrawEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        GuiGraphics graphics = event.getGraphics();

        for (Structure structure : structures) {
            if (structure.anarchy != Network.getAnarchyMode() || !matchesWorld(structure.world)) {
                continue;
            }

            Vec3 screen = projectWorldToScreen(structure.vec);
            if (screen == null) {
                continue;
            }

            String text = formatSeconds((structure.time - System.currentTimeMillis()) / 1000.0);
            graphics.renderItem(structure.item.getDefaultInstance(), (int) screen.x - 8, (int) screen.y - 18);
            graphics.drawString(mc.font, text, (int) screen.x - mc.font.width(text) / 2, (int) screen.y, 0xFFFFFFFF, true);
        }

        for (ServerEventData serverEvent : serverEvents) {
            if (serverEvent.anarchy != Network.getAnarchyMode() || !matchesWorld(serverEvent.world)) {
                continue;
            }

            Vec3 screen = projectWorldToScreen(serverEvent.vec);
            if (screen == null) {
                continue;
            }

            double open = (serverEvent.timeOpen - System.currentTimeMillis()) / 1000.0;
            double end = (serverEvent.timeEnd - System.currentTimeMillis()) / 1000.0;
            List<String> lines = new ArrayList<>();
            lines.add(serverEvent.name + " [" + formatDecimal(mc.player.distanceToSqr(serverEvent.vec.x, serverEvent.vec.y, serverEvent.vec.z) <= 0.0D
                    ? 0.0D
                    : Math.sqrt(mc.player.distanceToSqr(serverEvent.vec.x, serverEvent.vec.y, serverEvent.vec.z))) + "m]");
            if (serverEvent.owner != null) {
                lines.add("Призван: " + serverEvent.owner);
            }
            lines.add(open > 0.0 ? "До начала: " + formatSeconds(open) : end > 0.0 ? "До конца: " + formatSeconds(end) : "Конец ивента");
            if (serverEvent.lvl != null) {
                lines.add(serverEvent.lvl);
            }
            drawLines(graphics, lines, screen);
        }

        if (entityUUID != null) {
            PlayerInteractionHelper.streamEntities()
                    .filter(entity -> entity.getUUID().equals(entityUUID))
                    .findFirst()
                    .ifPresent(entity -> {
                        Vec3 screen = projectWorldToScreen(Vec3.atCenterOf(entity.blockPosition().below()));
                        if (screen == null) {
                            return;
                        }

                        String text = !itemsWatch.finished(200.0)
                                ? "Можно забрать"
                                : !itemsWatch.finished(20_000.0)
                                ? formatSeconds(20.0 - itemsWatch.elapsedTime() / 1000.0)
                                : "Скоро";
                        int color = mc.player.distanceTo(entity) < 5.0F ? FRIEND_COLOR : 0xFFFF5555;
                        graphics.drawString(mc.font, text, (int) screen.x - mc.font.width(text) / 2, (int) screen.y, color, true);
                    });
        }
    }

    private void addBind(String key, Item item, String name, String description, java.util.function.BooleanSupplier visible, float distance) {
        BindSetting setting = register(new BindSetting(name, description, KeyBind.NONE));
        setting.visibleWhen(visible);
        keyBindings.add(new KeyBindingEntry(key, item, setting, distance));
        lastKeyStates.put(key, false);
    }

    private void addSimpleItem(String key, Item item, String displayName, String fallbackName) {
        itemConfig.put(key, new ItemInfo(item, displayName, fallbackName, false, null, null));
    }

    private void addLoreItem(String key, Item item, String displayName, String fallbackName, List<String> loreKeywords) {
        itemConfig.put(key, new ItemInfo(item, displayName, fallbackName, true, loreKeywords, null));
    }

    private void addPotionItem(String key, String displayName, String fallbackName, EffectRequirement... requirements) {
        itemConfig.put(key, new ItemInfo(Items.SPLASH_POTION, displayName, fallbackName, true, null, List.of(requirements)));
    }

    private void resetState() {
        itemQueue.clear();
        pendingLootItems.clear();
        lastKeyStates.replaceAll((k, v) -> false);
        blockStateMap.clear();
        structures.clear();
        serverEvents.clear();
        script.cleanup();
        shulkerScript.cleanup();
        itemTimer.reset();
        ahSearchTimer.reset();
        itemsWatch.reset();
        shulkerWatch.reset();
        repairWatch.reset();
        clearActionState();
        ahSearchBindWasPressed = false;
        entityUUID = null;
        movement.reset();
    }

    private void clearActionState() {
        actionState = ActionState.IDLE;
        originalSlot = -1;
        originalSourceSlot = -1;
        pendingInventorySlot = null;
        pendingHotbarSlot = -1;
        actionTimer = 0L;
        stopMovementUntil = 0L;
        keysOverridden = false;
    }

    private void clearPressedBindState() {
        lastKeyStates.replaceAll((k, v) -> false);
        itemQueue.clear();
        ahSearchBindWasPressed = false;
    }

    private void blockMovement() {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        if (mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }
        mc.options.keySprint.setDown(false);
    }

    private void processKeyBindings() {
        if (mc.getWindow() == null) {
            return;
        }

        long handle = mc.getWindow().handle();
        for (KeyBindingEntry bind : keyBindings) {
            if (!bind.setting.isVisible()) {
                continue;
            }

            boolean current = bind.setting.getValue().isDown(handle);
            boolean previous = lastKeyStates.getOrDefault(bind.key, false);
            if (current && !previous) {
                ItemInfo info = itemConfig.get(bind.key);
                Slot slot = info == null ? null : findSlotByItem(info);
                handleBoundItemPress(bind.key, info, slot);
            }
            lastKeyStates.put(bind.key, current);
        }
    }

    private void handleBoundItemPress(String key, ItemInfo info, Slot slot) {
        if (info == null || mc.player == null) {
            return;
        }

        if (slot == null) {
            notifyItemMissing(info);
            return;
        }

        ItemStack stack = slot.getItem();
        if (mc.player.getCooldowns().isOnCooldown(stack)) {
            notifyItemCooldown(info, stack);
            return;
        }

        if (!itemQueue.contains(key)) {
            itemQueue.add(key);
        }
    }

    private void processItemQueue() {
        if (actionState != ActionState.IDLE || itemQueue.isEmpty() || !itemTimer.finished(0)) {
            return;
        }

        String itemKey = itemQueue.poll();
        ItemInfo info = itemConfig.get(itemKey);
        if (info == null) {
            return;
        }

        Slot slot = findSlotByItem(info);
        if (slot == null) {
            notifyItemMissing(info);
            itemTimer.reset();
            return;
        }
        if (mc.player.getCooldowns().isOnCooldown(slot.getItem())) {
            notifyItemCooldown(info, slot.getItem());
            itemTimer.reset();
            return;
        }

        notifyItemUsed(info);
        startItemUse(slot);
        itemTimer.reset();
    }

    private void startItemUse(Slot slot) {
        if (slot == null) {
            return;
        }

        originalSlot = mc.player.getInventory().getSelectedSlot();
        originalSourceSlot = InventoryTask.getMenuSlotId(slot);

        wasForwardPressed = mc.options.keyUp.isDown();
        wasBackPressed = mc.options.keyDown.isDown();
        wasLeftPressed = mc.options.keyLeft.isDown();
        wasRightPressed = mc.options.keyRight.isDown();
        wasJumpPressed = mc.options.keyJump.isDown();
        keysOverridden = true;

        movement.saveState();
        movement.block();
        blockMovement();

        actionState = ActionState.WAIT_BEFORE_USE;
        actionTimer = System.currentTimeMillis();
        stopMovementUntil = actionTimer + PRE_USE_STOP_DELAY_MS;
    }

    private void processItemAction() {
        int safety = 0;

        while (actionState != ActionState.IDLE && safety++ < 4) {
            long now = System.currentTimeMillis();

            if (now - actionTimer < PRE_USE_STOP_DELAY_MS) {
                return;
            }

            switch (actionState) {
                case WAIT_BEFORE_SWAP -> {
                    if (isHotbarSourceSlot(originalSourceSlot)) {
                        pendingHotbarSlot = toHotbarSlot(originalSourceSlot);
                        InventoryUtils.syncSelectedHotbarSlot(pendingHotbarSlot);
                    } else {
                        pendingInventorySlot = getSourceSlot(originalSourceSlot);
                        prepareInventoryUseSilent(pendingInventorySlot);
                    }

                    actionState = ActionState.WAIT_BEFORE_USE;
                    actionTimer = now;
                    stopMovementUntil = actionTimer + PRE_USE_STOP_DELAY_MS;
                }

                case WAIT_BEFORE_USE -> {
                    if (isHotbarSourceSlot(originalSourceSlot)) {
                        pendingHotbarSlot = toHotbarSlot(originalSourceSlot);
                        InventoryUtils.syncSelectedHotbarSlot(pendingHotbarSlot);
                    } else {
                        pendingInventorySlot = getSourceSlot(originalSourceSlot);
                        if (pendingInventorySlot == null) {
                            restoreKeyStates();
                            clearActionState();
                            return;
                        }
                        prepareInventoryUseSilent(pendingInventorySlot);
                    }

                    performUseItem();

                    actionState = ActionState.WAIT_BEFORE_RESTORE;
                    actionTimer = now;
                    stopMovementUntil = actionTimer + PRE_USE_STOP_DELAY_MS;
                }

                case WAIT_BEFORE_RESTORE -> {
                    if (pendingInventorySlot != null) {
                        finishInventoryUseSilent(pendingInventorySlot);
                    } else if (pendingHotbarSlot != -1) {
                        finishHotbarUseSilent(pendingHotbarSlot);
                    }

                    restoreKeyStates();
                    clearActionState();
                }

                default -> {
                    return;
                }
            }

            if (!swapMode.is("Instant") || PRE_USE_STOP_DELAY_MS > 0L) {
                return;
            }
        }
    }

    private boolean isHotbarSourceSlot(int slot) {
        return slot >= 0 && slot < 9 || slot >= 36 && slot < 45;
    }

    private int toHotbarSlot(int slot) {
        return slot >= 36 ? slot - 36 : slot;
    }

    private Slot getSourceSlot(int slot) {
        return slot >= 0 && mc.player != null && mc.player.containerMenu != null && slot < mc.player.containerMenu.slots.size()
                ? mc.player.containerMenu.getSlot(slot)
                : null;
    }

    private void finishHotbarUseSilent(int hotbarSlot) {
        if (hotbarSlot != originalSlot) {
            InventoryUtils.syncSelectedHotbarSlot(originalSlot);
        }
    }

    private void prepareInventoryUseSilent(Slot slot) {
        if (slot == null || originalSlot == -1) {
            return;
        }
        InventoryUtils.click(InventoryTask.getMenuSlotId(slot), originalSlot, ClickType.SWAP);
    }

    private void finishInventoryUseSilent(Slot slot) {
        if (slot == null || originalSlot == -1) {
            return;
        }
        InventoryUtils.click(InventoryTask.getMenuSlotId(slot), originalSlot, ClickType.SWAP);
        InventoryUtils.syncSelectedHotbarSlot(originalSlot);
    }

    private void performUseItem() {
        if (mc.player == null || mc.gameMode == null) {
            return;
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void notifyItemUsed(ItemInfo info) {
        notifyServerHelper1(itemDisplayName(info) + " использована");
    }

    private void notifyItemMissing(ItemInfo info) {
        notifyServerHelper(itemDisplayName(info) + " не найден");
    }

    private void notifyItemCooldown(ItemInfo info, ItemStack stack) {
        notifyServerHelper(itemDisplayName(info) + " будет доступен через " + formatCooldownDurationTicks(cooldownRemainingTicks(stack)));
    }

    private void notifyServerHelper(String text) {
        Notifications.push("Server Helper", text, 4000L, SoundManager.NOTIFICATION);
    }
    private void notifyServerHelper1(String text) {
        Notifications.push("Server Helper", text, 4000L);
    }
    private String itemDisplayName(ItemInfo info) {
        return info == null || info.displayName == null || info.displayName.isBlank() ? "Item" : info.displayName;
    }

    private int cooldownRemainingTicks(ItemStack stack) {
        if (mc.player == null || stack == null || stack.isEmpty()) {
            return -1;
        }

        ItemCooldowns cooldowns = mc.player.getCooldowns();
        try {
            Identifier group = cooldowns.getCooldownGroup(stack);
            Map<Identifier, ?> activeCooldowns = ((ItemCooldownsAccessor) cooldowns).releon$getCooldowns();
            Object cooldown = activeCooldowns.get(group);
            if (cooldown instanceof ItemCooldownInstanceAccessor accessor) {
                return Math.max(0, accessor.releon$getEndTime() - ((ItemCooldownsAccessor) cooldowns).releon$getTickCount());
            }
        } catch (RuntimeException ignored) {
        }

        float progress = cooldowns.getCooldownPercent(stack, 0.0F);
        return progress <= 0.0F ? -1 : Math.max(1, Math.round(progress * 20.0F));
    }

    private String formatCooldownDurationTicks(int ticks) {
        if (ticks < 0) {
            return "?";
        }

        int totalSeconds = Math.max(1, (int) Math.ceil(ticks / 20.0D));
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + ":" + String.format(Locale.US, "%02d", seconds) : totalSeconds + "s";
    }

    private void restoreKeyStates() {
        if (!keysOverridden) {
            return;
        }
        mc.options.keyUp.setDown(wasForwardPressed);
        mc.options.keyDown.setDown(wasBackPressed);
        mc.options.keyLeft.setDown(wasLeftPressed);
        mc.options.keyRight.setDown(wasRightPressed);
        mc.options.keyJump.setDown(wasJumpPressed);
        keysOverridden = false;
        movement.reset();
    }

    private void processServerAutomation() {
        if (autoRepairSetting.isVisible() && autoRepairSetting.getValue() && actionState == ActionState.IDLE && repairWatch.every(5000.0)
                && List.of(
                mc.player.getItemBySlot(EquipmentSlot.FEET),
                mc.player.getItemBySlot(EquipmentSlot.LEGS),
                mc.player.getItemBySlot(EquipmentSlot.CHEST),
                mc.player.getItemBySlot(EquipmentSlot.HEAD)
        ).stream().anyMatch(this::needsMendingRepair)) {
            ItemInfo exp = itemConfig.get("exp");
            Slot slot = exp == null ? null : findSlotByItem(exp);
            if (slot != null && !mc.player.getCooldowns().isOnCooldown(slot.getItem())) {
                startItemUse(slot);
            }
        }

        if (autoShulkerSetting.isVisible() && autoShulkerSetting.getValue()
                && mc.screen == null && !pendingLootItems.isEmpty() && shulkerScript.isFinished() && shulkerWatch.finished(300.0)) {
            Slot shulker = InventoryUtils.findSlot(slot -> slot.hasItem() && slot.getItem().get(DataComponents.CONTAINER) != null);
            if (shulker != null) {
                openLootShulker(shulker);
            }
        }

        if (autoLootSetting.isVisible() && autoLootSetting.getValue()) {
            PlayerInteractionHelper.streamEntities()
                    .filter(AbstractVillager.class::isInstance)
                    .map(AbstractVillager.class::cast)
                    .filter(villager -> !villager.getMainHandItem().isEmpty() || !villager.getOffhandItem().isEmpty())
                    .filter(villager -> mc.player.distanceTo(villager) <= 6.0F)
                    .findFirst()
                    .ifPresent(villager -> {
                        itemsWatch.reset();
                        entityUUID = villager.getUUID();
                        PlayerInteractionHelper.interactEntity(villager);
                    });
        }

        script.cleanupIfFinished().update();
        shulkerScript.cleanupIfFinished().update();

        blockStateMap.clear();
        structures.removeIf(structure -> structure.time <= System.currentTimeMillis());
        serverEvents.removeIf(serverEvent -> serverEvent.timeEnd + 90_000L <= System.currentTimeMillis());
    }

    private boolean needsMendingRepair(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
            return false;
        }
        if ((double) stack.getDamageValue() / (double) stack.getMaxDamage() < 0.94D) {
            return false;
        }

        try {
            Holder<Enchantment> mending = mc.level.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.MENDING);
            return EnchantmentHelper.getItemEnchantmentLevel(mending, stack) > 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void openLootShulker(Slot shulker) {
        int selected = mc.player.getInventory().getSelectedSlot();
        int sourceSlot = InventoryTask.getMenuSlotId(shulker);
        boolean sourceInHotbar = sourceSlot >= 36 && sourceSlot < 45;

        if (sourceInHotbar) {
            InventoryUtils.syncSelectedHotbarSlot(sourceSlot - 36);
        } else {
            InventoryUtils.click(sourceSlot, selected, ClickType.SWAP);
        }

        PlayerInteractionHelper.interactItem(InteractionHand.MAIN_HAND);
        shulkerScript.cleanup().addTickStep(0, () -> moveLootIntoOpenedBackpack(sourceSlot, selected, sourceInHotbar),
                () -> mc.player.containerMenu instanceof ChestMenu && mc.screen != null);
    }

    private void moveLootIntoOpenedBackpack(int sourceSlot, int selectedSlot, boolean sourceInHotbar) {
        if (!(mc.player.containerMenu instanceof ChestMenu chestMenu)) {
            return;
        }

        Map<Item, Integer> moved = new HashMap<>();
        int start = chestMenu.getRowCount() * 9;
        for (int slotId = start; slotId < chestMenu.slots.size(); slotId++) {
            Slot slot = chestMenu.slots.get(slotId);
            if (!slot.hasItem()) {
                continue;
            }

            Item item = slot.getItem().getItem();
            int remaining = pendingLootItems.getOrDefault(item, 0) - moved.getOrDefault(item, 0);
            if (remaining <= 0) {
                continue;
            }

            int movedCount = Math.min(slot.getItem().getCount(), remaining);
            InventoryUtils.click(slotId, 0, ClickType.QUICK_MOVE);
            moved.merge(item, movedCount, Integer::sum);
        }

        for (Map.Entry<Item, Integer> entry : moved.entrySet()) {
            int remaining = pendingLootItems.getOrDefault(entry.getKey(), 0) - entry.getValue();
            if (remaining <= 0) {
                pendingLootItems.remove(entry.getKey());
            } else {
                pendingLootItems.put(entry.getKey(), remaining);
            }
        }

        InventoryUtils.closeScreen();
        if (!sourceInHotbar) {
            InventoryUtils.click(sourceSlot, selectedSlot, ClickType.SWAP);
        }
        InventoryUtils.syncSelectedHotbarSlot(selectedSlot);
        shulkerWatch.reset();
    }

    private List<String> getLore(ItemStack stack) {
        List<String> lore = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return lore;
        }

        try {
            ItemLore itemLore = stack.get(DataComponents.LORE);
            if (itemLore != null) {
                for (Component line : itemLore.lines()) {
                    String clean = cleanText(line);
                    if (!clean.isEmpty()) {
                        lore.add(clean);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return lore;
    }

    private boolean matchesLore(ItemStack stack, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        String lore = String.join(" ", getLore(stack));
        if (lore.isEmpty()) {
            return false;
        }
        int matches = 0;
        for (String keyword : keywords) {
            if (lore.contains(keyword.toLowerCase(Locale.ROOT))) {
                matches++;
            }
        }
        return matches >= Math.min(2, keywords.size());
    }

    private boolean matchesPotionEffects(ItemStack stack, List<EffectRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return false;
        }
        if (stack.getItem() != Items.SPLASH_POTION && stack.getItem() != Items.LINGERING_POTION) {
            return false;
        }

        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return false;
        }

        int matches = 0;
        for (EffectRequirement requirement : requirements) {
            for (MobEffectInstance effect : contents.customEffects()) {
                if (effect.getEffect().equals(requirement.effect) && effect.getAmplifier() >= requirement.minAmplifier) {
                    matches++;
                    break;
                }
            }
            if (matches < requirements.size()) {
                for (MobEffectInstance effect : contents.getAllEffects()) {
                    if (effect.getEffect().equals(requirement.effect) && effect.getAmplifier() >= requirement.minAmplifier) {
                        matches++;
                        break;
                    }
                }
            }
        }
        return matches >= Math.min(2, requirements.size());
    }

    private int countMatchingBinding(KeyBindingEntry bind, ItemInfo info) {
        if (bind == null || mc.player == null) {
            return 0;
        }

        int count = 0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && matchesBindingStack(stack, bind, info)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean matchesBindingStack(ItemStack stack, KeyBindingEntry bind, ItemInfo info) {
        if (stack == null || stack.isEmpty() || bind == null) {
            return false;
        }

        if (info == null) {
            return stack.getItem() == bind.item;
        }
        if (stack.getItem() != info.item) {
            return false;
        }

        if (mode.is("FunTime") && info.funTimeOnly) {
            if (info.effectRequirements != null && !info.effectRequirements.isEmpty()
                    && matchesPotionEffects(stack, info.effectRequirements)) {
                return true;
            }
            if (info.loreKeywords != null && !info.loreKeywords.isEmpty()
                    && matchesLore(stack, info.loreKeywords)) {
                return true;
            }
        }

        return matchesFallbackName(stack, info.nameFallback);
    }

    private Slot findSlotByItem(ItemInfo info) {
        if (info == null) {
            return null;
        }

        if (mode.is("FunTime") && info.funTimeOnly) {
            if (info.effectRequirements != null && !info.effectRequirements.isEmpty()) {
                Slot slot = InventoryUtils.findSlot(candidate -> candidate.hasItem()
                        && candidate.getItem().getItem() == info.item
                        && matchesPotionEffects(candidate.getItem(), info.effectRequirements));
                if (slot != null) {
                    return slot;
                }
            }

            if (info.loreKeywords != null && !info.loreKeywords.isEmpty()) {
                Slot slot = InventoryUtils.findSlot(candidate -> candidate.hasItem()
                        && candidate.getItem().getItem() == info.item
                        && matchesLore(candidate.getItem(), info.loreKeywords));
                if (slot != null) {
                    return slot;
                }
            }
        }

        return InventoryUtils.findSlot(candidate -> candidate.hasItem()
                && candidate.getItem().getItem() == info.item
                && matchesFallbackName(candidate.getItem(), info.nameFallback));
    }

    private boolean matchesFallbackName(ItemStack stack, String fallbackName) {
        if (fallbackName == null || fallbackName.isEmpty()) {
            return false;
        }

        String cleanName = cleanText(stack.getHoverName());
        if (cleanName.contains(fallbackName.toLowerCase(Locale.ROOT))) {
            return true;
        }

        List<String> lore = getLore(stack);
        if (lore.isEmpty()) {
            return false;
        }
        return String.join(" ", lore).contains(fallbackName.toLowerCase(Locale.ROOT));
    }

    private Slot findSlotByBinding(KeyBindingEntry bind) {
        ItemInfo info = itemConfig.get(bind.key);
        Slot slot = info == null ? null : findSlotByItem(info);
        if (slot != null) {
            return slot;
        }
        return InventoryUtils.findSlot(candidate -> candidate.hasItem() && candidate.getItem().getItem() == bind.item);
    }

    private void processFuntimeAhSearchCombo() {
        if (mc.player == null || mc.screen != null || !mode.is("FunTime")) {
            return;
        }

        KeyBind bind = funtimeAhSearchBind.getValue();
        boolean pressed = bind != null && mc.getWindow() != null && bind.isDown(mc.getWindow().handle());
        if (!pressed) {
            ahSearchBindWasPressed = false;
            return;
        }
        if (ahSearchBindWasPressed || !ahSearchTimer.finished(700.0)) {
            return;
        }

        ItemStack stack = mc.player.getMainHandItem();
        if (!stack.isEmpty() && mc.player.connection != null) {
            String query = cleanText(stack.getHoverName())
                    .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!query.isEmpty()) {
                mc.player.connection.sendCommand("ah search " + query);
            }
        }

        ahSearchBindWasPressed = true;
        ahSearchTimer.reset();
    }

    private boolean validDistance(float distance) {
        if (distance == 0.0F) {
            return true;
        }
        for (Player player : mc.level.players()) {
            if (player != mc.player && !FriendUtils.isFriend(player) && mc.player.distanceTo(player) <= distance) {
                return true;
            }
        }
        return false;
    }

    private void drawItemCube(BlockPos playerPos, Vec3 smooth, float size, int lineColor, int fillColor) {
        AABB box = new AABB(playerPos.above()).move(smooth).inflate(size);
        boolean playerInside = mc.level.players().stream()
                .anyMatch(player -> player != mc.player && !FriendUtils.isFriend(player) && box.intersects(player.getBoundingBox()));
        if (playerInside) {
            Render3D.drawBoxWithCrossFull(box, FRIEND_COLOR, FRIEND_FILL_COLOR, 2.0F);
        } else {
            Render3D.drawBoxWithCrossFull(box, lineColor, fillColor, 2.0F);
        }
    }

    private void processServerPoint(Component content) {
        String contentString = content.toString();
        String message = content.getString();
        String name = substringBetween(message, "||| [", "] ");
        if (name == null) {
            return;
        }

        String position = substringBetween(contentString, "value='/gps ", "'");
        String lvl = substringBetween(message, "Уровень лута: ", "\n ║");
        String owner = substringBetween(message, "Призван игроком: ", "\n ║");

        if (position != null) {
            String[] parts = position.split(" ");
            if (parts.length >= 3) {
                try {
                    Vec3 center = Vec3.atCenterOf(new BlockPos(
                            Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2])
                    ));
                    switch (name) {
                        case "Мистический сундук" -> addEvent(name, lvl, owner, center, "overworld", 300, 0);
                        case "Вулкан" -> addEvent(name, lvl, owner, center, "overworld", 300, 120);
                        case "Метеоритный дождь", "Маяк убийца", "Мистический Алтарь" -> addEvent(name, lvl, owner, center, "overworld", 360, 0);
                        case "Загадочный маяк" -> addEvent(name, lvl, owner, center, "overworld", 60, 180);
                        default -> {
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } else {
            switch (name) {
                case "Сундук смерти" -> addEvent(name, lvl, owner, Vec3.atCenterOf(new BlockPos(-155, 64, 205)), "lobby", 300, 0);
                case "Адская резня" -> addEvent(name, lvl, owner, Vec3.atCenterOf(new BlockPos(48, 87, 73)), "lobby", 180, 120);
                default -> {
                }
            }
        }
    }

    private void processExperienceCooldown(String message) {
        if (message == null || !message.contains("Повторно активировать") || !message.contains("Пузырь опыта")) {
            return;
        }

        String seconds = substringBetween(message, "через ", " секунд");
        if (seconds == null || seconds.isEmpty()) {
            return;
        }

        try {
            int duration = Integer.parseInt(seconds) * 20;
            mc.player.getCooldowns().addCooldown(Items.EXPERIENCE_BOTTLE.getDefaultInstance(), duration);
        } catch (NumberFormatException ignored) {
        }
    }

    private void addEvent(String name, String lvl, String owner, Vec3 vec, String world, int timeOpen, int timeLoot) {
        for (ServerEventData event : serverEvents) {
            if (event.vec.equals(vec)) {
                return;
            }
        }
        long open = System.currentTimeMillis() + timeOpen * 1000L;
        long end = open + timeLoot * 1000L;
        serverEvents.add(new ServerEventData(name, lvl, owner, vec, world, Network.getAnarchyMode(), open, end));
    }

    private void addStructure(Item item, Vec3 vec, double time) {
        for (Structure structure : structures) {
            if (structure.vec.equals(vec)) {
                return;
            }
        }
        structures.add(new Structure(item, vec, currentWorldToken(), Network.getAnarchyMode(), time));
    }

    private boolean isTrap(BlockPos center) {
        int inconsistencies = 0;
        for (BlockPos pos : PlayerInteractionHelper.getCube(center, 2.0F)) {
            if (Vec3.atCenterOf(pos).distanceTo(Vec3.atCenterOf(center)) < 2.0D) {
                BlockState state = blockStateMap.get(pos);
                if (state != null && !state.isAir()) {
                    inconsistencies++;
                }
            } else if (!pos.equals(center.above(2).north().east())
                    && !pos.equals(center.above(2).north().west())
                    && !pos.equals(center.above(2).south().east())
                    && !pos.equals(center.above(2).south().west())) {
                BlockState state = blockStateMap.get(pos);
                if (state == null || state.isAir()) {
                    inconsistencies++;
                }
            }
            if (inconsistencies > 1) {
                return false;
            }
        }
        return true;
    }

    private boolean isBigTrap(BlockPos center) {
        int inconsistencies = 0;
        for (BlockPos pos : PlayerInteractionHelper.getCube(center, 3.0F)) {
            if (Math.abs(pos.getX() - center.getX()) <= 2
                    && Math.abs(pos.getY() - center.getY()) <= 2
                    && Math.abs(pos.getZ() - center.getZ()) <= 2) {
                BlockState state = blockStateMap.get(pos);
                if (state != null && !state.isAir()) {
                    inconsistencies++;
                }
            } else if (!pos.equals(center.above(3))) {
                BlockState state = blockStateMap.get(pos);
                if (state == null || state.isAir()) {
                    inconsistencies++;
                }
            }
            if (inconsistencies > 1) {
                return false;
            }
        }
        return true;
    }

    private void drawLines(GuiGraphics graphics, List<String> lines, Vec3 screen) {
        int y = (int) screen.y;
        for (String line : lines) {
            graphics.drawString(mc.font, line, (int) screen.x - mc.font.width(line) / 2, y, 0xFFFFFFFF, true);
            y += 10;
        }
    }

    private Vec3 projectWorldToScreen(Vec3 worldPos) {
        if (mc.getWindow() == null) {
            return null;
        }

        Matrix4f combined = new Matrix4f(Render3D.lastProjMat).mul(Render3D.lastModMat);
        Vector4f clip = combined.transform(new Vector4f((float) worldPos.x, (float) worldPos.y, (float) worldPos.z, 1.0F));
        if (!Float.isFinite(clip.w) || clip.w <= 0.01F) {
            return null;
        }

        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        float ndcZ = clip.z / clip.w;
        if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY) || !Float.isFinite(ndcZ)) {
            return null;
        }
        if (ndcZ < -1.0F || ndcZ > 1.0F) {
            return null;
        }

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        double x = (ndcX * 0.5D + 0.5D) * width;
        double y = (1.0D - (ndcY * 0.5D + 0.5D)) * height;
        if (x < -32.0D || x > width + 32.0D || y < -32.0D || y > height + 32.0D) {
            return null;
        }

        return new Vec3(x, y, ndcZ);
    }

    private boolean matchesWorld(String world) {
        String current = currentWorldToken();
        return world.equals(current) || ("lobby".equals(world) && "overworld".equals(current));
    }

    private String currentWorldToken() {
        if (mc.level == null) {
            return "unknown";
        }
        return mc.level.dimension().identifier().getPath();
    }

    private String cleanText(Component component) {
        return component == null ? "" : cleanText(component.getString());
    }

    private String cleanText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder(value.length());
        boolean skip = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (skip) {
                skip = false;
                continue;
            }
            if (c == '\u00A7') {
                skip = true;
                continue;
            }
            if (c == 194) {
                continue;
            }
            out.append(c == '\u00A0' ? ' ' : Character.toLowerCase(c));
        }
        return out.toString().trim();
    }

    private String substringBetween(String value, String open, String close) {
        if (value == null || open == null || close == null) {
            return null;
        }
        int start = value.indexOf(open);
        if (start == -1) {
            return null;
        }
        start += open.length();
        int end = value.indexOf(close, start);
        if (end == -1) {
            return null;
        }
        return value.substring(start, end);
    }

    private String formatSeconds(double seconds) {
        return formatDecimal(Math.max(0.0D, seconds)) + "с";
    }

    private String formatDecimal(double value) {
        return String.format(Locale.US, "%.1f", value).replace(".0", "");
    }

    private enum ActionState {
        IDLE,
        WAIT_BEFORE_SWAP,
        WAIT_BEFORE_USE,
        WAIT_BEFORE_RESTORE
    }

    public record HudBind(String key, Item item, BindSetting setting, ItemStack stack, int inventoryCount,
                          float cooldownProgress, String displayName) {
    }

    public record HudEvent(String name, String level, String owner, String timer, double distance) {
    }

    private record KeyBindingEntry(String key, Item item, BindSetting setting, float distance) {
    }

    private record EffectRequirement(Holder<MobEffect> effect, int minAmplifier) {
    }

    private record Structure(Item item, Vec3 vec, String world, int anarchy, double time) {
    }

    private record ServerEventData(String name, String lvl, String owner, Vec3 vec, String world, int anarchy,
                                   double timeOpen, double timeEnd) {
    }

    private static final class ItemInfo {
        private final Item item;
        private final String displayName;
        private final String nameFallback;
        private final boolean funTimeOnly;
        private final List<String> loreKeywords;
        private final List<EffectRequirement> effectRequirements;

        private ItemInfo(Item item, String displayName, String nameFallback, boolean funTimeOnly,
                         List<String> loreKeywords, List<EffectRequirement> effectRequirements) {
            this.item = item;
            this.displayName = displayName;
            this.nameFallback = nameFallback == null ? "" : nameFallback.toLowerCase(Locale.ROOT);
            this.funTimeOnly = funTimeOnly;
            this.loreKeywords = loreKeywords;
            this.effectRequirements = effectRequirements;
        }
    }
}
