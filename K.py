#!/usr/bin/env python3
"""
Attrium One-Command Gist Auth Manager (Termux)
Automatically hashes new Device IDs (d1) or Username+Password pairs (u2)
with salted PBKDF2-HMAC-SHA256 + decoy noise and pushes them directly to
your GitHub Gist via the GitHub API—no browser needed!
"""
import base64
import hashlib
import json
import os
import secrets
import sys
import urllib.error
import urllib.request
import zlib

GIST_ID = "df6ca1c8b1a34a9503636e5cf145cc1e"
GIST_FILE = "gistfile1.txt"
TOKEN_PATH = os.path.expanduser("~/.attrium_gist_token")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def make_noise_line() -> str:
    return b64url(secrets.token_bytes(secrets.choice([24, 32, 40, 48])))


def encode_entry(tag: str, secret_input: bytes, iterations: int) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", secret_input, salt, iterations, dklen=32)
    payload = f"{tag}{b64url(salt)}.{b64url(dk)}"
    crc = f"{zlib.crc32(payload.encode('ascii')) & 0xFFFF:04x}"
    return f"{secrets.token_hex(1)}{payload}.{crc}"


def get_token() -> str:
    env_tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if env_tok:
        return env_tok
    if os.path.isfile(TOKEN_PATH):
        with open(TOKEN_PATH, "r", encoding="utf-8") as f:
            tok = f.read().strip()
            if tok:
                return tok
    print("\n[First-Time Setup] Create a GitHub Personal Access Token with ONLY the 'gist' scope:")
    print("  https://github.com/settings/tokens/new?scopes=gist&description=AttriumAuth")
    tok = input("Paste your GitHub Gist token (saved locally to ~/.attrium_gist_token): ").strip()
    if not tok:
        print("[ERROR] Token cannot be empty.")
        sys.exit(1)
    with open(TOKEN_PATH, "w", encoding="utf-8") as f:
        f.write(tok + "\n")
    os.chmod(TOKEN_PATH, 0o600)
    print(f"[SAVED] Token saved securely to {TOKEN_PATH}")
    return tok


def api_request(method: str, url: str, token: str, payload: dict = None) -> dict:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "AttriumGistAdmin/1.0")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", "replace")
        print(f"[HTTP {e.code}] GitHub API error: {err_body}")
        sys.exit(1)


def main():
    token = get_token()
    gist_url = f"https://api.github.com/gists/{GIST_ID}"

    print("\n=== Attrium Auto Gist Manager ===")
    print("1) Auto-add Device ID (permanent auto-login)")
    print("2) Auto-add Username + Password (24h session)")
    print("3) Show Gist stats (count active Device IDs & Users)")
    choice = input("Choose [1/2/3]: ").strip()

    gist = api_request("GET", gist_url, token)
    files = gist.get("files", {})
    target_name = GIST_FILE if GIST_FILE in files else (next(iter(files.keys())) if files else GIST_FILE)
    current_content = files.get(target_name, {}).get("content", "")

    if choice == "3":
        lines = [l.strip() for l in current_content.splitlines() if l.strip()]
        d_cnt = sum(1 for l in lines if len(l) > 40 and l[2:4] == "d1" and l.count(".") == 2)
        u_cnt = sum(1 for l in lines if len(l) > 40 and l[2:4] == "u2" and l.count(".") == 2)
        print(f"Gist file '{target_name}': {d_cnt} Device IDs, {u_cnt} User accounts, {len(lines)} total lines.")
        return

    if choice == "1":
        hwid = input("Paste 64-char Device ID: ").strip().lower()
        if len(hwid) != 64 or any(c not in "0123456789abcdef" for c in hwid):
            print("[WARN] Input doesn't look like a 64-char SHA-256 hex ID, continuing anyway...")
        new_entry = encode_entry("d1", hwid.encode("utf-8"), 10_000)
        label = f"Device ID ({hwid[:10]}...)"
    elif choice == "2":
        user = input("Username: ").strip().lower()
        pwd = input("Password: ").strip()
        if not user or not pwd:
            print("[ERROR] Username and password cannot be empty.")
            sys.exit(1)
        new_entry = encode_entry("u2", f"{user}:{pwd}".encode("utf-8"), 50_000)
        label = f"User '{user}'"
    else:
        print("Invalid choice.")
        return

    prefix = current_content.rstrip("\n")
    addition = f"{make_noise_line()}\n{new_entry}\n{make_noise_line()}"
    updated_content = (prefix + "\n" + addition + "\n") if prefix else (addition + "\n")

    api_request("PATCH", gist_url, token, {"files": {target_name: {"content": updated_content}}})
    print(f"[SUCCESS] Added {label} directly to your GitHub Gist! (Takes effect in ~30-60s)")


if __name__ == "__main__":
    main()