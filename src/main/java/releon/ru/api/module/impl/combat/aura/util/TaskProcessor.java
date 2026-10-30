package releon.ru.api.module.impl.combat.aura.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public final class TaskProcessor<T> {
    private final List<Task<T>> activeTasks = new ArrayList<>();
    private int tickCounter;

    public void addTask(Task<T> task) {
        if (task != null) {
            activeTasks.add(task);
            activeTasks.sort(Comparator.comparingInt(Task<T>::priority).reversed());
        }
    }

    public T fetchActiveTaskValue() {
        return activeTasks.isEmpty() ? null : activeTasks.getFirst().value();
    }

    public int tickCounter() {
        return tickCounter;
    }

    public void tick(int ticks) {
        tickCounter += ticks;
        Iterator<Task<T>> iterator = activeTasks.iterator();
        while (iterator.hasNext()) {
            Task<T> task = iterator.next();
            task.age += ticks;
            if (task.age >= task.duration()) {
                iterator.remove();
            }
        }
        if (activeTasks.isEmpty()) {
            tickCounter = 0;
        }
    }

    public void clear() {
        activeTasks.clear();
        tickCounter = 0;
    }

    public static final class Task<T> {
        private final int duration;
        private final int priority;
        private final Object provider;
        private final T value;
        private int age;

        public Task(int duration, int priority, Object provider, T value) {
            this.duration = Math.max(1, duration);
            this.priority = priority;
            this.provider = provider;
            this.value = value;
        }

        public int duration() {
            return duration;
        }

        public int priority() {
            return priority;
        }

        public Object provider() {
            return provider;
        }

        public T value() {
            return value;
        }
    }
}
