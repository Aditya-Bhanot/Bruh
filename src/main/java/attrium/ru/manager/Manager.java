package attrium.ru.manager;



import attrium.ru.api.module.impl.misc.ElytraHelper;
import attrium.ru.api.module.impl.combat.AutoPearlCatch;
import attrium.ru.api.module.impl.movement.LungeHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import attrium.ru.api.command.CommandManager;
import attrium.ru.api.config.ConfigManager;
import attrium.ru.api.drag.core.ElementManager;
import attrium.ru.api.events.Event;
import attrium.ru.api.events.bus.EventBus;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.ModuleManager;
import attrium.ru.api.module.impl.combat.aura.AngleConnection;
import attrium.ru.screens.ClickGui;
import attrium.ru.screens.ClickGuiScreen;
import attrium.ru.screens.WorldAnimation;
import attrium.ru.utils.repository.blockesp.BlockESPConfig;
import attrium.ru.utils.inventory.item.ItemToolkit;
import attrium.ru.utils.repository.friend.FriendUtils;
import attrium.ru.utils.repository.macro.MacroRepository;
import attrium.ru.utils.repository.staff.StaffUtils;
import attrium.ru.utils.repository.way.WayRepository;

public class Manager {
    private static Manager instance;

    private EventBus eventBus;
    private ModuleManager moduleManager;
    private ConfigManager configManager;
    private CommandManager commandManager;
    private boolean clickGuiTextWarmed;

    public void initClient() {
        instance = this;
        eventBus = new EventBus();
        FriendUtils.load();
        StaffUtils.load();
        MacroRepository.getInstance().load();
        WayRepository.getInstance().load();
        BlockESPConfig.getInstance().load();
        moduleManager = new ModuleManager(eventBus);
        moduleManager.init();
        commandManager = new CommandManager();
        commandManager.init();
        eventBus.register(AngleConnection.INSTANCE);
        configManager = new ConfigManager(moduleManager);
        moduleManager.setDirtyListener(configManager::markDirty);
        configManager.init();
        ElementManager.getInstance().load();
        configManager.loadAll();
        eventBus.register(configManager);
        eventBus.register(commandManager);
        eventBus.register(MacroRepository.getInstance());
        eventBus.register(WayRepository.getInstance());
        eventBus.register(ItemToolkit.INSTANCE);

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            inStartClientTick = true;
            startClientTickCount++;
            try {
                warmClickGuiText();
                WorldAnimation.tick();
                ClickGui.tickMovementKeys();
                postEvent(new TickEvent.Pre(client));
                ElytraHelper.tickMacroGlobal(client, ElytraHelper.getMacroBoundKey());
                LungeHelper.tickMacroGlobal(client, LungeHelper.getMacroBoundKey());
                AutoPearlCatch.tickMacroGlobal(client, AutoPearlCatch.getMacroBoundKey());
            } finally {
                inStartClientTick = false;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> postEvent(new TickEvent.Post(client)));
    }

    private static volatile boolean inStartClientTick = false;
    private static int startClientTickCount = 0;

    public static boolean isInStartClientTick() {
        return inStartClientTick;
    }

    public static int getStartClientTickCount() {
        return startClientTickCount;
    }

    public static Manager getInstance() {
        return instance;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public static ModuleManager getModules() {
        return instance == null ? null : instance.moduleManager;
    }

    public static <T extends Event> T postEvent(T event) {
        if (instance == null || instance.eventBus == null) {
            return event;
        }
        return instance.eventBus.post(event);
    }

    public static void toggleClickGui(Minecraft client) {
        if (client.screen instanceof ClickGuiScreen) {
            client.screen.onClose();
            return;
        }
        if (WorldAnimation.isActive()) {
            return;
        }
        /* disabled legacy ClickGUI open: client.setScreen(new ClickGuiScreen()); */
    }

    private void warmClickGuiText() {
        if (clickGuiTextWarmed) {
            return;
        }
        clickGuiTextWarmed = true;
        ClickGui.warmupText();
    }
}
