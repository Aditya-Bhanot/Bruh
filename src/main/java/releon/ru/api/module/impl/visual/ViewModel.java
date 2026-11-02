package releon.ru.api.module.impl.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.HandOffsetEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.NumberSetting;

public class ViewModel extends Module {
    private final NumberSetting mainHandX = register(new NumberSetting("Main Hand X", "Main hand X offset.", 0.0, -1.0, 1.0, 0.05));
    private final NumberSetting mainHandY = register(new NumberSetting("Main Hand Y", "Main hand Y offset.", 0.0, -1.0, 1.0, 0.05));
    private final NumberSetting mainHandZ = register(new NumberSetting("Main Hand Z", "Main hand Z offset.", 0.0, -2.5, 2.5, 0.05));
    private final NumberSetting offHandX = register(new NumberSetting("Off Hand X", "Off hand X offset.", 0.0, -1.0, 1.0, 0.05));
    private final NumberSetting offHandY = register(new NumberSetting("Off Hand Y", "Off hand Y offset.", 0.0, -1.0, 1.0, 0.05));
    private final NumberSetting offHandZ = register(new NumberSetting("Off Hand Z", "Off hand Z offset.", 0.0, -2.5, 2.5, 0.05));

    public ViewModel() {
        super("View Model", "Changes first-person hand and item offsets.", ModuleCategory.VISUAL);
    }

    @SubscribeEvent
    private void onHandOffset(HandOffsetEvent event) {
        if (event.getHand() == InteractionHand.MAIN_HAND && event.getStack().getItem() instanceof CrossbowItem) {
            return;
        }

        PoseStack matrices = event.getMatrices();
        if (event.getHand() == InteractionHand.MAIN_HAND) {
            matrices.translate(mainHandX.getValue(), mainHandY.getValue(), mainHandZ.getValue());
        } else {
            matrices.translate(offHandX.getValue(), offHandY.getValue(), offHandZ.getValue());
        }
    }
}
