#!/usr/bin/env python3
"""
Updates AutoUpdater.applyStagedUpdateNow() so that when an update is applied,
the installed mod JAR in mods/ is saved as attirumhumaib.jar and any old differently-named
Attrium JAR (targetJar) is removed to prevent duplicates.
"""

from pathlib import Path
import shutil
import sys

TARGET = Path.home() / "Meow/Bruh/src/main/java/attrium/ru/manager/AutoUpdater.java"
NEW_JAR_NAME = "bbe-fabric-1.3.4+mc1.21.11.jar"

OLD_SWAP = """            Path stagingInMods = targetJar.resolveSibling(targetJar.getFileName().toString() + ".updating");
            Files.copy(tmp, stagingInMods, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(stagingInMods, targetJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                try {
                    Files.move(stagingInMods, targetJar, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception moveEx) {
                    Files.copy(tmp, targetJar, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(stagingInMods);
                }
            }
            Files.deleteIfExists(tmp);
            System.out.println("[Attrium AutoUpdater] Applied staged update to " + targetJar);"""

NEW_SWAP = f"""            Path finalJar = targetJar.resolveSibling("{NEW_JAR_NAME}");
            Path stagingInMods = targetJar.resolveSibling("{NEW_JAR_NAME}.updating");
            Files.copy(tmp, stagingInMods, StandardCopyOption.REPLACE_EXISTING);
            try {{
                Files.move(stagingInMods, finalJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }} catch (Exception atomicEx) {{
                try {{
                    Files.move(stagingInMods, finalJar, StandardCopyOption.REPLACE_EXISTING);
                }} catch (Exception moveEx) {{
                    Files.copy(tmp, finalJar, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(stagingInMods);
                }}
            }}
            if (!finalJar.equals(targetJar)) {{
                Files.deleteIfExists(targetJar);
            }}
            Files.deleteIfExists(tmp);
            activeModJarPath = finalJar;
            System.out.println("[Attrium AutoUpdater] Applied staged update to " + finalJar);"""


def main() -> int:
    if not TARGET.exists():
        print(f"ERROR: {TARGET} not found.", file=sys.stderr)
        return 1
    original = TARGET.read_text(encoding="utf-8")
    if f'resolveSibling("{NEW_JAR_NAME}")' in original:
        print(f"AutoUpdater.java already targets {NEW_JAR_NAME}.")
        return 0
    if OLD_SWAP not in original:
        print("ERROR: Could not find expected swap block in applyStagedUpdateNow().", file=sys.stderr)
        return 1
    updated = original.replace(OLD_SWAP, NEW_SWAP, 1)
    if (original.count("{") - original.count("}")) != (updated.count("{") - updated.count("}")):
        print("ERROR: Brace mismatch. Aborting.", file=sys.stderr)
        return 1
    bak = TARGET.with_suffix(".java.rename.bak")
    if not bak.exists():
        shutil.copy2(TARGET, bak)
    TARGET.write_text(updated, encoding="utf-8")
    print(f"SUCCESS: AutoUpdater now saves updates as {NEW_JAR_NAME} (backup: {bak.name}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())