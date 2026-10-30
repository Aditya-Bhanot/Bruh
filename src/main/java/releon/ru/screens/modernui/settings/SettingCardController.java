package releon.ru.screens.modernui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.utils.math.MathUtils;
import releon.ru.utils.render.animation.modernfx.Decelerate;
import releon.ru.utils.render.animation.modernfx.Direction;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SettingCardController {
    private static final int MODULE_LIST_ANIMATION_MS = 220;
    private static final int CATEGORY_TRANSITION_MS = 150;
    private static final float CATEGORY_SWITCH_THRESHOLD = 0.08F;
    private static final float CATEGORY_RENDER_THRESHOLD = 0.04F;
    private static final Map<ModuleCategory, ScrollSnapshot> CATEGORY_SCROLLS = new EnumMap<>(ModuleCategory.class);
    private static final ScrollSnapshot SEARCH_SCROLL = new ScrollSnapshot();

    private final Map<Module, ModuleState> moduleStates = new HashMap<>();
    private final Map<Setting<?>, SettingCardComponent<?>> settingComponents = new HashMap<>();
    private final SettingCardContext context = new SettingCardContext();
    private final Decelerate categoryTransition = MathUtils.createAnimation(CATEGORY_TRANSITION_MS, Direction.FORWARDS);
    private float moduleScroll;
    private float moduleScrollTarget;
    private float moduleContentHeight;
    private boolean moduleClipReady;
    private boolean moduleListInitialized;
    private ModuleCategory activeCategory;
    private boolean activeSearchMode;
    private ModuleCategory pendingCategory;
    private boolean pendingSearchMode;
    private List<Module> displayedModules = List.of();
    private List<Module> pendingModules = List.of();
    private Module listeningModuleBind;
    private List<Module> lastRenderModules = List.of();

    public void render(GuiGraphics graphics, float x, float y, float panelWidth, float panelHeight, ModuleCategory category, boolean searchMode, List<Module> modules, int mouseX, int mouseY) {
        updateModuleScope(category, searchMode, modules == null ? List.of() : modules);
        float categoryProgress = context.clamp(categoryTransition.getOutput().floatValue(), 0.0F, 1.0F);
        if (categoryProgress < CATEGORY_RENDER_THRESHOLD) {
            categoryProgress = 0.0F;
        }
        renderModuleCards(graphics, x, y, panelWidth, panelHeight, displayedModules, categoryProgress, mouseX, mouseY);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (listeningModuleBind != null) {
            finishModuleBind(KeyBind.mouse(event.button()));
            return true;
        }
        if (context.listeningBind != null) {
            context.listeningBind.setValue(KeyBind.mouse(event.button()));
            context.listeningBind = null;
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && handleModuleBindClick(event)) {
            return true;
        }
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return false;
        }

        if (dispatchOverlayClick(event)) {
            return true;
        }
        if (dispatchSettingClick(event, doubled)) {
            return true;
        }
        if (handleModuleStarClick(event)) {
            return true;
        }
        if (handleModuleClick(event)) {
            return true;
        }
        if (context.focusedText != null) {
            context.focusedText = null;
        }
        return false;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        boolean consumed = false;
        for (SettingCardComponent<?> component : settingComponents.values()) {
            consumed |= component.mouseReleased(event, context);
        }
        return consumed;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        for (SettingCardComponent<?> component : settingComponents.values()) {
            if (component.mouseDragged(event, dragX, dragY, context)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!moduleClipReady || !context.inside(mouseX, mouseY, context.moduleClipX, context.moduleClipY, context.moduleClipWidth, context.moduleClipHeight)) {
            return false;
        }
        moduleScrollTarget = context.clamp(moduleScrollTarget - (float) scrollY * 20.0F, 0.0F, Math.max(0.0F, moduleContentHeight - context.moduleClipHeight));
        saveScrollScope();
        return true;
    }

    public boolean keyPressed(KeyEvent event) {
        if (listeningModuleBind != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                finishModuleBind(KeyBind.NONE);
            } else {
                finishModuleBind(KeyBind.keyboard(event.key()));
            }
            return true;
        }
        if (context.listeningBind != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                context.listeningBind.setValue(KeyBind.NONE);
            } else {
                context.listeningBind.setValue(KeyBind.keyboard(event.key()));
            }
            context.listeningBind = null;
            return true;
        }
        if (context.focusedText != null) {
            SettingCardComponent<?> component = settingComponents.get(context.focusedText);
            return component != null && component.keyPressed(event, context);
        }
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        if (context.focusedText == null) {
            return false;
        }
        SettingCardComponent<?> component = settingComponents.get(context.focusedText);
        return component != null && component.charTyped(event, context);
    }

    private void renderModuleCards(GuiGraphics graphics, float x, float y, float panelWidth, float panelHeight, List<Module> modules, float categoryProgress, int mouseX, int mouseY) {
        boolean firstListRender = !moduleListInitialized;
        if (modulesChanged(modules)) {
            lastRenderModules = new ArrayList<>(modules);
        }
        for (ModuleState state : moduleStates.values()) {
            state.visible = false;
            state.includedThisFrame = false;
        }
        for (SettingCardComponent<?> component : settingComponents.values()) {
            component.hide();
        }
        for (Module module : modules) {
            for (Setting<?> setting : module.getSettings()) {
                SettingCardComponent<?> component = componentFor(setting);
                if (component != null) {
                    component.updateVisibility();
                }
            }
        }

        context.moduleClipX = x + 53.0F;
        context.moduleClipY = y + 41.5F;
        context.moduleClipWidth = panelWidth - 60.0F;
        context.moduleClipHeight = panelHeight - 49.0F;
        moduleClipReady = true;

        float cardGap = 6.0F;
        List<ModuleLayoutEntry> layoutEntries = layoutEntries(modules);
        moduleContentHeight = measureModuleLayoutHeight(layoutEntries, cardGap) + 15.0F;
        moduleScrollTarget = context.clamp(moduleScrollTarget, 0.0F, Math.max(0.0F, moduleContentHeight - context.moduleClipHeight));
        moduleScroll += (moduleScrollTarget - moduleScroll) * 0.22F;
        saveScrollScope();

        Render2D.pushScissor(graphics, context.moduleClipX, context.moduleClipY, context.moduleClipWidth, context.moduleClipHeight);
        float leftX = x + 60.0F;
        float rightX = x + 215.0F;
        float startY = y + 48.0F + (1.0F - categoryProgress) * 15.0F - moduleScroll;
        float leftOffset = 0.0F;
        float rightOffset = 0.0F;
        float leftLayoutOffset = 0.0F;
        float rightLayoutOffset = 0.0F;
        for (ModuleLayoutEntry entry : layoutEntries) {
            ModuleState state = moduleState(entry.module);
            boolean leftColumn = state.column == 0 || (state.column < 0 && leftLayoutOffset <= rightLayoutOffset);
            state.column = leftColumn ? 0 : 1;
            float cardX = leftColumn ? leftX : rightX;
            float cardY = startY + (leftColumn ? leftOffset : rightOffset);
            state.includedThisFrame = true;
            if (firstListRender && !state.includedLastFrame) {
                completeListAnimation(state.entryAnimation, Direction.FORWARDS);
            } else {
                state.entryAnimation.setDirection(Direction.FORWARDS);
            }
            float entryAnimation = context.clamp(state.entryAnimation.getOutput().floatValue(), 0.0F, 1.0F);
            float targetX = cardX;
            float targetY = cardY;
            if (!state.positionReady) {
                state.cardX = targetX;
                state.cardY = targetY;
                state.positionReady = true;
            } else {
                state.cardX = context.lerp(state.cardX, targetX, 0.24F);
                state.cardY = context.lerp(state.cardY, targetY, 0.24F);
            }
            state.cardWidth = 150.0F;
            state.cardHeight = entry.height;
            state.visible = state.cardY + entry.height >= context.moduleClipY && state.cardY <= context.moduleClipY + context.moduleClipHeight;
            if (state.visible) {
                renderModuleCard(graphics, entry.module, entry.settings, state, entryAnimation * categoryProgress, mouseX, mouseY);
            }
            if (leftColumn) {
                leftOffset += entry.height + cardGap;
                leftLayoutOffset += entry.layoutHeight + cardGap;
            } else {
                rightOffset += entry.height + cardGap;
                rightLayoutOffset += entry.layoutHeight + cardGap;
            }
        }
        renderExitingModules(graphics, categoryProgress, mouseX, mouseY);
        for (ModuleState state : moduleStates.values()) {
            float progress = context.clamp(state.entryAnimation.getOutput().floatValue(), 0.0F, 1.0F);
            state.includedLastFrame = state.includedThisFrame || progress > 0.01F;
            if (!state.includedLastFrame) {
                state.column = -1;
            }
        }
        moduleListInitialized = true;
        Render2D.popScissor(graphics);
        Render2D.flush();
        Render2D.beginFrame(graphics);
        renderFloatingOverlays(graphics, categoryProgress);
        Render2D.flush();
        Render2D.beginFrame(graphics);
    }

    private void renderModuleCard(GuiGraphics graphics, Module module, List<SettingCardComponent<?>> settings, ModuleState state, float alpha, int mouseX, int mouseY) {
        float cardX = state.cardX;
        float cardY = state.cardY;
        float previousAlpha = context.visualAlpha();
        context.setVisualAlpha(previousAlpha * alpha);
        renderInnerCard(cardX, cardY, state.cardWidth, state.cardHeight);
        renderModuleHeader(graphics, module, state);

        float settingY = cardY + 24.0F;
        for (SettingCardComponent<?> component : settings) {
            float height = component.animatedHeight(context);
            if (settingY + height >= context.moduleClipY - 5.0F && settingY <= context.moduleClipY + context.moduleClipHeight + 5.0F) {
                component.renderAnimated(graphics, context, cardX, settingY, mouseX, mouseY);
            }
            settingY += height;
        }
        for (SettingCardComponent<?> component : settings) {
            component.renderOverlay(graphics, context);
        }
        context.setVisualAlpha(previousAlpha);
    }

    private void renderExitingModules(GuiGraphics graphics, float categoryProgress, int mouseX, int mouseY) {
        for (ModuleState state : moduleStates.values()) {
            if (state.includedThisFrame || !state.includedLastFrame) {
                continue;
            }
            state.entryAnimation.setDirection(Direction.BACKWARDS);
            float progress = context.clamp(state.entryAnimation.getOutput().floatValue(), 0.0F, 1.0F);
            if (progress <= 0.01F) {
                continue;
            }
            state.visible = state.cardY + state.cardHeight >= context.moduleClipY && state.cardY <= context.moduleClipY + context.moduleClipHeight;
            if (state.visible) {
                renderModuleCard(graphics, state.module, visibleComponents(state.module), state, progress * categoryProgress, mouseX, mouseY);
            }
        }
    }

    private void renderFloatingOverlays(GuiGraphics graphics, float alpha) {
        if (alpha <= CATEGORY_RENDER_THRESHOLD) {
            return;
        }
        float previousAlpha = context.visualAlpha();
        context.setVisualAlpha(previousAlpha * alpha);
        for (SettingCardComponent<?> component : settingComponents.values()) {
            component.renderFloatingOverlay(graphics, context);
        }
        context.setVisualAlpha(previousAlpha);
    }

    private void renderModuleHeader(GuiGraphics graphics, Module module, ModuleState state) {
        float cardX = state.cardX;
        float cardY = state.cardY;
        state.toggleAnimation.setDirection(module.isEnabled() ? Direction.FORWARDS : Direction.BACKWARDS);
        float animation = context.clamp(state.toggleAnimation.getOutput().floatValue(), 0.0F, 1.0F);


        Render2D.pushScissor(graphics, cardX + 5.0F, cardY + 4.0F, 91.0F, 15.0F);

        Render2D.text(
                FontType.HUD_ICONS,
                "o",
                cardX + 6.0F, cardY + 5.5F,
                10F,
                context.color(128, 128, 128, 250)
        );

        float moduleNameX = cardX + 18.0F;
        float moduleNameY = cardY + 7.0F;
        float moduleNameSize = 6.0F;
        Render2D.text(
                FontType.SEMIBOLD,
                module.getName(),
                moduleNameX, moduleNameY,
                moduleNameSize,
                context.color(255, 255, 255, 255)
        );
        renderModuleBindIndicator(module, state, moduleNameX, moduleNameY, moduleNameSize);

        Render2D.popScissor(graphics);

        state.toggleX = cardX + 127.0F;
        state.toggleY = cardY + 5.0F;
        state.toggleWidth = 17.0F;
        state.toggleHeight = 11.0F;

        if (animation > 0.01F) {
            Render2D.rect(
                    state.toggleX,
                    state.toggleY,
                    state.toggleWidth,
                    state.toggleHeight,
                    4,
                    context.withAlpha(new Color(145, 115, 185, 250), animation),
                    context.withAlpha(new Color(195, 135, 195, 250), animation),
                    context.withAlpha(new Color(145, 115, 185, 250), animation),
                    context.withAlpha(new Color(125, 105, 145, 250), animation)
            );
        } else {
            Render2D.rect(
                    state.toggleX,
                    state.toggleY,
                    state.toggleWidth,
                    state.toggleHeight,
                    4,
                    context.color(185, 185, 185, 50),
                    context.color(185, 185, 185, 50),
                    context.color(185, 185, 185, 50),
                    context.color(185, 185, 185, 50)
            );
        }

        Render2D.outline(
                state.toggleX, state.toggleY,
                state.toggleWidth,
                state.toggleHeight,
                4,
                0.25f,
                context.color(255, 255, 255, 100),
                context.color(255, 255, 255, 100),
                context.color(255, 255, 255, 100),
                context.color(255, 255, 255, 100)
        );

        Render2D.rect(
                cardX + 128.0F + 6.0F * animation,
                cardY + 6.0F,
                9,
                9,
                3.5f,
                context.color(255, 255, 255, 255)
        );

        renderModuleStar(module, state, cardX + 112.0F, cardY + 6.0F);
    }

    private boolean dispatchOverlayClick(MouseButtonEvent event) {
        for (SettingCardComponent<?> component : settingComponents.values()) {
            if (component.mouseClickedOverlay(event, context)) {
                return true;
            }
        }
        return false;
    }

    private boolean dispatchSettingClick(MouseButtonEvent event, boolean doubled) {
        for (SettingCardComponent<?> component : settingComponents.values()) {
            if (!component.state().visible) {
                continue;
            }
            if (component.mouseClicked(event, doubled, context)) {
                return true;
            }
        }
        return false;
    }

    private boolean handleModuleClick(MouseButtonEvent event) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        for (ModuleState state : moduleStates.values()) {
            if (!state.visible || !context.inside(event.x(), event.y(), state.toggleX, state.toggleY, state.toggleWidth, state.toggleHeight)) {
                continue;
            }
            state.module.toggle();
            return true;
        }
        return false;
    }

    private boolean handleModuleStarClick(MouseButtonEvent event) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        for (ModuleState state : moduleStates.values()) {
            if (!state.visible || !context.inside(event.x(), event.y(), state.starX, state.starY, state.starWidth, state.starHeight)) {
                continue;
            }
            state.module.setStarred(!state.module.isStarred());
            state.starAnimation.setDirection(state.module.isStarred() ? Direction.FORWARDS : Direction.BACKWARDS);
            return true;
        }
        return false;
    }

    private boolean handleModuleBindClick(MouseButtonEvent event) {
        for (ModuleState state : moduleStates.values()) {
            if (!state.visible || !context.inside(event.x(), event.y(), state.cardX, state.cardY, state.cardWidth, Math.min(22.0F, state.cardHeight))) {
                continue;
            }
            listeningModuleBind = state.module;
            state.bindAnimation.setDirection(Direction.FORWARDS);
            context.listeningBind = null;
            context.focusedText = null;
            return true;
        }
        return false;
    }

    private void finishModuleBind(KeyBind bind) {
        Module module = listeningModuleBind;
        if (module == null) {
            return;
        }
        ModuleState state = moduleState(module);
        module.setBind(bind);
        state.bindAnimation.setDirection(bind != null && bind.isBound() ? Direction.FORWARDS : Direction.BACKWARDS);
        listeningModuleBind = null;
    }

    private void renderModuleBindIndicator(Module module, ModuleState state, float nameX, float nameY, float nameSize) {
        KeyBind bind = module.getBind();
        boolean listening = listeningModuleBind == module;
        boolean bound = bind != null && bind.isBound();

        boolean visible = listening || bound;
        state.bindAnimation.setDirection(visible ? Direction.FORWARDS : Direction.BACKWARDS);
        float progress = context.clamp(state.bindAnimation.getOutput().floatValue(), 0.0F, 1.0F);
        if (progress <= 0.01F) {
            return;
        }

        float nameWidth = Render2D.textWidth(FontType.SEMIBOLD, module.getName(), nameSize);
        float offsetX = (1.0F - progress) * -4.0F;
        float dotX = nameX + nameWidth + 3.0F + offsetX;
        float dotY = nameY + (nameSize - 1.5F) * 0.5F;
        int alpha = Math.round(255.0F * progress);
        int color = context.color(255, 255, 255, alpha);

        Render2D.rect(dotX, dotY, 3.0F, 3.0F, 1.5F, color);

        String bindText = bound && !listening ? MathUtils.shortBind(bind) : "...";
        Render2D.text(FontType.SEMIBOLD, bindText, dotX + 6.0F, nameY, nameSize, color);
    }

    private void renderModuleStar(Module module, ModuleState state, float x, float y) {
        state.starX = x - 2.0F;
        state.starY = y - 2.0F;
        state.starWidth = 14.0F;
        state.starHeight = 14.0F;
        state.starAnimation.setDirection(module.isStarred() ? Direction.FORWARDS : Direction.BACKWARDS);
        float progress = context.clamp(state.starAnimation.getOutput().floatValue(), 0.0F, 1.0F);
        int grey = context.color(128, 128, 128, 255);
        int topLeft = ColorUtil.lerpColor(grey, context.color(170, 118, 22, 255), progress);
        int topRight = ColorUtil.lerpColor(grey, context.color(255, 224, 92, 255), progress);
        int bottomRight = ColorUtil.lerpColor(grey, context.color(255, 181, 35, 255), progress);
        int bottomLeft = ColorUtil.lerpColor(grey, context.color(190, 132, 18, 255), progress);

        Render2D.text(
                FontType.MAINMENUSCREEN,
                "Y",
                x, y,
                10,
                topLeft,
                topRight,
                bottomRight,
                bottomLeft
        );
    }

    private void renderInnerCard(float x, float y, float width, float height) {
        Render2D.rect(
                x,
                y,
                width,
                height,
                6,
                context.color(125, 125, 125, 50),
                context.color(125, 125, 125, 50),
                context.color(125, 125, 125, 50),
                context.color(125, 125, 125, 50)
        );

        Render2D.outline(
                x,
                y,
                width,
                height,
                6,
                0.25f,
                context.color(255, 255, 255, 50),
                context.color(255, 255, 255, 50),
                context.color(255, 255, 255, 50),
                context.color(255, 255, 255, 50)
        );
    }

    private void updateModuleScope(ModuleCategory category, boolean searchMode, List<Module> modules) {
        if (!moduleListInitialized && activeCategory == null) {
            activeCategory = category;
            activeSearchMode = searchMode;
            displayedModules = new ArrayList<>(modules);
            restoreScrollScope(category, searchMode);
            completeListAnimation(categoryTransition, Direction.FORWARDS, CATEGORY_TRANSITION_MS);
            return;
        }

        boolean scopeChanged = activeSearchMode != searchMode || (!searchMode && activeCategory != category);
        if (scopeChanged) {
            pendingCategory = category;
            pendingSearchMode = searchMode;
            pendingModules = new ArrayList<>(modules);
            categoryTransition.setDirection(Direction.BACKWARDS);
            if (context.clamp(categoryTransition.getOutput().floatValue(), 0.0F, 1.0F) <= CATEGORY_SWITCH_THRESHOLD) {
                switchToPendingScope();
            }
            return;
        }

        displayedModules = new ArrayList<>(modules);
        categoryTransition.setDirection(Direction.FORWARDS);
    }

    private void switchToPendingScope() {
        saveScrollScope();
        context.activeColor = null;
        context.draggingColor = null;
        context.draggingColorPart = 0;
        context.draggingSlider = null;
        context.focusedText = null;
        context.listeningBind = null;
        listeningModuleBind = null;
        activeCategory = pendingCategory;
        activeSearchMode = pendingSearchMode;
        displayedModules = new ArrayList<>(pendingModules);
        pendingModules = List.of();
        lastRenderModules = List.of();
        restoreScrollScope(activeCategory, activeSearchMode);
        for (Module module : displayedModules) {
            ModuleState state = moduleState(module);
            completeListAnimation(state.entryAnimation, Direction.FORWARDS, MODULE_LIST_ANIMATION_MS);
            state.includedLastFrame = false;
            state.includedThisFrame = false;
            state.column = -1;
        }
        for (ModuleState state : moduleStates.values()) {
            if (!displayedModules.contains(state.module)) {
                state.includedLastFrame = false;
                state.includedThisFrame = false;
                state.visible = false;
                state.column = -1;
            }
        }
        categoryTransition.setDirection(Direction.FORWARDS);
    }

    private void restoreScrollScope(ModuleCategory category, boolean searchMode) {
        ScrollSnapshot snapshot = searchMode ? SEARCH_SCROLL : CATEGORY_SCROLLS.computeIfAbsent(category, ignored -> new ScrollSnapshot());
        moduleScroll = snapshot.current;
        moduleScrollTarget = snapshot.target;
    }

    private void saveScrollScope() {
        ScrollSnapshot snapshot = activeSearchMode ? SEARCH_SCROLL : activeCategory == null ? null : CATEGORY_SCROLLS.computeIfAbsent(activeCategory, ignored -> new ScrollSnapshot());
        if (snapshot == null) {
            return;
        }
        snapshot.current = moduleScroll;
        snapshot.target = moduleScrollTarget;
    }

    private void completeListAnimation(Decelerate animation, Direction direction) {
        completeListAnimation(animation, direction, MODULE_LIST_ANIMATION_MS);
    }

    private void completeListAnimation(Decelerate animation, Direction direction, int ms) {
        animation.setDirection(direction);
        animation.counter.setTime(System.currentTimeMillis() - ms - 1L);
    }

    private List<SettingCardComponent<?>> visibleComponents(Module module) {
        List<SettingCardComponent<?>> components = new ArrayList<>();
        for (Setting<?> setting : module.getSettings()) {
            SettingCardComponent<?> component = componentFor(setting);
            if (component != null && component.alive()) {
                components.add(component);
            }
        }
        components.sort(Comparator.comparingInt(SettingCardComponent::order));
        return components;
    }

    private SettingCardComponent<?> componentFor(Setting<?> setting) {
        SettingCardComponent<?> component = settingComponents.get(setting);
        if (component != null) {
            return component;
        }
        component = SettingCardFactory.create(setting);
        if (component != null) {
            settingComponents.put(setting, component);
        }
        return component;
    }

    private float measureSettings(List<SettingCardComponent<?>> settings) {
        float height = 0.0F;
        for (SettingCardComponent<?> component : settings) {
            height += component.animatedHeight(context);
        }
        return height;
    }

    private float measureLayoutSettings(List<SettingCardComponent<?>> settings) {
        float height = 0.0F;
        for (SettingCardComponent<?> component : settings) {
            height += component.animatedLayoutHeight(context);
        }
        return height;
    }

    private List<ModuleLayoutEntry> layoutEntries(List<Module> modules) {
        List<ModuleLayoutEntry> entries = new ArrayList<>();
        for (Module module : modules) {
            List<SettingCardComponent<?>> settings = visibleComponents(module);
            entries.add(new ModuleLayoutEntry(module, settings, moduleCardHeight(settings), moduleCardLayoutHeight(settings)));
        }
        entries.sort(Comparator
                .comparingInt((ModuleLayoutEntry entry) -> entry.module.isStarred() ? 0 : 1)
                .thenComparing(entry -> entry.module.getName(), String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private float measureModuleLayoutHeight(List<ModuleLayoutEntry> entries, float cardGap) {
        float leftHeight = 0.0F;
        float rightHeight = 0.0F;
        float leftLayoutHeight = 0.0F;
        float rightLayoutHeight = 0.0F;
        for (ModuleLayoutEntry entry : entries) {
            if (leftLayoutHeight <= rightLayoutHeight) {
                leftHeight += entry.height + cardGap;
                leftLayoutHeight += entry.layoutHeight + cardGap;
            } else {
                rightHeight += entry.height + cardGap;
                rightLayoutHeight += entry.layoutHeight + cardGap;
            }
        }
        float totalHeight = Math.max(leftHeight, rightHeight);
        return totalHeight <= 0.0F ? 0.0F : totalHeight - cardGap;
    }

    private float moduleCardHeight(List<SettingCardComponent<?>> settings) {
        if (settings.isEmpty()) {
            return 21.0F;
        }
        return Math.max(34.0F, 24.0F + measureSettings(settings) + 4.0F);
    }

    private float moduleCardLayoutHeight(List<SettingCardComponent<?>> settings) {
        if (settings.isEmpty()) {
            return 20.0F;
        }
        return Math.max(34.0F, 24.0F + measureLayoutSettings(settings) + 4.0F);
    }

    private ModuleState moduleState(Module module) {
        return moduleStates.computeIfAbsent(module, ModuleState::new);
    }

    private boolean modulesChanged(List<Module> modules) {
        if (modules.size() != lastRenderModules.size()) {
            return true;
        }
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i) != lastRenderModules.get(i)) {
                return true;
            }
        }
        return false;
    }

    private static final class ModuleState {
        private final Module module;
        private final Decelerate toggleAnimation;
        private final Decelerate entryAnimation;
        private final Decelerate bindAnimation;
        private final Decelerate starAnimation;
        private boolean visible;
        private boolean includedLastFrame;
        private boolean includedThisFrame;
        private boolean positionReady;
        private float cardX;
        private float cardY;
        private float cardWidth;
        private float cardHeight;
        private int column = -1;
        private float starX;
        private float starY;
        private float starWidth;
        private float starHeight;
        private float toggleX;
        private float toggleY;
        private float toggleWidth;
        private float toggleHeight;

        private ModuleState(Module module) {
            this.module = module;
            this.toggleAnimation = MathUtils.createAnimation(180, module.isEnabled() ? Direction.FORWARDS : Direction.BACKWARDS);
            this.entryAnimation = MathUtils.createAnimation(220, Direction.BACKWARDS);
            this.bindAnimation = MathUtils.createAnimation(180, module.getBind() != null && module.getBind().isBound() ? Direction.FORWARDS : Direction.BACKWARDS);
            this.starAnimation = MathUtils.createAnimation(220, module.isStarred() ? Direction.FORWARDS : Direction.BACKWARDS);
        }
    }

    private record ModuleLayoutEntry(Module module, List<SettingCardComponent<?>> settings, float height, float layoutHeight) {
    }

    private static final class ScrollSnapshot {
        private float current;
        private float target;
    }
}
