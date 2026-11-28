#!/usr/bin/env python3
"""
Attrium Auth Key & Salted Hash Generator (Step 1)
Generates unreadable PBKDF2-HMAC-SHA256 entries with random salts and noise padding
for your GitHub raw auth list (safe for FoldCraftLauncher + cracked launchers).

Format of emitted real entries (hidden inside noise lines):
  <2-hex-noise><tag><salt_b64url>.<hash_b64url>.<4-hex-crc>
  - tag "d1": Device Fingerprint entry (PBKDF2-HMAC-SHA256, 10,000 iters)
  - tag "u2": Username:Password entry (PBKDF2-HMAC-SHA256, 50,000 iters)
"""
import base64
import hashlib
import os
import secrets
import sys
import zlib


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def make_noise_line() -> str:
    # Produces plausible-looking hex/base64 decoy lines that fail checksum validation
    n = secrets.choice([24, 32, 40, 48])
    return b64url(secrets.token_bytes(n))


def encode_entry(tag: str, secret_input: bytes, iterations: int) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", secret_input, salt, iterations, dklen=32)
    payload = f"{tag}{b64url(salt)}.{b64url(dk)}"
    crc = f"{zlib.crc32(payload.encode('ascii')) & 0xFFFF:04x}"
    prefix = secrets.token_hex(1)
    return f"{prefix}{payload}.{crc}"


def make_device_entry(device_hash_hex: str) -> str:
    clean = device_hash_hex.strip().lower()
    if len(clean) != 64 or any(c not in "0123456789abcdef" for c in clean):
        print("[WARN] Expected a 64-char SHA-256 hex device fingerprint; hashing input as-is.")
    return encode_entry("d1", clean.encode("utf-8"), 10_000)


def make_user_entry(username: str, password: str) -> str:
    cred = f"{username.strip().lower()}:{password}".encode("utf-8")
    return encode_entry("u2", cred, 50_000)


def interactive_menu():
    print("=== Attrium Auth Hash Generator (Step 1) ===")
    print("1) Add Device Fingerprint (auto-login without password)")
    print("2) Add Username + Password (24h session login)")
    print("3) Generate decoy noise block (paste between real lines)")
    choice = input("Choose [1/2/3]: ").strip()

    if choice == "1":
        hwid = input("Paste 64-char Device Fingerprint SHA-256: ").strip()
        line = make_device_entry(hwid)
        print("\n--- Paste into your GitHub auth file ---")
        print(make_noise_line())
        print(line)
        print(make_noise_line())
    elif choice == "2":
        user = input("Username: ").strip()
        pwd = input("Password: ").strip()
        if not user or not pwd:
            print("[ERROR] Username and password cannot be empty.")
            sys.exit(1)
        line = make_user_entry(user, pwd)
        print("\n--- Paste into your GitHub auth file ---")
        print(make_noise_line())
        print(line)
        print(make_noise_line())
    elif choice == "3":
        count = input("How many noise lines? [default 6]: ").strip()
        n = int(count) if count.isdigit() else 6
        print("\n--- Decoy Noise Lines ---")
        for _ in range(max(1, min(n, 50))):
            print(make_noise_line())
    else:
        print("Invalid choice.")


if __name__ == "__main__":
    interactive_menu()