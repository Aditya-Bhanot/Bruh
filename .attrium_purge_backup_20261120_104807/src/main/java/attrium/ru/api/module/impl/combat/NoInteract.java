package attrium.ru.api.module.impl.combat;

import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;

public class NoInteract extends Module {
    public NoInteract() {
        super("No Interact", "Blocks unwanted interactions.", ModuleCategory.COMBAT);
    }
}
