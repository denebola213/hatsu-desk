#!/usr/bin/env python3
"""GitHub App の installation token を発行し、その資格でコマンドを実行する。

使い方:
  gh-app-token.py --token              # トークンだけ出す
  gh-app-token.py -- gh pr list        # GH_TOKEN を差し替えて実行

環境変数:
  GH_APP_ID               App ID
  GH_APP_INSTALLATION_ID  Installation ID
  GH_APP_KEY              秘密鍵のパス（既定 ~/.hermes/secrets/gh-app.pem）

PyJWT も cryptography も無いので、JWT の署名は openssl に任せる。
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def make_jwt(app_id: str, key_path: str) -> str:
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    payload = {"iat": now - 60, "exp": now + 540, "iss": app_id}
    head = b64(json.dumps(header, separators=(",", ":")).encode())
    body = b64(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{head}.{body}"
    sig = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key_path],
        input=signing_input.encode(), capture_output=True, check=True,
    ).stdout
    return f"{signing_input}.{b64(sig)}"


def installation_token(app_id: str, inst_id: str, key_path: str) -> str:
    req = urllib.request.Request(
        f"{API}/app/installations/{inst_id}/access_tokens",
        method="POST",
        headers={
            "Authorization": f"Bearer {make_jwt(app_id, key_path)}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)["token"]
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:300]
        print(f"installation token の発行に失敗: HTTP {e.code} {detail}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    app_id = os.environ.get("GH_APP_ID")
    inst_id = os.environ.get("GH_APP_INSTALLATION_ID")
    key_path = os.environ.get("GH_APP_KEY", os.path.expanduser("~/.hermes/secrets/gh-app.pem"))
    if not app_id or not inst_id:
        print("GH_APP_ID と GH_APP_INSTALLATION_ID を設定する", file=sys.stderr)
        sys.exit(2)

    token = installation_token(app_id, inst_id, key_path)

    args = sys.argv[1:]
    if args and args[0] == "--token":
        print(token)
        return
    if args and args[0] == "--":
        args = args[1:]
    if not args:
        print("実行するコマンドを指定する", file=sys.stderr)
        sys.exit(2)

    env = dict(os.environ)
    env["GH_TOKEN"] = token
    env.pop("GITHUB_TOKEN", None)
    os.execvpe(args[0], args, env)


if __name__ == "__main__":
    main()
