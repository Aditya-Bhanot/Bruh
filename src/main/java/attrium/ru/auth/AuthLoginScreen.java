package attrium.ru.auth;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import attrium.ru.manager.ClientThemeManager;
import attrium.ru.manager.HudManager;

/**
 * Attrium Fail-Closed Frosted Glass Modal Login Screen (Step 3, Fabric 1.21.11).
 */
public class AuthLoginScreen extends Screen {
    private EditBox usernameBox;
    private EditBox passwordBox;
    private Button signInButton;
    private Button copyHwidButton;
    private Button retryButton;

    private boolean submitting = false;
    private long copyConfirmUntilMs = 0L;
    private long lockoutExitAtMs = 0L;

    public AuthLoginScreen() {
        super(Component.literal("Attrium Authentication"));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    protected void init() {
        super.init();
        int cardW = 248;
        int cardH = 188;
        int cx = (this.width - cardW) / 2;
        int cy = (this.height - cardH) / 2;
        int fieldW = cardW - 36;
        int fx = cx + 18;

        this.usernameBox = new EditBox(this.font, fx, cy + 54, fieldW, 20, Component.literal("Username"));
        this.usernameBox.setMaxLength(48);
        this.usernameBox.setHint(Component.literal("Username"));
        this.addRenderableWidget(this.usernameBox);

        this.passwordBox = new EditBox(this.font, fx, cy + 84, fieldW, 20, Component.literal("Password"));
        this.passwordBox.setMaxLength(64);
        this.passwordBox.setHint(Component.literal("Password"));
        applyPasswordMask(this.passwordBox);
        this.addRenderableWidget(this.passwordBox);

        this.signInButton = Button.builder(Component.literal("Sign In"), btn -> triggerSignIn())
                .bounds(fx, cy + 114, fieldW, 20)
                .build();
        this.addRenderableWidget(this.signInButton);

        int halfW = (fieldW - 6) / 2;
        this.copyHwidButton = Button.builder(Component.literal("Copy Device ID"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) {
                mc.keyboardHandler.setClipboard(DeviceFingerprint.getFingerprint());
                copyConfirmUntilMs = System.currentTimeMillis() + 2500L;
            }
        }).bounds(fx, cy + 140, halfW, 20).build();
        this.addRenderableWidget(this.copyHwidButton);

        this.retryButton = Button.builder(Component.literal("Retry Sync"), btn -> {
            if (!submitting) {
                AuthService.retryBootstrapAsync();
            }
        }).bounds(fx + halfW + 6, cy + 140, fieldW - halfW - 6, 20).build();
        this.addRenderableWidget(this.retryButton);

        this.setInitialFocus(this.usernameBox);
    }

    private void triggerSignIn() {
        if (submitting || AuthService.getStatus() == AuthService.Status.LOCKED_OUT) {
            return;
        }
        submitting = true;
        if (signInButton != null) {
            signInButton.active = false;
        }
        String u = usernameBox != null ? usernameBox.getValue() : "";
        String p = passwordBox != null ? passwordBox.getValue() : "";

        AuthService.submitLoginAsync(u, p).thenAccept(ok -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                submitting = false;
                if (signInButton != null) {
                    signInButton.active = true;
                }
                if (Boolean.TRUE.equals(ok) && AuthService.isAuthorized()) {
                    mc.setScreen(null);
                } else if (AuthService.getStatus() == AuthService.Status.LOCKED_OUT) {
                    lockoutExitAtMs = System.currentTimeMillis() + 1200L;
                }
            });
        });
    }

    @Override
    public void tick() {
        super.tick();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (AuthService.isAuthorized()) {
            mc.setScreen(null);
            return;
        }
        if (AuthService.getStatus() == AuthService.Status.LOCKED_OUT) {
            if (lockoutExitAtMs == 0L) {
                lockoutExitAtMs = System.currentTimeMillis() + 1200L;
            } else if (System.currentTimeMillis() >= lockoutExitAtMs) {
                mc.stop();
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC090C12);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int cardW = 248;
        int cardH = 188;
        int cx = (this.width - cardW) / 2;
        int cy = (this.height - cardH) / 2;
        int accent = ClientThemeManager.getAccentArgb();

        HudManager.drawGlassCard(guiGraphics, cx, cy, cardW, cardH, 12, 0xC8141924, 1.0F);
        HudManager.drawRoundedRect(guiGraphics, cx + 18, cy + 38, cardW - 36, 1, 0, 0x22FFFFFF);

        Component titleComp = Component.literal("Attrium Access").withStyle(HudManager.getBoldStyle());
        int tw = this.font.width(titleComp);
        guiGraphics.drawString(this.font, titleComp, cx + (cardW - tw) / 2, cy + 14, 0xFFFFFFFF, false);

        String sub = (System.currentTimeMillis() < copyConfirmUntilMs)
                ? "Device ID copied to clipboard!"
                : AuthService.getStatusMessage();
        int subCol = (System.currentTimeMillis() < copyConfirmUntilMs)
                ? 0xFF22E56B
                : (AuthService.getStatus() == AuthService.Status.LOCKED_OUT || AuthService.getStatus() == AuthService.Status.NETWORK_ERROR
                ? 0xFFF43F5E
                : accent);
        Component subComp = Component.literal(sub != null ? sub : "").withStyle(HudManager.getRegularStyle());
        int sw = this.font.width(subComp);
        guiGraphics.drawString(this.font, subComp, cx + (cardW - sw) / 2, cy + 26, subCol, false);

        String fp = DeviceFingerprint.getFingerprint();
        String fpShort = "ID: " + (fp.length() >= 12 ? fp.substring(0, 12) + "..." : fp);
        Component fpComp = Component.literal(fpShort).withStyle(HudManager.getRegularStyle());
        int fw = this.font.width(fpComp);
        guiGraphics.drawString(this.font, fpComp, cx + (cardW - fw) / 2, cy + 168, 0xFF8B94A5, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private static void applyPasswordMask(EditBox box) {
        if (box == null) return;
        java.util.function.BiFunction<String, Integer, FormattedCharSequence> mask =
                (text, firstIdx) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY);
        for (String name : new String[]{"addFormatter", "setFormatter"}) {
            try {
                java.lang.reflect.Method m = EditBox.class.getMethod(name, java.util.function.BiFunction.class);
                m.invoke(box, mask);
                return;
            } catch (Throwable ignored) {
            }
        }
    }
}
