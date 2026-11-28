package attrium.ru.api.module;







import attrium.ru.api.module.impl.misc.NameProtectModule;
import attrium.ru.api.module.impl.misc.AutoUpdaterModule;
import attrium.ru.api.module.impl.misc.ChatUtilsModule;
import attrium.ru.api.module.impl.visual.ArrayListModule;
import attrium.ru.api.module.impl.visual.NotificationsModule;
import attrium.ru.api.module.impl.visual.TargetHUDModule;
import attrium.ru.api.module.impl.misc.UISoundsModule;
import attrium.ru.api.module.impl.visual.PerformanceHudModule;
import attrium.ru.api.module.impl.visual.TrailsModule;
import attrium.ru.api.module.impl.visual.BlockOverlayModule;
import attrium.ru.api.module.impl.visual.PlayerRadarModule;
import attrium.ru.api.module.impl.visual.KeybindsModule;
import attrium.ru.api.module.impl.combat.SilentAura;
import net.minecraft.client.Minecraft;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.bus.EventBus;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.impl.combat.AuraModule;
import attrium.ru.api.module.impl.combat.AntiBot;
import attrium.ru.api.module.impl.combat.AimAssist;
import attrium.ru.api.module.impl.combat.AutoCrystal;
import attrium.ru.api.module.impl.combat.AutoPearlCatch;
import attrium.ru.api.module.impl.combat.AutoGApple;
import attrium.ru.api.module.impl.combat.AutoSwap;
import attrium.ru.api.module.impl.combat.AutoTotem;
import attrium.ru.api.module.impl.combat.HitSound;
import attrium.ru.api.module.impl.combat.MaceTarget;
import attrium.ru.api.module.impl.combat.NoFriendDamage;
import attrium.ru.api.module.impl.combat.NoInteract;
import attrium.ru.api.module.impl.combat.TriggerBot;
import attrium.ru.api.module.impl.movement.*;
import attrium.ru.api.module.impl.misc.AutoAuth;
import attrium.ru.api.module.impl.misc.AutoDuel;
import attrium.ru.api.module.impl.misc.AutoLes;
import attrium.ru.api.module.impl.misc.AutoLeave;
import attrium.ru.api.module.impl.misc.AutoTpAccept;
import attrium.ru.api.module.impl.misc.ClientSounds;
import attrium.ru.api.module.impl.misc.DeathCoords;
import attrium.ru.api.module.impl.misc.ElytraHelper;
import attrium.ru.api.module.impl.misc.JoinerHelper;
import attrium.ru.api.module.impl.misc.ServerHelper;
import attrium.ru.api.module.impl.misc.UseTracker;
import attrium.ru.api.module.impl.player.AHHelper;
import attrium.ru.api.module.impl.player.AutoRespawn;
import attrium.ru.api.module.impl.player.AutoTool;
import attrium.ru.api.module.impl.player.ChestStealer;
import attrium.ru.api.module.impl.player.ClickPearl;
import attrium.ru.api.module.impl.misc.ServerRPSpoofer;
import attrium.ru.api.module.impl.player.NoEntityTrace;
import attrium.ru.api.module.impl.player.ItemScroller;
import attrium.ru.api.module.impl.player.FreeLook;
import attrium.ru.api.module.impl.player.FreeCam;
import attrium.ru.api.module.impl.visual.Ambience;
import attrium.ru.api.module.impl.visual.Arrows;
import attrium.ru.api.module.impl.visual.AspectRatio;
import attrium.ru.api.module.impl.visual.BlockESP;
import attrium.ru.api.module.impl.visual.DashTrail;
import attrium.ru.api.module.impl.visual.ESP;
import attrium.ru.api.module.impl.visual.FogBlur;
import attrium.ru.api.module.impl.visual.Hands;
import attrium.ru.api.module.impl.visual.ItemPhysics;
import attrium.ru.api.module.impl.visual.KillEffect;
import attrium.ru.api.module.impl.visual.NoRender;
import attrium.ru.api.module.impl.visual.Particles;
import attrium.ru.api.module.impl.visual.SeeInvisible;
import attrium.ru.api.module.impl.visual.SwingAnimation;
import attrium.ru.api.module.impl.visual.TargetESP;
import attrium.ru.api.module.impl.visual.ViewModel;
import attrium.ru.api.settings.bind.KeyBind;
import attrium.ru.utils.inventory.InventoryFlowManager;
import attrium.ru.utils.network.Network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ModuleManager {
    private final EventBus eventBus;
    private final List<Module> modules = new ArrayList<>();
    private final Map<String, Module> byName = new HashMap<>();
    private final Map<Class<? extends Module>, Module> byType = new HashMap<>();
    private final Map<ModuleCategory, List<Module>> byCategory = new EnumMap<>(ModuleCategory.class);
    private final Map<ModuleCategory, List<Module>> byCategoryViews = new EnumMap<>(ModuleCategory.class);
    private Collection<Module> modulesView = List.of();
    private final Map<Module, Boolean> bindStates = new IdentityHashMap<>();
    private Runnable dirtyListener = () -> {};

    public ModuleManager(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void init() {
        registerDefaults();
        eventBus.register(this);
    }

    public void setDirtyListener(Runnable dirtyListener) {
        this.dirtyListener = dirtyListener == null ? () -> {} : dirtyListener;
    }

    public void register(Module module) {
        String key = normalize(module.getName());
        if (byName.containsKey(key)) {
            return;
        }
        modules.add(module);
        byName.put(key, module);
        byType.putIfAbsent(module.getClass(), module);
        byCategory.computeIfAbsent(module.getCategory(), ignored -> new ArrayList<>()).add(module);
        module.addStateListener(changedModule -> dirtyListener.run());
        module.initialize(eventBus);
        sortViews();
    }

    public Collection<Module> getModules() {
        return modulesView;
    }

    public List<Module> getByCategory(ModuleCategory category) {
        return byCategoryViews.getOrDefault(category, List.of());
    }

    public Optional<Module> getByName(String name) {
        return Optional.ofNullable(byName.get(normalize(name)));
    }

    public <T extends Module> Optional<T> getByType(Class<T> type) {
        Module module = byType.get(type);
        if (type.isInstance(module)) {
            return Optional.of(type.cast(module));
        }
        for (Module candidate : modules) {
            if (type.isInstance(candidate)) {
                return Optional.of(type.cast(candidate));
            }
        }
        return Optional.empty();
    }

    public boolean isEnabled(Class<? extends Module> type) {
        return getByType(type).filter(Module::isEnabled).isPresent();
    }

    public Optional<AuraModule> getAura() {
        return getByType(AuraModule.class);
    }

    public Optional<AuraModule> getKillAura() {
        return getAura();
    }

    public Optional<NoInteract> getNoInteract() {
        return getByType(NoInteract.class);
    }

    public boolean isNoInteractEnabled() {
        return isEnabled(NoInteract.class);
    }

    public Optional<ServerRPSpoofer> getServerRPSpoofer() {
        return getByType(ServerRPSpoofer.class);
    }

    public boolean isServerRPSpooferEnabled() {
        return isEnabled(ServerRPSpoofer.class);
    }

    @SubscribeEvent
    private void onPreTick(TickEvent.Pre event) {
        InventoryFlowManager.update();
    }

    @SubscribeEvent
    private void onTick(TickEvent.Post event) {
        Minecraft client = event.getClient();
        Network.tick();
        handleBinds(client);
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onTick(client);
            }
        }
    }

    private void registerDefaults() {
        register(new AntiBot());
        register(new AimAssist());
        register(new AuraModule());
        register(new AutoCrystal());
        register(new AutoPearlCatch());
        register(new AutoGApple());
        register(new AutoSwap());
        register(new AutoTotem());
        register(new MaceTarget());
        register(new NameProtectModule());
        register(new ChatUtilsModule());
        register(new KeybindsModule());
        register(new TargetHUDModule());
        register(new UISoundsModule());
        register(new PerformanceHudModule());
        register(new TrailsModule());
        register(new BlockOverlayModule());
        register(new PlayerRadarModule());
        register(new NotificationsModule());
        register(new ArrayListModule());
        register(new SilentAura());
        register(new LungeHelper());
        register(new HitSound());
        register(new NoFriendDamage());
        register(new NoInteract());
        register(new TriggerBot());

        register(new AutoSprint());
        register(new ElytraTarget());
        register(new Ambience());
        register(new Arrows());
        register(new AspectRatio());
        register(new DashTrail());
        register(new BlockESP());
        register(new ESP());
        register(new FogBlur());
        register(new Hands());
        register(new ItemPhysics());
        register(new KillEffect());
        register(new Particles());
        register(new SeeInvisible());
        register(new SwingAnimation());
        register(new ViewModel());
        register(new NoRender());
        register(new TargetESP());

        register(new NoEntityTrace());
        register(new ItemScroller());
        register(new FreeLook());
        register(new FreeCam());
        register(new AHHelper());
        register(new AutoRespawn());
        register(new AutoTool());
        register(new ChestStealer());

        register(new JoinerHelper());
        register(new ServerRPSpoofer());
        register(new AutoAuth());
        register(new AutoDuel());
        register(new AutoLeave());
        register(new AutoUpdaterModule());
        register(new AutoTpAccept());
        register(new AutoLes());
        register(new ClientSounds());
        register(new ClickPearl());
        register(new DeathCoords());
        register(new ElytraHelper());
        register(new ServerHelper());
        register(new UseTracker());
    }

    private void handleBinds(Minecraft client) {
        if (client == null || client.getWindow() == null || client.screen != null) {
            bindStates.clear();
            return;
        }

        long handle = client.getWindow().handle();
        for (Module module : modules) {
            KeyBind bind = module.getBind();
            boolean down = bind.isDown(handle);
            boolean wasDown = bindStates.getOrDefault(module, false);
            if (down && !wasDown) {
                module.toggle();
            }
            bindStates.put(module, down);
        }
    }

    private void register(Module module, String name, String description, ModuleCategory category) {
        module.configure(name, description, category);
        register(module);
    }

    private void sortViews() {
        modules.sort(Comparator.comparing(module -> module.getName().toLowerCase(Locale.ROOT)));
        for (List<Module> categoryModules : byCategory.values()) {
            categoryModules.sort(Comparator.comparing(module -> module.getName().toLowerCase(Locale.ROOT)));
        }
        modulesView = Collections.unmodifiableList(modules);
        byCategoryViews.clear();
        for (Map.Entry<ModuleCategory, List<Module>> entry : byCategory.entrySet()) {
            byCategoryViews.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
    }

    private String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
