#!/usr/bin/env python3
"""
Upgrades AutoMace Spear 2-Pop in MaceTarget.java to be:
- Fully dynamic (velocity-scaled 2 -> 1 -> 0 ticks, removes manual delay selector)
- 100% health-bar independent (works on AntiHealthIndicator / spoofed-HP servers)
- High-ping ground-drop compatible (ping-scaled 4-10 tick ground grace window + fall-dist snapshot)
- Guaranteed Mace follow-up with zero GrimAC flags (strict 3.0m Mace reach, 1-tick STAB/release separation,
  catch-up tick guard, and single-C09-per-tick verification)
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import List, Tuple


def detect_newline(raw: bytes) -> str:
    return "\r\n" if b"\r\n" in raw else "\n"


def apply_exact_patch(
    text: str, label: str, old: str, new: str, already_marker: str
) -> Tuple[str, str]:
    if already_marker in text:
        return text, f"  [SKIP] {label} (already applied)"
    if old not in text:
        return text, f"  [WARN] {label} (pattern not found)"
    return text.replace(old, new, 1), f"  [OK]   {label}"


def apply_regex_patch(
    text: str, label: str, pattern: str, replacement: str, already_marker: str
) -> Tuple[str, str]:
    if already_marker and already_marker in text:
        return text, f"  [SKIP] {label} (already applied)"
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE | re.DOTALL)
    if count == 0:
        return text, f"  [WARN] {label} (pattern not found)"
    return updated, f"  [OK]   {label}"


def apply_method_replacement(
    text: str,
    label: str,
    method_signature_regex: str,
    replacement: str,
    already_marker: str,
) -> Tuple[str, str]:
    if already_marker in text:
        return text, f"  [SKIP] {label} (already applied)"

    match = re.search(method_signature_regex, text, flags=re.MULTILINE)
    if not match:
        return text, f"  [WARN] {label} (method signature not found)"

    start = match.start()
    brace_open = text.find("{", match.end() - 1)
    if brace_open == -1:
        return text, f"  [WARN] {label} (opening brace not found)"

    depth = 0
    in_string = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    i = brace_open
    n = len(text)

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
        elif in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                i += 1
        elif in_string:
            if ch == "\\" and i + 1 < n:
                i += 1
            elif ch == '"':
                in_string = False
        elif in_char:
            if ch == "\\" and i + 1 < n:
                i += 1
            elif ch == "'":
                in_char = False
        else:
            if ch == "/" and nxt == "/":
                in_line_comment = True
                i += 1
            elif ch == "/" and nxt == "*":
                in_block_comment = True
                i += 1
            elif ch == '"':
                in_string = True
            elif ch == "'":
                in_char = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[:start] + replacement + text[i + 1 :], f"  [OK]   {label}"
        i += 1

    return text, f"  [WARN] {label} (unbalanced braces)"


def ensure_import(text: str, imp: str) -> Tuple[str, str]:
    line = f"import {imp};"
    if line in text:
        return text, f"  [SKIP] Import {imp} (already present)"
    anchor = "import net.minecraft.client.Minecraft;"
    if anchor not in text:
        return text, f"  [WARN] Import {imp} (anchor not found)"
    return text.replace(anchor, anchor + "\n" + line, 1), f"  [OK]   Import {imp}"


def patch_mace_target(text: str) -> Tuple[str, List[str]]:
    logs: List[str] = []

    # 1. Ensure all required imports exist
    for imp in (
        "net.minecraft.client.multiplayer.PlayerInfo",
        "net.minecraft.core.Direction",
        "net.minecraft.core.registries.BuiltInRegistries",
        "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket",
        "java.util.Locale",
    ):
        text, msg = ensure_import(text, imp)
        logs.append(msg)

    # 2. Add canDispatchGuaranteedFollowup() to ActionManager (no random CPS drop, zero same-tick double attack)
    text, msg = apply_exact_patch(
        text,
        "Add ActionManager.canDispatchGuaranteedFollowup() (no CPS drop, no double-attack/Post)",
        old="        public synchronized void recordInteractionDispatched(Minecraft client) {",
        new=(
            "        public synchronized boolean canDispatchGuaranteedFollowup(Minecraft client, ActionPriority priority) {\n"
            "            if (client == null || client.player == null || client.screen != null) {\n"
            "                return false;\n"
            "            }\n"
            "            if (client.player.isUsingItem() || isBlockedByHigherPriority(priority)) {\n"
            "                return false;\n"
            "            }\n"
            "            if (catchUpTickActive || clientTickCounter == lastInteractionTick) {\n"
            "                return false;\n"
            "            }\n"
            "            return true;\n"
            "        }\n\n"
            "        public synchronized void recordInteractionDispatched(Minecraft client) {"
        ),
        already_marker="public synchronized boolean canDispatchGuaranteedFollowup(",
    )
    logs.append(msg)

    # 3. Restore strict 3.0m reach cap on verifyStrict3mAttackRaycast() & add dedicated verifySpearJabRaycast()
    text, msg = apply_method_replacement(
        text,
        "Lock verifyStrict3mAttackRaycast() strictly to 3.0m & add dedicated verifySpearJabRaycast() (4.3m)",
        r"[ \t]*public static boolean verifyStrict3mAttackRaycast\(\s*Minecraft client,\s*LivingEntity target,\s*double requestedReach,\s*Vec3 customLookVec\s*\)\s*\{",
        replacement="""        public static boolean verifyStrict3mAttackRaycast(
                Minecraft client,
                LivingEntity target,
                double requestedReach,
                Vec3 customLookVec
        ) {
            if (client == null || client.player == null || client.level == null || target == null || !target.isAlive()) {
                return false;
            }
            // Strict vanilla 3.0m reach cap for Mace attacks (prevents GrimAC 3.01 Reach flags)
            double legalReach = Math.min(3.0D, Math.max(1.0D, requestedReach));
            Vec3 eyePos = client.player.getEyePosition(1.0F);
            Vec3 lookVec = customLookVec != null ? customLookVec.normalize() : client.player.getViewVector(1.0F);
            Vec3 rayEnd = eyePos.add(lookVec.scale(legalReach));

            AABB targetBox = target.getBoundingBox();
            if (targetBox.contains(eyePos)) {
                return true;
            }

            Optional<Vec3> hitOpt = targetBox.clip(eyePos, rayEnd);
            if (hitOpt.isEmpty()) {
                return false;
            }

            Vec3 hitPoint = hitOpt.get();
            if (eyePos.distanceToSqr(hitPoint) > (legalReach * legalReach)) {
                return false;
            }

            BlockHitResult blockHit = client.level.clip(new ClipContext(
                    eyePos,
                    hitPoint,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    client.player
            ));
            return blockHit.getType() == HitResult.Type.MISS;
        }

        /**
         * Dedicated raycast for Minecraft 1.21.11 Spear attacks (Action.STAB / Charge, up to 4.3m reach).
         */
        public static boolean verifySpearJabRaycast(
                Minecraft client,
                LivingEntity target,
                double requestedReach,
                Vec3 customLookVec
        ) {
            if (client == null || client.player == null || client.level == null || target == null || !target.isAlive()) {
                return false;
            }
            double legalReach = Math.min(4.3D, Math.max(2.0D, requestedReach));
            Vec3 eyePos = client.player.getEyePosition(1.0F);
            Vec3 lookVec = customLookVec != null ? customLookVec.normalize() : client.player.getViewVector(1.0F);
            Vec3 rayEnd = eyePos.add(lookVec.scale(legalReach));

            AABB targetBox = target.getBoundingBox();
            if (targetBox.contains(eyePos)) {
                return true;
            }

            Optional<Vec3> hitOpt = targetBox.clip(eyePos, rayEnd);
            if (hitOpt.isEmpty()) {
                return false;
            }

            Vec3 hitPoint = hitOpt.get();
            if (eyePos.distanceToSqr(hitPoint) > (legalReach * legalReach)) {
                return false;
            }

            BlockHitResult blockHit = client.level.clip(new ClipContext(
                    eyePos,
                    hitPoint,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    client.player
            ));
            return blockHit.getType() == HitResult.Type.MISS;
        }""",
        already_marker="public static boolean verifySpearJabRaycast(",
    )
    logs.append(msg)

    # 4. Update CombatStateManager.checkStaleMaceLock() to preserve lock during high-ping ground grace
    text, msg = apply_method_replacement(
        text,
        "Preserve AUTO_MACE_STRIKE lock across high-ping touchdown in CombatStateManager.checkStaleMaceLock()",
        r"[ \t]*public static void checkStaleMaceLock\(\)\s*\{",
        replacement="""        public static void checkStaleMaceLock() {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || !mc.player.isAlive()) {
                if (ActionManager.getInstance().getActiveLockPriority() == ActionManager.ActionPriority.AUTO_MACE_STRIKE) {
                    ActionManager.getInstance().forceReleaseAllLocks();
                }
                lockAcquiredTick = -100;
                return;
            }
            boolean inSpearGrace = MaceTarget.isInActiveSpearTwoPopGrace(mc);
            if (mc.player.onGround() && !inSpearGrace) {
                if (ActionManager.getInstance().getActiveLockPriority() == ActionManager.ActionPriority.AUTO_MACE_STRIKE) {
                    ActionManager.getInstance().forceReleaseAllLocks();
                }
                lockAcquiredTick = -100;
                return;
            }
            int maxTicks = inSpearGrace ? 16 : 8;
            if (lockAcquiredTick != -100 && (mc.player.tickCount - lockAcquiredTick) > maxTicks) {
                ActionManager.getInstance().forceReleaseAllLocks();
                lockAcquiredTick = -100;
            }
        }""",
        already_marker="boolean inSpearGrace = MaceTarget.isInActiveSpearTwoPopGrace(mc);",
    )
    logs.append(msg)

    # 5. Remove static spearTwoPopDelay ModeSetting & update spearTwoPop description to Dynamic
    if "private final ModeSetting spearTwoPopDelay" in text:
        text, msg = apply_regex_patch(
            text,
            "Remove static spearTwoPopDelay ModeSetting (now 100% dynamic)",
            pattern=r"\n[ \t]*private final ModeSetting spearTwoPopDelay = register\(new ModeSetting\([^;]+?\)\);",
            replacement="",
            already_marker="",
        )
        logs.append(msg)
    else:
        logs.append("  [SKIP] Static spearTwoPopDelay ModeSetting (already removed)")

    if "spearTwoPopDelay.visibleWhen(" in text:
        text, msg = apply_regex_patch(
            text,
            "Remove spearTwoPopDelay.visibleWhen() line from constructor",
            pattern=r"\n[ \t]*spearTwoPopDelay\.visibleWhen\([^;]+?\);",
            replacement="",
            already_marker="",
        )
        logs.append(msg)

    if "private final BooleanSetting spearTwoPop" not in text:
        text, msg = apply_exact_patch(
            text,
            "Register dynamic 'Spear 2-Pop' BooleanSetting",
            old=(
                '    private final BooleanSetting betaAimMace = register(new BooleanSetting(\n'
                '            "Beta Aim & Mace",\n'
                '            "Angle-based chest/head elastic aim, Breach (<3m) vs Density+WindBurst (>=3m) switching, <40 deg Elytra cancel, and 1s pre-hit aim lock.",\n'
                '            false));'
            ),
            new=(
                '    private final BooleanSetting betaAimMace = register(new BooleanSetting(\n'
                '            "Beta Aim & Mace",\n'
                '            "Angle-based chest/head elastic aim, Breach (<3m) vs Density+WindBurst (>=3m) switching, <40 deg Elytra cancel, and 1s pre-hit aim lock.",\n'
                '            false));\n'
                '    private final BooleanSetting spearTwoPop = register(new BooleanSetting(\n'
                '            "Spear 2-Pop",\n'
                '            "Dynamic velocity-scaled Spear -> Mace follow-up (health-independent, high-ping ground drop support, GrimAC-safe).",\n'
                '            false));'
            ),
            already_marker="private final BooleanSetting spearTwoPop =",
        )
        logs.append(msg)
    else:
        text, msg = apply_regex_patch(
            text,
            "Update 'Spear 2-Pop' description to dynamic mode",
            pattern=r'private final BooleanSetting spearTwoPop = register\(new BooleanSetting\(\s*"Spear 2-Pop",\s*"[^"]*",\s*false\)\);',
            replacement=(
                'private final BooleanSetting spearTwoPop = register(new BooleanSetting(\n'
                '            "Spear 2-Pop",\n'
                '            "Dynamic velocity-scaled Spear -> Mace follow-up (health-independent, high-ping ground drop support, GrimAC-safe).",\n'
                '            false));'
            ),
            already_marker="Dynamic velocity-scaled Spear -> Mace follow-up",
        )
        logs.append(msg)

    # 6. Ensure STEP_SPEAR_2POP_WAIT_MACE exists in SmashSequenceState
    text, msg = apply_exact_patch(
        text,
        "Ensure STEP_SPEAR_2POP_WAIT_MACE exists in SmashSequenceState",
        old=(
            "        STEP_2_SELECT_MACE_AFTER_STUN,\n"
            "        STEP_1_MACE_SMASH_ATTACK,"
        ),
        new=(
            "        STEP_2_SELECT_MACE_AFTER_STUN,\n"
            "        STEP_SPEAR_2POP_WAIT_MACE,\n"
            "        STEP_1_MACE_SMASH_ATTACK,"
        ),
        already_marker="STEP_SPEAR_2POP_WAIT_MACE,",
    )
    logs.append(msg)

    # 7. Ensure all dynamic Spear 2-Pop fields exist without duplicates
    needed_fields = [
        ("spearTwoPopReadyTick", "    private int spearTwoPopReadyTick = -100;"),
        ("spearTrackTargetId", "    private int spearTrackTargetId = -1;"),
        ("spearTrackPrevHurtTime", "    private int spearTrackPrevHurtTime = 0;"),
        ("lastSpearAutoPopTick", "    private int lastSpearAutoPopTick = -100;"),
        ("spearSuppressKeyUseUntilTick", "    private int spearSuppressKeyUseUntilTick = -100;"),
        ("spearTwoPopPeakFallDist", "    private double spearTwoPopPeakFallDist = 0.0D;"),
        ("spearWasChargingLastTick", "    private boolean spearWasChargingLastTick = false;"),
        ("spearChargeTicks", "    private int spearChargeTicks = 0;"),
        ("lastAirborneDropTick", "    private int lastAirborneDropTick = -100;"),
        ("lastAirbornePeakFallDist", "    private double lastAirbornePeakFallDist = 0.0D;"),
    ]
    missing_decls = [decl for name, decl in needed_fields if f" {name} =" not in text]
    if missing_decls:
        text, msg = apply_exact_patch(
            text,
            "Add dynamic Spear 2-Pop & high-ping ground grace fields",
            old="    private int betaEnemyVelTick = -100;",
            new="    private int betaEnemyVelTick = -100;\n" + "\n".join(missing_decls),
            already_marker="private double lastAirbornePeakFallDist = 0.0D;",
        )
        logs.append(msg)
    else:
        logs.append("  [SKIP] Dynamic Spear 2-Pop fields (already present)")

    # 8. Upgrade START_CLIENT_TICK onGround() block for high-ping Spear -> Mace drops
    text, msg = apply_regex_patch(
        text,
        "Enable ping-scaled ground grace for Spear 2-Pop inside START_CLIENT_TICK onGround()",
        pattern=(
            r"[ \t]*if \(client\.player\.onGround\(\)\) \{\n"
            r"[ \t]*groundedAfterDiveTicks\+\+;\n"
            r".*?"
            r"[ \t]*\} else \{\n"
            r"[ \t]*groundedAfterDiveTicks = 0;\n"
            r"[ \t]*\}"
        ),
        replacement="""            if (client.player.onGround()) {
                groundedAfterDiveTicks++;
                int spearGroundGrace = getSpearTwoPopGroundGraceTicks(client);
                if (smashState == SmashSequenceState.STEP_SPEAR_2POP_WAIT_MACE && groundedAfterDiveTicks <= spearGroundGrace) {
                    processSmashSequenceStateMachine(client);
                    return;
                }
                // High-ping landing Spear 2-Pop: catch a Spear hit registering right as the player touches down
                if (spearTwoPop.getValue()
                        && smashState == SmashSequenceState.IDLE
                        && groundedAfterDiveTicks <= spearGroundGrace
                        && isSpearStack(client.player.getMainHandItem())
                        && wasRecentlyDroppingForSpear2Pop()) {
                    if (target == null || !isEntityAllowedByTargetSelection(client, target)) {
                        findTargetFromPreFilteredCache(client);
                    }
                    if (target != null && isEntityAllowedByTargetSelection(client, target) && !isWearingElytra(client)) {
                        double gDist = getPrecisionDistanceToTarget(client, target);
                        boolean hitOnLanding = detectSpearHitOnTarget(client, target, gDist);
                        if (hitOnLanding && cooldownTimer.finished(cooldownMs.getValue())) {
                            boolean needsSep = client.player.isUsingItem();
                            beginSpearTwoPopMaceFollowup(client, target, needsSep);
                            return;
                        }
                    }
                }
                if (isInMidOrPostUnequipSmash(client) && groundedAfterDiveTicks <= 3) {
                    if (smashState != SmashSequenceState.IDLE) {
                        processSmashSequenceStateMachine(client);
                        return;
                    }
                    if (target == null || !isEntityAllowedByTargetSelection(client, target)) {
                        findTargetFromPreFilteredCache(client);
                    }
                    if (target != null && isEntityAllowedByTargetSelection(client, target) && !isWearingElytra(client)
                            && !ActionManager.getInstance().isSwapInProgress()
                            && getPrecisionDistanceToTarget(client, target) <= swingRange.getValue()
                            && cooldownTimer.finished(cooldownMs.getValue())) {
                        initiateSmashSequence(client, target);
                        return;
                    }
                    if (groundedAfterDiveTicks <= 2) {
                        return;
                    }
                }
                if (smashState != SmashSequenceState.IDLE && ActionManager.getInstance().getClientTick() > stateCommittedTick) {
                    executeSafeLegitSwapBack(client);
                    smashState = SmashSequenceState.IDLE;
                }
                postUnequipSmashEndTick = -100;
                lastActiveGlideDiveTick = -100;
                spearTwoPopPeakFallDist = 0.0D;
                CombatStateManager.releaseMaceSmashLock();
                releaseCameraAndClearTargetLock();
                return;
            } else {
                groundedAfterDiveTicks = 0;
            }""",
        already_marker="int spearGroundGrace = getSpearTwoPopGroundGraceTicks(client);",
    )
    logs.append(msg)

    # 9. Update Spear detection block inside START_CLIENT_TICK (handles charge release & direct drop jab)
    text, msg = apply_regex_patch(
        text,
        "Upgrade START_CLIENT_TICK Spear 2-Pop trigger to health-independent dynamic engine",
        pattern=(
            r"[ \t]*if \(spearTwoPop\.getValue\(\) && isSpearStack\(client\.player\.getMainHandItem\(\)\)\) \{\n"
            r".*?"
            r"[ \t]*\} else \{\n"
            r"[ \t]*resetSpearHitTracker\(\);\n"
            r"[ \t]*\}"
        ),
        replacement="""            if (spearTwoPop.getValue() && isSpearStack(client.player.getMainHandItem())) {
                updateBetaEnemyVelocityOncePerTick(client, target);
                boolean spearHitLanded = detectSpearHitOnTarget(client, target, distToTarget);
                if (spearHitLanded && isFallingForSmash(client) && cooldownTimer.finished(cooldownMs.getValue())) {
                    // Enforce 1-tick separation if ending an active Spear charge (zero NoSlow/BadPackets flags)
                    boolean needsSep = client.player.isUsingItem();
                    beginSpearTwoPopMaceFollowup(client, target, needsSep);
                    return;
                }
                // While charging a Spear, wait for charge contact instead of swapping to Mace early
                if (client.player.isUsingItem()) {
                    return;
                }
                // Direct drop Spear jab (2.0m-4.25m): fire Action.STAB first, then Mace after dynamic 1-2 tick delay
                if (!ActionManager.getInstance().isSwapInProgress()
                        && isFallingForSmash(client)
                        && distToTarget >= 2.0D && distToTarget <= 4.25D
                        && cooldownTimer.finished(cooldownMs.getValue())) {
                    if (tryFireSpearJabOpeningHit(client, target)) {
                        beginSpearTwoPopMaceFollowup(client, target, true);
                        return;
                    }
                }
            } else {
                resetSpearHitTracker();
            }""",
        already_marker="Enforce 1-tick separation if ending an active Spear charge",
    )
    logs.append(msg)

    # 10. Expose static isInActiveSpearTwoPopGrace() & ping helpers
    text, msg = apply_exact_patch(
        text,
        "Add isInActiveSpearTwoPopGrace(), getLocalPingMs(), and getSpearTwoPopGroundGraceTicks()",
        old="    public static boolean isAutoMaceEnabled() {",
        new=(
            "    public static boolean isInActiveSpearTwoPopGrace(Minecraft client) {\n"
            "        MaceTarget mod = instance;\n"
            "        if (mod == null || !mod.isEnabled() || !mod.spearTwoPop.getValue()) {\n"
            "            return false;\n"
            "        }\n"
            "        return mod.smashState == SmashSequenceState.STEP_SPEAR_2POP_WAIT_MACE\n"
            "                && mod.groundedAfterDiveTicks <= mod.getSpearTwoPopGroundGraceTicks(client);\n"
            "    }\n\n"
            "    private int getLocalPingMs(Minecraft client) {\n"
            "        try {\n"
            "            if (client != null && client.player != null && client.getConnection() != null) {\n"
            "                PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());\n"
            "                if (info != null) {\n"
            "                    return Math.max(0, info.getLatency());\n"
            "                }\n"
            "            }\n"
            "        } catch (Throwable ignored) {\n"
            "        }\n"
            "        return 50;\n"
            "    }\n\n"
            "    private int getSpearTwoPopGroundGraceTicks(Minecraft client) {\n"
            "        int pingMs = getLocalPingMs(client);\n"
            "        return Mth.clamp(3 + (int) Math.ceil(pingMs / 50.0D), 4, 10);\n"
            "    }\n\n"
            "    private boolean wasRecentlyDroppingForSpear2Pop() {\n"
            "        int curTick = ActionManager.getInstance().getClientTick();\n"
            "        return (curTick - lastAirborneDropTick) <= 10 && lastAirbornePeakFallDist >= Math.max(1.40D, minFallDist.getValue());\n"
            "    }\n\n"
            "    public static boolean isAutoMaceEnabled() {"
        ),
        already_marker="public static boolean isInActiveSpearTwoPopGrace(Minecraft client)",
    )
    logs.append(msg)

    # 11. Track lastAirborneDropTick & lastAirbornePeakFallDist inside updateAirbornePeak()
    text, msg = apply_method_replacement(
        text,
        "Track lastAirborneDropTick & peak fall distance in updateAirbornePeak()",
        r"[ \t]*private void updateAirbornePeak\(Minecraft client\)\s*\{",
        replacement="""    private void updateAirbornePeak(Minecraft client) {
        if (client.player == null) {
            airbornePeakY = Double.NaN;
            return;
        }
        if (client.player.onGround() || client.player.isInWater() || client.player.isInLava()) {
            airbornePeakY = client.player.getY();
        } else {
            double y = client.player.getY();
            if (Double.isNaN(airbornePeakY) || y > airbornePeakY) {
                airbornePeakY = y;
            }
            double fallNow = getEffectiveFallDistance(client);
            if (client.player.getDeltaMovement().y < -0.06D || client.player.isFallFlying() || fallNow >= 1.25D) {
                lastAirborneDropTick = ActionManager.getInstance().getClientTick();
                lastAirbornePeakFallDist = Math.max(fallNow, client.player.isFallFlying() ? 2.5D : 0.0D);
            }
        }
    }""",
        already_marker="lastAirborneDropTick = ActionManager.getInstance().getClientTick();",
    )
    logs.append(msg)

    # 12. Update initiateSmashSequence() to pass needsOneTickSeparation = true after Spear Action.STAB
    text, msg = apply_exact_patch(
        text,
        "Pass needsOneTickSeparation=true after Spear Action.STAB in initiateSmashSequence()",
        old=(
            "            if (dist >= 2.0D && tryFireSpearJabOpeningHit(client, targetEntity)) {\n"
            "                beginSpearTwoPopMaceFollowup(client, targetEntity);\n"
            "                return;\n"
            "            }"
        ),
        new=(
            "            if (dist >= 2.0D && tryFireSpearJabOpeningHit(client, targetEntity)) {\n"
            "                beginSpearTwoPopMaceFollowup(client, targetEntity, true);\n"
            "                return;\n"
            "            }"
        ),
        already_marker="beginSpearTwoPopMaceFollowup(client, targetEntity, true);\n                return;\n            }\n        }",
    )
    logs.append(msg)

    # 13. Upgrade processSmashSequenceStateMachine() for guaranteed, zero-flag STEP_SPEAR_2POP_WAIT_MACE
    text, msg = apply_method_replacement(
        text,
        "Upgrade STEP_SPEAR_2POP_WAIT_MACE in processSmashSequenceStateMachine() for guaranteed zero-flag Mace hit",
        r"[ \t]*private void processSmashSequenceStateMachine\(Minecraft client\)\s*\{",
        replacement="""    private void processSmashSequenceStateMachine(Minecraft client) {
        if (client.player == null) {
            CombatStateManager.releaseMaceSmashLock();
            smashState = SmashSequenceState.IDLE;
            return;
        }

        if (isWearingElytra(client)) {
            smashState = SmashSequenceState.IDLE;
            yieldToPriority1AutoElytraUnequip(client);
            return;
        }

        switch (smashState) {
            case STEP_1_AXE_STUN_ATTACK -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() && performThrottledAttack(client, target)) {
                    stateCommittedTick = ActionManager.getInstance().getClientTick();
                    stateTickTimer.reset();
                    smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
                } else if (client.player.onGround() || stateTickTimer.finished(200.0D)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SELECT_MACE_AFTER_STUN -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                int maceSlot = targetMaceSlot != -1 ? targetMaceSlot : cachedMaceSlot;
                if (maceSlot != -1) {
                    selectHotbarSlotLegit(client, maceSlot);
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() && performThrottledAttack(client, target)) {
                    cooldownTimer.reset();
                    transitionToSwapBackRestore(client);
                } else {
                    stateCommittedTick = ActionManager.getInstance().getClientTick();
                    stateTickTimer.reset();
                    smashState = SmashSequenceState.STEP_1_MACE_SMASH_ATTACK;
                }
            }

            case STEP_SPEAR_2POP_WAIT_MACE -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                int curTick = ActionManager.getInstance().getClientTick();
                // Keep right-click released during the follow-up window so Spear cannot restart charging
                if (curTick <= spearSuppressKeyUseUntilTick && client.options != null) {
                    client.options.keyUse.setDown(false);
                }
                if (client.player.isUsingItem() && client.gameMode != null) {
                    client.gameMode.releaseUsingItem(client.player);
                    // Wait 1 tick after releasing item use so we never overlap ReleaseUse + Attack (NoSlow/BadPackets)
                    spearTwoPopReadyTick = Math.max(spearTwoPopReadyTick, curTick + 1);
                    return;
                }

                double dist = getPrecisionDistanceToTarget(client, target);
                // Keep crosshair smoothly aligned onto enemy hitbox while waiting for the strike tick
                if ("Silent".equalsIgnoreCase(aimMode.getValue())) {
                    if (dist <= swingRange.getValue() + 2.5D) {
                        queueSilentServerRotation(computeExactCenterAimAngle(client, target));
                    }
                } else if (dist <= swingRange.getValue() + 2.5D) {
                    if (!ActionManager.verifyStrict3mAttackRaycast(client, target, swingRange.getValue(), client.player.getViewVector(1.0F))) {
                        alignCameraOntoHitboxForStrike(client, target);
                    }
                }

                // Dynamic reach-exit check: if we are about to fly past 2.90m, fire immediately on this tick
                boolean aboutToExitReach = dist <= swingRange.getValue()
                        && getPredictedDistanceToTarget(client, target, 1.0D) > Math.min(2.90D, swingRange.getValue() - 0.08D);

                if (curTick < spearTwoPopReadyTick && !aboutToExitReach) {
                    return;
                }

                ActionManager actionMgr = ActionManager.getInstance();
                int maceSlot = resolveMaceSlot(client);
                if (maceSlot == -1) {
                    maceSlot = targetMaceSlot;
                }

                if (maceSlot >= 0 && maceSlot <= 8 && dist <= Math.min(3.0D, swingRange.getValue())) {
                    if (!actionMgr.isSafePreMotionTick()) {
                        return;
                    }
                    boolean needSlotSwitch = client.player.getInventory().getSelectedSlot() != maceSlot;
                    if (needSlotSwitch && !actionMgr.canMutateHotbarSlotThisTick()) {
                        return;
                    }
                    if (needSlotSwitch) {
                        selectHotbarSlotLegit(client, maceSlot);
                        if (client.gameMode != null) {
                            ((CarriedItemSyncInvoker) client.gameMode).attrium$ensureHasSentCarriedItem();
                        }
                    }
                    if (performSpearTwoPopMaceAttack(client, target, true)) {
                        cooldownTimer.reset();
                        transitionToSwapBackRestore(client);
                        return;
                    }
                }

                int maxWaitTicks = getSpearTwoPopGroundGraceTicks(client) + 3;
                if (curTick > spearTwoPopReadyTick + maxWaitTicks || stateTickTimer.finished(650.0D)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_1_MACE_SMASH_ATTACK -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                int maceSlot = targetMaceSlot != -1 ? targetMaceSlot : resolveMaceSlot(client);
                if (maceSlot != -1 && client.player.getInventory().getSelectedSlot() != maceSlot) {
                    selectHotbarSlotLegit(client, maceSlot);
                }
                double requiredDelayMs = isInElytraOrRocketDive(client) ? 0.0D : maceSwapDelayMs.getValue();
                if (requiredDelayMs > 0.0D && !stateTickTimer.finished(requiredDelayMs)) {
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() && performThrottledAttack(client, target)) {
                    cooldownTimer.reset();
                    transitionToSwapBackRestore(client);
                } else if (client.player.onGround() || stateTickTimer.finished(250.0D)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SWAPBACK_RESTORE -> {
                if (ActionManager.getInstance().getClientTick() <= stateCommittedTick) {
                    return;
                }
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
                CombatStateManager.releaseMaceSmashLock();
            }

            default -> {
                smashState = SmashSequenceState.IDLE;
                CombatStateManager.releaseMaceSmashLock();
            }
        }
    }""",
        already_marker="Dynamic reach-exit check: if we are about to fly past 2.90m",
    )
    logs.append(msg)

    # 14. Update isGroundedOrAscendingLockout() with ping-scaled ground grace
    text, msg = apply_method_replacement(
        text,
        "Use ping-scaled ground grace in isGroundedOrAscendingLockout()",
        r"[ \t]*private boolean isGroundedOrAscendingLockout\(Minecraft client\)\s*\{",
        replacement="""    private boolean isGroundedOrAscendingLockout(Minecraft client) {
        if (client.player == null) {
            return true;
        }
        if (client.player.isInWater()
                || client.player.isInLava()
                || client.player.getAbilities().flying) {
            return true;
        }
        // Keep aim & attack alive across touchdown for high-ping Spear -> Mace drops
        if (smashState == SmashSequenceState.STEP_SPEAR_2POP_WAIT_MACE
                && groundedAfterDiveTicks <= getSpearTwoPopGroundGraceTicks(client)) {
            return false;
        }
        // Never aim while ascending (e.g. Wind Burst upward launch or Rocket climb)
        if (client.player.getDeltaMovement().y >= -0.04D) {
            return true;
        }
        if (isInMidOrPostUnequipSmash(client) && (!client.player.onGround() || groundedAfterDiveTicks <= 3)) {
            return false;
        }
        if (client.player.onGround()) {
            return true;
        }
        if (isInElytraOrRocketDive(client)) {
            return false;
        }
        return client.player.getDeltaMovement().y >= -0.05D;
    }""",
        already_marker="groundedAfterDiveTicks <= getSpearTwoPopGroundGraceTicks(client)",
    )
    logs.append(msg)

    # 15. Keep aiming active during STEP_SPEAR_2POP_WAIT_MACE even when fallDistance resets on ground
    text, msg = apply_method_replacement(
        text,
        "Keep shouldAimAtTarget() active during STEP_SPEAR_2POP_WAIT_MACE touchdown",
        r"[ \t]*private boolean shouldAimAtTarget\(Minecraft client\)\s*\{",
        replacement="""    private boolean shouldAimAtTarget(Minecraft client) {
        if (target == null || client.player == null || isGroundedOrAscendingLockout(client) || isWhitelistedFriend(client, target)) {
            return false;
        }
        if (!ActionManager.hasLineOfSight(client, target)) {
            return false;
        }
        if (getPrecisionDistanceToTarget(client, target) > aimRange.getValue()) {
            return false;
        }
        if (smashState == SmashSequenceState.STEP_SPEAR_2POP_WAIT_MACE) {
            return true;
        }
        if (client.player.isFallFlying()) {
            // Beta rule: cancel aim whenever gliding with Elytra below 40 degrees dive angle
            if (betaAimMace.getValue() && getPlayerDiveAngleDeg(client) < 40.0D) {
                return false;
            }
            return isGlidingTowardTarget(client, target);
        }
        if (isInMidOrPostUnequipSmash(client) || isInElytraOrRocketDive(client)) {
            return true;
        }
        double requiredFall = Math.max(minFallDist.getValue(), aimInAir.getValue());
        return getEffectiveFallDistance(client) >= requiredFall;
    }""",
        already_marker="if (smashState == SmashSequenceState.STEP_SPEAR_2POP_WAIT_MACE) {\n            return true;\n        }",
    )
    logs.append(msg)

    # 16. Use spearTwoPopPeakFallDist snapshot in resolveMaceSlot() so Breach vs Density+WindBurst stays right on landing
    text, msg = apply_method_replacement(
        text,
        "Use spearTwoPopPeakFallDist snapshot in resolveMaceSlot() for grounded high-ping Mace selection",
        r"[ \t]*private int resolveMaceSlot\(Minecraft client\)\s*\{",
        replacement="""    private int resolveMaceSlot(Minecraft client) {
        if (client == null || client.player == null) {
            return -1;
        }
        if (betaAimMace.getValue()) {
            boolean needRefresh = (cachedBreachMaceSlot == -1 && cachedDensityWindMaceSlot == -1)
                    || (cachedBreachMaceSlot >= 0 && !isMaceStack(client.player.getInventory().getItem(cachedBreachMaceSlot)))
                    || (cachedDensityWindMaceSlot >= 0 && !isMaceStack(client.player.getInventory().getItem(cachedDensityWindMaceSlot)));
            if (needRefresh) {
                updateDynamicMaceCacheAt4Hz(client, true);
            }
            double fallDist = getEffectiveFallDistance(client);
            if (smashState == SmashSequenceState.STEP_SPEAR_2POP_WAIT_MACE && spearTwoPopPeakFallDist > fallDist) {
                fallDist = spearTwoPopPeakFallDist;
            }
            if (fallDist < 3.0D) {
                if (cachedBreachMaceSlot >= 0 && cachedBreachMaceSlot <= 8
                        && isMaceStack(client.player.getInventory().getItem(cachedBreachMaceSlot))) {
                    return cachedBreachMaceSlot;
                }
            } else {
                if (cachedDensityWindMaceSlot >= 0 && cachedDensityWindMaceSlot <= 8
                        && isMaceStack(client.player.getInventory().getItem(cachedDensityWindMaceSlot))) {
                    return cachedDensityWindMaceSlot;
                }
            }
        }
        if (cachedMaceSlot >= 0 && cachedMaceSlot <= 8) {
            if (isMaceStack(client.player.getInventory().getItem(cachedMaceSlot))) {
                return cachedMaceSlot;
            }
        }
        updateDynamicMaceCacheAt4Hz(client, true);
        return cachedMaceSlot;
    }""",
        already_marker="if (smashState == SmashSequenceState.STEP_SPEAR_2POP_WAIT_MACE && spearTwoPopPeakFallDist > fallDist)",
    )
    logs.append(msg)

    # 17. Remove obsolete getSpearTwoPopDelayTicks() if present
    if "private int getSpearTwoPopDelayTicks()" in text:
        text, msg = apply_method_replacement(
            text,
            "Remove obsolete static getSpearTwoPopDelayTicks()",
            r"[ \t]*private int getSpearTwoPopDelayTicks\(\)\s*\{",
            replacement="",
            already_marker="NO_OP_REMOVE_OBSOLETE",
        )
        logs.append(msg)

    # 18. Replace tryFireSpearJabOpeningHit(), detectSpearHitOnTarget(), beginSpearTwoPopMaceFollowup(),
    #     performSpearTwoPopMaceAttack(), and resetSpearHitTracker() with dynamic health-independent engine
    text, msg = apply_method_replacement(
        text,
        "Upgrade resetSpearHitTracker() (health-independent)",
        r"[ \t]*private void resetSpearHitTracker\(\)\s*\{",
        replacement="""    private void resetSpearHitTracker() {
        spearTrackTargetId = -1;
        spearTrackPrevHurtTime = 0;
        spearWasChargingLastTick = false;
        spearChargeTicks = 0;
    }""",
        already_marker="spearWasChargingLastTick = false;\n        spearChargeTicks = 0;",
    )
    logs.append(msg)

    text, msg = apply_method_replacement(
        text,
        "Upgrade tryFireSpearJabOpeningHit() to use dedicated verifySpearJabRaycast() & record interaction",
        r"[ \t]*private boolean tryFireSpearJabOpeningHit\(Minecraft client, LivingEntity candidate\)\s*\{",
        replacement="""    private boolean tryFireSpearJabOpeningHit(Minecraft client, LivingEntity candidate) {
        if (client == null || client.player == null || client.player.connection == null || candidate == null) {
            return false;
        }
        if (client.player.isUsingItem() || client.player.isFallFlying() || isWearingElytra(client)) {
            return false;
        }
        ActionManager actionMgr = ActionManager.getInstance();
        if (!actionMgr.isSafePreMotionTick()
                || !actionMgr.canDispatchGuaranteedFollowup(client, ActionManager.ActionPriority.AUTO_MACE_STRIKE)) {
            return false;
        }
        if (client.player.getAttackStrengthScale(0.5F) < 0.95F) {
            return false;
        }
        Vec3 activeLookVec = "Silent".equalsIgnoreCase(aimMode.getValue()) && AngleConnection.INSTANCE.getCurrentAngle() != null
                ? AngleConnection.INSTANCE.getCurrentAngle().toVector()
                : client.player.getViewVector(1.0F);
        if (!ActionManager.verifySpearJabRaycast(client, candidate, 4.3D, activeLookVec)) {
            if (!"Silent".equalsIgnoreCase(aimMode.getValue())) {
                alignCameraOntoHitboxForStrike(client, candidate);
            }
            if (!ActionManager.verifySpearJabRaycast(client, candidate, 4.3D, client.player.getViewVector(1.0F))) {
                return false;
            }
        }
        if (client.gameMode != null) {
            ((CarriedItemSyncInvoker) client.gameMode).attrium$ensureHasSentCarriedItem();
        }
        client.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STAB,
                BlockPos.ZERO,
                Direction.DOWN
        ));
        client.player.swing(InteractionHand.MAIN_HAND);
        client.player.resetAttackStrengthTicker();
        actionMgr.recordInteractionDispatched(client);
        lastSpearAutoPopTick = actionMgr.getClientTick();
        return true;
    }""",
        already_marker="ActionManager.verifySpearJabRaycast(client, candidate, 4.3D, activeLookVec)",
    )
    logs.append(msg)

    text, msg = apply_method_replacement(
        text,
        "Rewrite detectSpearHitOnTarget() (100% health-independent + proactive high-ping charge contact)",
        r"[ \t]*private boolean detectSpearHitOnTarget\(Minecraft client, LivingEntity candidate, double distToTarget\)\s*\{",
        replacement="""    private double computeRelativeClosingSpeed(Minecraft client, LivingEntity candidate) {
        if (client == null || client.player == null || candidate == null) {
            return 0.0D;
        }
        double ex = client.player.getX();
        double ey = client.player.getEyeY();
        double ez = client.player.getZ();
        AABB box = candidate.getBoundingBox();
        double rx = Mth.clamp(ex, box.minX, box.maxX) - ex;
        double ry = Mth.clamp(ey, box.minY, box.maxY) - ey;
        double rz = Mth.clamp(ez, box.minZ, box.maxZ) - ez;
        double dist = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (dist < 1.0E-4D) {
            return client.player.getDeltaMovement().length();
        }
        Vec3 pVel = client.player.getDeltaMovement();
        return ((pVel.x - betaEnemyVx) * rx + (pVel.y - betaEnemyVy) * ry + (pVel.z - betaEnemyVz) * rz) / dist;
    }

    /**
     * Health-independent Spear hit detector (works on servers that spoof or hide entity HP):
     * 1. HurtAnimation rising edge (target.hurtTime >= 7, cannot be spoofed by AntiHealthIndicator)
     * 2. Manual Spear left-click swing toward target within 4.5m
     * 3. Proactive Spear-charge contact for high-ping players (detects charge intersection without waiting for RTT)
     */
    private boolean detectSpearHitOnTarget(Minecraft client, LivingEntity candidate, double distToTarget) {
        if (client == null || client.player == null || candidate == null || !candidate.isAlive()) {
            resetSpearHitTracker();
            return false;
        }
        int curTick = ActionManager.getInstance().getClientTick();
        int curHurtTime = candidate.hurtTime;

        boolean chargingNow = client.player.isUsingItem() && isSpearStack(client.player.getUseItem());
        if (chargingNow) {
            spearChargeTicks++;
        } else {
            spearChargeTicks = 0;
        }
        spearWasChargingLastTick = chargingNow;

        if (candidate.getId() != spearTrackTargetId) {
            spearTrackTargetId = candidate.getId();
            spearTrackPrevHurtTime = curHurtTime;
            return false;
        }

        boolean hurtSpike = curHurtTime > spearTrackPrevHurtTime && curHurtTime >= 7;
        boolean manualSpearSwing = !chargingNow
                && client.player.swinging
                && client.player.swingTime <= 1
                && distToTarget <= 4.5D;

        boolean proactiveChargeHit = false;
        if (chargingNow && spearChargeTicks >= 3) {
            double closingSpeed = computeRelativeClosingSpeed(client, candidate);
            double pSpeed = client.player.getDeltaMovement().length();
            // Vanilla Spear charge requires >= 4.6 b/s (~0.23 b/t) relative speed to deal charge damage
            if (Math.max(closingSpeed, pSpeed) >= 0.23D) {
                double contactDist = Mth.clamp(2.65D + Math.max(0.0D, closingSpeed) * 1.05D, 2.65D, 3.85D);
                if (distToTarget <= contactDist) {
                    Vec3 look = "Silent".equalsIgnoreCase(aimMode.getValue()) && AngleConnection.INSTANCE.getCurrentAngle() != null
                            ? AngleConnection.INSTANCE.getCurrentAngle().toVector()
                            : client.player.getViewVector(1.0F);
                    if (ActionManager.verifySpearJabRaycast(client, candidate, contactDist + 0.35D, look)) {
                        proactiveChargeHit = true;
                    }
                }
            }
        }

        spearTrackPrevHurtTime = curHurtTime;

        if ((curTick - lastSpearAutoPopTick) <= 6) {
            return false;
        }
        double maxDetectDist = Math.max(4.6D, swingRange.getValue() + 1.6D);
        if (distToTarget <= maxDetectDist && (hurtSpike || manualSpearSwing || proactiveChargeHit)) {
            lastSpearAutoPopTick = curTick;
            return true;
        }
        return false;
    }

    /**
     * Velocity-scaled dynamic delay engine:
     * - Slow (< 0.60 b/t):   2 ticks
     * - Medium (0.60-1.15):  1 tick
     * - Fast (>= 1.15 b/t):  0 ticks (or 1 tick if separating from same-tick STAB/ReleaseUse)
     * Automatically steps down delay if forward motion would carry the player outside 2.90m reach.
     */
    private int computeDynamicSpearTwoPopDelayTicks(
            Minecraft client,
            LivingEntity targetEntity,
            boolean needsOneTickSeparation
    ) {
        if (client == null || client.player == null || targetEntity == null) {
            return needsOneTickSeparation ? 1 : 2;
        }
        updateBetaEnemyVelocityOncePerTick(client, targetEntity);
        double closingSpeed = computeRelativeClosingSpeed(client, targetEntity);
        double playerSpeed = client.player.getDeltaMovement().length();
        double effectiveSpeed = Math.max(closingSpeed, playerSpeed);

        int delay;
        if (effectiveSpeed < 0.60D) {
            delay = 2;
        } else if (effectiveSpeed < 1.15D) {
            delay = 1;
        } else {
            delay = 0;
        }

        // Reach-exit safety override: step down delay if velocity would carry us out of 2.90m reach
        double safeReach = Math.min(2.90D, swingRange.getValue() - 0.08D);
        while (delay > (needsOneTickSeparation ? 1 : 0)) {
            double predDist = getPredictedDistanceToTarget(client, targetEntity, (double) delay);
            if (predDist > safeReach) {
                delay--;
            } else {
                break;
            }
        }

        return needsOneTickSeparation ? Math.max(1, delay) : Math.max(0, delay);
    }""",
        already_marker="private int computeDynamicSpearTwoPopDelayTicks(",
    )
    logs.append(msg)

    text, msg = apply_method_replacement(
        text,
        "Upgrade beginSpearTwoPopMaceFollowup() to dynamic velocity scaling & fall-distance snapshot",
        r"[ \t]*private void beginSpearTwoPopMaceFollowup\(Minecraft client, LivingEntity targetEntity\)\s*\{",
        replacement="""    private void beginSpearTwoPopMaceFollowup(Minecraft client, LivingEntity targetEntity) {
        beginSpearTwoPopMaceFollowup(client, targetEntity, false);
    }

    private void beginSpearTwoPopMaceFollowup(
            Minecraft client,
            LivingEntity targetEntity,
            boolean needsOneTickSeparation
    ) {
        if (client == null || client.player == null || targetEntity == null || !autoSwitch.getValue()) {
            return;
        }
        // Snapshot fall distance BEFORE any ground contact so Beta Breach/Density choice stays accurate
        spearTwoPopPeakFallDist = Math.max(getEffectiveFallDistance(client), lastAirbornePeakFallDist);

        int maceSlot = resolveMaceSlot(client);
        if (maceSlot < 0 || maceSlot > 8) {
            return;
        }

        int currentSlot = client.player.getInventory().getSelectedSlot();
        if (savedHotbarSlot == -1 && currentSlot != maceSlot) {
            savedHotbarSlot = currentSlot;
        }
        targetMaceSlot = maceSlot;

        int curTick = ActionManager.getInstance().getClientTick();
        boolean wasUsingItem = client.player.isUsingItem();
        boolean mustSeparate = needsOneTickSeparation || wasUsingItem;
        int delayTicks = computeDynamicSpearTwoPopDelayTicks(client, targetEntity, mustSeparate);

        // Release Spear charge cleanly & suppress keyUse across the follow-up window
        if (client.options != null) {
            client.options.keyUse.setDown(false);
        }
        if (wasUsingItem && client.gameMode != null) {
            client.gameMode.releaseUsingItem(client.player);
        }
        spearSuppressKeyUseUntilTick = curTick + Math.max(2, delayTicks + 2);

        CombatStateManager.acquireMaceSmashLock();
        lastActiveGlideDiveTick = curTick;
        postUnequipSmashEndTick = Math.max(postUnequipSmashEndTick, curTick + 25);

        if (delayTicks <= 0) {
            ActionManager actionMgr = ActionManager.getInstance();
            double dist = getPrecisionDistanceToTarget(client, targetEntity);
            if ("Silent".equalsIgnoreCase(aimMode.getValue())) {
                if (dist <= swingRange.getValue() + 2.0D) {
                    queueSilentServerRotation(computeExactCenterAimAngle(client, targetEntity));
                }
            } else if (dist <= swingRange.getValue() + 2.0D) {
                if (!ActionManager.verifyStrict3mAttackRaycast(client, targetEntity, swingRange.getValue(), client.player.getViewVector(1.0F))) {
                    alignCameraOntoHitboxForStrike(client, targetEntity);
                }
            }

            boolean needSlotSwitch = client.player.getInventory().getSelectedSlot() != maceSlot;
            if (dist <= Math.min(3.0D, swingRange.getValue())
                    && !isWearingElytra(client)
                    && actionMgr.isSafePreMotionTick()
                    && (!needSlotSwitch || actionMgr.canMutateHotbarSlotThisTick())) {
                if (needSlotSwitch) {
                    selectHotbarSlotLegit(client, maceSlot);
                    if (client.gameMode != null) {
                        ((CarriedItemSyncInvoker) client.gameMode).attrium$ensureHasSentCarriedItem();
                    }
                }
                if (performSpearTwoPopMaceAttack(client, targetEntity, true)) {
                    cooldownTimer.reset();
                    transitionToSwapBackRestore(client);
                    return;
                }
            }
            spearTwoPopReadyTick = curTick + 1;
            stateCommittedTick = curTick;
            stateTickTimer.reset();
            smashState = SmashSequenceState.STEP_SPEAR_2POP_WAIT_MACE;
            return;
        }

        spearTwoPopReadyTick = curTick + delayTicks;
        stateCommittedTick = curTick;
        stateTickTimer.reset();
        smashState = SmashSequenceState.STEP_SPEAR_2POP_WAIT_MACE;
    }""",
        already_marker="spearTwoPopPeakFallDist = Math.max(getEffectiveFallDistance(client), lastAirbornePeakFallDist);",
    )
    logs.append(msg)

    text, msg = apply_method_replacement(
        text,
        "Upgrade performSpearTwoPopMaceAttack() for guaranteed zero-flag pre-motion strike",
        r"[ \t]*private boolean performSpearTwoPopMaceAttack\(Minecraft client, LivingEntity targetEntity, boolean allowZeroTickSameTick\)\s*\{",
        replacement="""    private boolean performSpearTwoPopMaceAttack(Minecraft client, LivingEntity targetEntity, boolean allowZeroTickSameTick) {
        if (client.player == null || client.gameMode == null || targetEntity == null || client.screen != null) {
            return false;
        }
        if (isWhitelistedFriend(client, targetEntity)) {
            return false;
        }
        if (client.player.isUsingItem() || client.player.isFallFlying() || isWearingElytra(client)) {
            return false;
        }
        ActionManager actionMgr = ActionManager.getInstance();
        if (!actionMgr.canDispatchGuaranteedFollowup(client, ActionManager.ActionPriority.AUTO_MACE_STRIKE)) {
            return false;
        }

        Vec3 activeLookVec = "Silent".equalsIgnoreCase(aimMode.getValue()) && AngleConnection.INSTANCE.getCurrentAngle() != null
                ? AngleConnection.INSTANCE.getCurrentAngle().toVector()
                : client.player.getViewVector(1.0F);

        if (!ActionManager.verifyStrict3mAttackRaycast(client, targetEntity, swingRange.getValue(), activeLookVec)) {
            return false;
        }

        client.gameMode.attack(client.player, targetEntity);
        client.player.swing(InteractionHand.MAIN_HAND);
        actionMgr.recordInteractionDispatched(client);
        return true;
    }""",
        already_marker="actionMgr.canDispatchGuaranteedFollowup(client, ActionManager.ActionPriority.AUTO_MACE_STRIKE)",
    )
    logs.append(msg)

    # 19. Reset new Spear 2-Pop fields inside resetAllStates()
    text, msg = apply_exact_patch(
        text,
        "Reset dynamic Spear 2-Pop & airborne snapshot fields in resetAllStates()",
        old=(
            "        spearTwoPopReadyTick = -100;\n"
            "        lastSpearAutoPopTick = -100;\n"
            "        resetSpearHitTracker();"
        ),
        new=(
            "        spearTwoPopReadyTick = -100;\n"
            "        lastSpearAutoPopTick = -100;\n"
            "        spearSuppressKeyUseUntilTick = -100;\n"
            "        spearTwoPopPeakFallDist = 0.0D;\n"
            "        lastAirborneDropTick = -100;\n"
            "        lastAirbornePeakFallDist = 0.0D;\n"
            "        resetSpearHitTracker();"
        ),
        already_marker="spearTwoPopPeakFallDist = 0.0D;\n        lastAirborneDropTick = -100;",
    )
    logs.append(msg)

    return text, logs


def main() -> int:
    parser = argparse.ArgumentParser(description="Upgrade AutoMace Spear 2-Pop to dynamic velocity-scaled, zero-flag mode.")
    parser.add_argument("repo", nargs="?", default="src", help="Source directory (default: src)")
    parser.add_argument("--dry-run", action="store_true", help="Preview changes without writing")
    args = parser.parse_args()

    root = Path(args.repo).resolve()
    if not root.exists():
        print(f"[ERROR] Path does not exist: {root}", file=sys.stderr)
        return 1

    targets = [
        p for p in sorted(root.rglob("MaceTarget.java"))
        if not any(part.startswith(".") or part in {"build", "run"} for part in p.parts)
    ]
    if not targets:
        print(f"[ERROR] Could not locate MaceTarget.java under {root}", file=sys.stderr)
        return 1

    warnings = 0
    for path in targets:
        raw = path.read_bytes()
        nl = detect_newline(raw)
        original = raw.decode("utf-8").replace("\r\n", "\n")
        patched, logs = patch_mace_target(original)

        rel = path.relative_to(root)
        print(f"\n==> {rel}")
        for line in logs:
            print(line)
            if "[WARN]" in line:
                warnings += 1

        if patched != original and not args.dry_run:
            path.write_bytes(patched.replace("\n", nl).encode("utf-8"))
            print(f"  -> Saved {rel}")
        elif patched == original:
            print("  -> No modifications needed")

    print("\nDone." + (f" ({warnings} warning(s))" if warnings else " All patches clean."))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())