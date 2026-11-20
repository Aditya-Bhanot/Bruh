package attrium.ru.api.module;

@FunctionalInterface
public interface ModuleStateListener {
    void onChanged(Module module);
}
