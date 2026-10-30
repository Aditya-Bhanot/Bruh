package releon.ru.api.events.impl;

import net.minecraft.world.entity.player.Input;
import releon.ru.api.events.CancellableEvent;

public class InputEvent extends CancellableEvent {
    private Input input;

    public InputEvent(Input input) {
        this.input = input;
    }

    public Input getInput() {
        return input;
    }

    public void setInput(Input input) {
        this.input = input;
    }

    public void setJumping(boolean jump) {
        input = new Input(input.forward(), input.backward(), input.left(), input.right(), jump, input.shift(), input.sprint());
    }

    public void setSprinting(boolean sprint) {
        input = new Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(), input.shift(), sprint);
    }

    public void setDirectional(boolean forward, boolean backward, boolean left, boolean right, boolean sneak, boolean sprint, boolean jump) {
        input = new Input(forward, backward, left, right, jump, sneak, sprint);
    }

    public void setDirectionalLow(boolean forward, boolean backward, boolean left, boolean right) {
        input = new Input(forward, backward, left, right, input.jump(), input.shift(), input.sprint());
    }

    public void inputNone() {
        input = new Input(false, false, false, false, false, false, false);
    }

    public int forward() {
        return input.forward() ? 1 : input.backward() ? -1 : 0;
    }

    public float sideways() {
        return input.left() ? 1 : input.right() ? -1 : 0;
    }
}
