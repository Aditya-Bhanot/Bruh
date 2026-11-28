#!/data/data/com.termux/files/usr/bin/bash
set -e

echo "=== Attrium Automated Flattened & Obfuscated Release Build ==="
./gradlew clean build scrubObfJar --no-daemon

OBF_JAR=$(ls -t build/libs/*-obf.jar | head -n 1)
if [ -z "$OBF_JAR" ]; then
    echo "[ERROR] No *-obf.jar found in build/libs/"
    exit 1
fi

MODS_DIR="$HOME/storage/shared/FCL/.minecraft/versions/test/mods"
if [ -d "$MODS_DIR" ]; then
    rm -f "$MODS_DIR"/Attrium-*.jar
    cp "$OBF_JAR" "$MODS_DIR/"
    echo "[DEPLOYED] Copied $(basename "$OBF_JAR") -> $MODS_DIR"
else
    echo "[INFO] FCL mods folder not found at $MODS_DIR (skipping local copy)."
fi

read -p "Commit & push clean source to GitHub to trigger Actions release? [y/N]: " PUSH_ANS
if [[ "$PUSH_ANS" =~ ^[Yy]$ ]]; then
    read -p "Commit message [default: Update]: " MSG
    MSG=${MSG:-Update}
    git add -A
    git commit -m "$MSG" || true
    git push
    echo "[DONE] Pushed clean source! GitHub Actions is now publishing the hardened JAR."
fi
