package releon.ru.api.module.impl.visual;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.MultiModeSetting;

public class NoRender extends Module {
    private static NoRender instance;

    private final MultiModeSetting elements = register(new MultiModeSetting("Elements", "Render elements to hide.",
            new String[]{
                    "Fire", "Lava", "Hit Particles", "Bad Effects", "Block Overlay", "Plants", "Sky Background",
                    "Fog", "Darkness", "Damage", "Nausea", "Scoreboard", "Bossbar", "Wither Hearts", "Totem Animation"
            },
            "Fire", "Bad Effects", "Block Overlay", "Darkness", "Damage", "Nausea", "Wither Hearts", "Totem Animation"
    ));

    private boolean plantsReloadState;

    public NoRender() {
        super("No Render", "Hides selected visual effects.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static NoRender getInstance() {
        return instance;
    }

    public static boolean isActive(String element) {
        return instance != null && instance.isEnabled() && instance.elements.isSelected(element);
    }

    @Override
    protected void onEnable() {
        plantsReloadState = shouldHidePlants();
        if (plantsReloadState) {
            reloadWorldRenderer();
        }
    }

    @Override
    protected void onDisable() {
        boolean hadPlantsHidden = plantsReloadState;
        plantsReloadState = false;
        if (hadPlantsHidden) {
            reloadWorldRenderer();
        }
    }

    @Override
    public void onTick(net.minecraft.client.Minecraft client) {
        boolean hidePlants = shouldHidePlants();
        if (hidePlants != plantsReloadState) {
            plantsReloadState = hidePlants;
            reloadWorldRenderer();
        }
    }

    public boolean shouldHidePlants() {
        return isEnabled() && elements.isSelected("Plants");
    }

    public boolean shouldHideSkyBackground() {
        return isEnabled() && elements.isSelected("Sky Background");
    }

    public boolean shouldHideHitParticles() {
        return isEnabled() && elements.isSelected("Hit Particles");
    }

    public boolean isPlantState(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.getBlock() instanceof VegetationBlock
                || state.getBlock() instanceof BushBlock
                || state.getBlock() instanceof GrowingPlantBodyBlock
                || state.getBlock() instanceof CropBlock
                || state.getBlock() instanceof SaplingBlock
                || state.getBlock() instanceof MushroomBlock
                || state.getBlock() instanceof SweetBerryBushBlock
                || state.getBlock() instanceof TallGrassBlock
                || state.getBlock() instanceof DoublePlantBlock
                || state.getBlock() instanceof FlowerBlock
                || state.getBlock() instanceof FlowerPotBlock
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS);
    }

    private void reloadWorldRenderer() {
        if (mc.level != null && mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }
}
