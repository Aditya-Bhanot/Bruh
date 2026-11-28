#!/usr/bin/env python3
"""
Attrium One-Command Gist Auth Manager v2 (Termux)
Supports adding AND revoking Device IDs (d1) or Username+Password pairs (u2)
with salted PBKDF2-HMAC-SHA256 + decoy noise directly via the GitHub Gist API.
"""
import base64
import hashlib
import hmac
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


def b64url_decode(s: str) -> bytes:
    pad = "=" * ((4 - (len(s) % 4)) % 4)
    return base64.urlsafe_b64decode(s + pad)


def make_noise_line() -> str:
    return b64url(secrets.token_bytes(secrets.choice([24, 32, 40, 48])))


def encode_entry(tag: str, secret_input: bytes, iterations: int) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", secret_input, salt, iterations, dklen=32)
    payload = f"{tag}{b64url(salt)}.{b64url(dk)}"
    crc = f"{zlib.crc32(payload.encode('ascii')) & 0xFFFF:04x}"
    return f"{secrets.token_hex(1)}{payload}.{crc}"


def is_valid_entry(line: str, expected_tag: str | None = None) -> bool:
    s = line.strip()
    if len(s) <= 40 or s.count(".") != 2:
        return False
    tag = s[2:4]
    if tag not in ("d1", "u2"):
        return False
    if expected_tag is not None and tag != expected_tag:
        return False
    payload, crc = s[2:].rsplit(".", 1)
    expected_crc = f"{zlib.crc32(payload.encode('ascii')) & 0xFFFF:04x}"
    return hmac.compare_digest(crc.lower(), expected_crc)


def entry_matches_secret(line: str, tag: str, secret_input: bytes, iterations: int) -> bool:
    if not is_valid_entry(line, tag):
        return False
    s = line.strip()
    payload, _ = s[2:].rsplit(".", 1)
    body = payload[2:]  # strip tag ("d1" or "u2")
    salt_b64, dk_b64 = body.split(".", 1)
    try:
        salt = b64url_decode(salt_b64)
        stored_dk = b64url_decode(dk_b64)
    except Exception:
        return False
    computed = hashlib.pbkdf2_hmac("sha256", secret_input, salt, iterations, dklen=len(stored_dk))
    return hmac.compare_digest(computed, stored_dk)


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
    req.add_header("User-Agent", "AttriumGistAdmin/2.0")
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

    print("\n=== Attrium Auto Gist Manager v2 ===")
    print("1) Add Device ID (permanent auto-login)")
    print("2) Add Username + Password (24h session)")
    print("3) Show Gist stats (count active Device IDs & Users)")
    print("4) Revoke Device ID")
    print("5) Revoke Username + Password")
    print("6) Refresh decoy noise (keep all active entries)")
    choice = input("Choose [1-6]: ").strip()

    gist = api_request("GET", gist_url, token)
    files = gist.get("files", {})
    target_name = GIST_FILE if GIST_FILE in files else (next(iter(files.keys())) if files else GIST_FILE)
    current_content = files.get(target_name, {}).get("content", "")
    lines = [l.strip() for l in current_content.splitlines() if l.strip()]

    if choice == "3":
        d_cnt = sum(1 for l in lines if is_valid_entry(l, "d1"))
        u_cnt = sum(1 for l in lines if is_valid_entry(l, "u2"))
        print(f"Gist file '{target_name}': {d_cnt} Device IDs, {u_cnt} User accounts, {len(lines)} total lines.")
        return

    if choice == "1":
        hwid = input("Paste 64-char Device ID: ").strip().lower()
        if len(hwid) != 64 or any(c not in "0123456789abcdef" for c in hwid):
            print("[WARN] Input doesn't look like a 64-char SHA-256 hex ID, continuing anyway...")
        new_entry = encode_entry("d1", hwid.encode("utf-8"), 10_000)
        prefix = current_content.rstrip("\n")
        addition = f"{make_noise_line()}\n{new_entry}\n{make_noise_line()}"
        updated_content = (prefix + "\n" + addition + "\n") if prefix else (addition + "\n")
        api_request("PATCH", gist_url, token, {"files": {target_name: {"content": updated_content}}})
        print(f"[SUCCESS] Added Device ID ({hwid[:10]}...) to Gist!")
        return

    if choice == "2":
        user = input("Username: ").strip().lower()
        pwd = input("Password: ").strip()
        if not user or not pwd:
            print("[ERROR] Username and password cannot be empty.")
            sys.exit(1)
        new_entry = encode_entry("u2", f"{user}:{pwd}".encode("utf-8"), 50_000)
        prefix = current_content.rstrip("\n")
        addition = f"{make_noise_line()}\n{new_entry}\n{make_noise_line()}"
        updated_content = (prefix + "\n" + addition + "\n") if prefix else (addition + "\n")
        api_request("PATCH", gist_url, token, {"files": {target_name: {"content": updated_content}}})
        print(f"[SUCCESS] Added User '{user}' to Gist!")
        return

    if choice == "4":
        hwid = input("Paste 64-char Device ID to REVOKE: ").strip().lower()
        kept = []
        removed = 0
        for l in lines:
            if entry_matches_secret(l, "d1", hwid.encode("utf-8"), 10_000):
                removed += 1
            else:
                kept.append(l)
        if removed == 0:
            print("[INFO] No matching Device ID found in Gist.")
            return
        updated_content = "\n".join(kept) + "\n"
        api_request("PATCH", gist_url, token, {"files": {target_name: {"content": updated_content}}})
        print(f"[REVOKED] Removed {removed} matching Device ID entry(ies) from Gist!")
        return

    if choice == "5":
        user = input("Username to REVOKE: ").strip().lower()
        pwd = input("Password for that user: ").strip()
        kept = []
        removed = 0
        secret_bytes = f"{user}:{pwd}".encode("utf-8")
        for l in lines:
            if entry_matches_secret(l, "u2", secret_bytes, 50_000):
                removed += 1
            else:
                kept.append(l)
        if removed == 0:
            print("[INFO] No matching Username+Password entry found in Gist.")
            return
        updated_content = "\n".join(kept) + "\n"
        api_request("PATCH", gist_url, token, {"files": {target_name: {"content": updated_content}}})
        print(f"[REVOKED] Removed {removed} matching user entry(ies) from Gist!")
        return

    if choice == "6":
        valid = [l for l in lines if is_valid_entry(l)]
        rebuilt = [make_noise_line()]
        for v in valid:
            rebuilt.append(v)
            rebuilt.append(make_noise_line())
        updated_content = "\n".join(rebuilt) + "\n"
        api_request("PATCH", gist_url, token, {"files": {target_name: {"content": updated_content}}})
        print(f"[SUCCESS] Refreshed noise around {len(valid)} active auth entries.")
        return

    print("Invalid choice.")


if __name__ == "__main__":
    main()
