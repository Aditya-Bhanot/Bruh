import re, shutil, sys
from pathlib import Path

gui = Path.home() / "Meow/Bruh/src/main/java/attrium/ru/screens/GlassClickGuiScreen.java"
src = gui.read_text(encoding="utf-8")

if "ATTRIUM_UPDATER_RENDER_START" in src:
    print("[OK] Already patched!")
    sys.exit(0)

if "import attrium.ru.manager.AutoUpdater;" not in src:
    src = re.sub(r"(package\s+attrium\.ru\.screens;\s*\n)", r"\1\nimport attrium.ru.manager.AutoUpdater;\n", src, count=1)

if "updCheckHoverAnim" not in src:
    src = re.sub(
        r"(public\s+class\s+GlassClickGuiScreen\s+extends\s+Screen\s*\{)",
        r"\1\n    private float updCheckHoverAnim = 0.0F;\n    private float updRestartHoverAnim = 0.0F;\n    private float updChangelogRevealAnim = 0.0F;\n    private float updPulsePhase = 0.0F;\n",
        src,
        count=1,
    )

render_block = """
            // === ATTRIUM_UPDATER_RENDER_START ===
            float updStep = Math.min(1.0F, Math.max(0.05F, dt * 14.0F));
            updPulsePhase += dt * 3.8F;
            boolean updReady = AutoUpdater.updateReady;
            String updStatus = AutoUpdater.getStatusText();
            String checkText = AutoUpdater.isChecking() ? updStatus : "Updates";
            Component checkComp = Component.literal(checkText).withStyle(nunitoRegularStyle);
            int checkW = Math.max(66, this.font.width(checkComp) + 14);
            int checkX = folderX - checkW - 8;
            boolean hoverCheck = isHovered(mouseX, mouseY, checkX, btnY, checkW, btnH);
            updCheckHoverAnim = Mth.lerp(updStep, updCheckHoverAnim, hoverCheck ? 1.0F : 0.0F);
            int checkBg = multiplyAlpha(lerpColor(0x24FFFFFF, 0x4800E5FF, updCheckHoverAnim), p0);
            int checkFg = multiplyAlpha(lerpColor(0xFFFFFFFF, 0xFF00E5FF, updCheckHoverAnim * 0.85F), p0);
            HudManager.drawRoundedRect(guiGraphics, checkX, btnY, checkW, btnH, 8, checkBg);
            int cw = this.font.width(checkComp);
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, checkComp, checkX + (checkW - cw) / 2, btnY + 9, checkFg, false);
            }
            updChangelogRevealAnim = Mth.lerp(updStep, updChangelogRevealAnim, updReady ? 1.0F : 0.0F);
            if (updReady) {
                Component restartComp = Component.literal("Restart").withStyle(nunitoRegularStyle);
                int restartW = Math.max(62, this.font.width(restartComp) + 14);
                int restartX = checkX - restartW - 8;
                boolean hoverRestart = isHovered(mouseX, mouseY, restartX, btnY, restartW, btnH);
                updRestartHoverAnim = Mth.lerp(updStep, updRestartHoverAnim, hoverRestart ? 1.0F : 0.0F);
                float pulse = 0.5F + 0.5F * (float) Math.sin(updPulsePhase);
                int baseGlow = lerpColor(0x3400E5FF, 0x6200E5FF, Math.max(pulse * 0.65F, updRestartHoverAnim));
                HudManager.drawRoundedRect(guiGraphics, restartX, btnY, restartW, btnH, 8, multiplyAlpha(baseGlow, p0));
                int rsw = this.font.width(restartComp);
                if ((whiteCol >>> 24) > 8) {
                    guiGraphics.drawString(this.font, restartComp, restartX + (restartW - rsw) / 2, btnY + 9, multiplyAlpha(0xFF00E5FF, p0), false);
                }
            }
            if (updChangelogRevealAnim > 0.04F) {
                java.util.List<String> changes = AutoUpdater.getChangelogLines();
                int lineCount = Math.min(3, changes.size());
                int cardH = 22 + lineCount * 11;
                int cardW = Math.min(fullW, 300);
                int cardX = leftCardX + fullW - cardW;
                int cardY = y0 + bannerH + 6 - Math.round(6.0F * (1.0F - updChangelogRevealAnim));
                float ca = p0 * updChangelogRevealAnim;
                HudManager.drawRoundedRect(guiGraphics, cardX, cardY, cardW, cardH, 10, multiplyAlpha(0xEC10141F, ca));
                Component hdr = Component.literal("What's New in v" + AutoUpdater.getLatestVersion()).withStyle(nunitoRegularStyle);
                if (((int) (255 * ca)) > 10) {
                    guiGraphics.drawString(this.font, hdr, cardX + 10, cardY + 6, multiplyAlpha(0xFF00E5FF, ca), false);
                    for (int ci = 0; ci < lineCount; ci++) {
                        Component lineComp = Component.literal("• " + changes.get(ci)).withStyle(nunitoRegularStyle);
                        guiGraphics.drawString(this.font, lineComp, cardX + 10, cardY + 19 + ci * 11, multiplyAlpha(0xFFE5E7EB, ca), false);
                    }
                }
            }
            // === ATTRIUM_UPDATER_RENDER_END ===
"""

click_block = """
        // === ATTRIUM_UPDATER_CLICK_START ===
        String checkTextClick = AutoUpdater.isChecking() ? AutoUpdater.getStatusText() : "Updates";
        int checkW = Math.max(66, this.font.width(Component.literal(checkTextClick).withStyle(nunitoRegularStyle)) + 14);
        int checkX = folderX - checkW - 8;
        if (isHovered(mouseX, mouseY, checkX, btnY, checkW, btnH)) {
            AutoUpdater.checkForUpdatesAsync(true);
            SoundUtil.playClick();
            return;
        }
        if (AutoUpdater.updateReady) {
            int restartW = Math.max(62, this.font.width(Component.literal("Restart").withStyle(nunitoRegularStyle)) + 14);
            int restartX = checkX - restartW - 8;
            if (isHovered(mouseX, mouseY, restartX, btnY, restartW, btnH)) {
                SoundUtil.playClick();
                AutoUpdater.restartToApply();
                return;
            }
        }
        // === ATTRIUM_UPDATER_CLICK_END ===
"""

lines = src.splitlines(keepends=True)
r_idx = next((j + 1 for i, l in enumerate(lines) if "bentoResetLayoutComp" in l and "drawString" in l for j in range(i + 1, min(i + 5, len(lines))) if lines[j].strip() == "}"), -1)
lines.insert(r_idx, render_block + "\n")
c_idx = next((j + 1 for i, l in enumerate(lines) if "ClientThemeManager.resetLayout()" in l for j in range(i + 1, min(i + 8, len(lines))) if lines[j].strip() == "}"), -1)
lines.insert(c_idx, click_block + "\n")

shutil.copy2(gui, gui.with_suffix(".java.bak"))
gui.write_text("".join(lines), encoding="utf-8")
print(f"[PATCHED] GlassClickGuiScreen.java (render@line {r_idx+1}, click@line {c_idx+1})")
