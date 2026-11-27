#!/usr/bin/env bash
# harness を Hermes に取り付ける。リポジトリが正で、~/.hermes へは symlink で入れる。
# 何度実行しても同じ状態になる。
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="$REPO/harness"

resolve_home() {
  if [ -n "${HERMES_HOME:-}" ] && [ -f "$HERMES_HOME/config.yaml" ]; then
    echo "$HERMES_HOME"; return
  fi
  for c in /home/hermeswebui/.hermes "$HOME/.hermes"; do
    if [ -f "$c/config.yaml" ]; then echo "$c"; return; fi
  done
  echo "HERMES_HOME を特定できない" >&2; exit 1
}
HH="$(resolve_home)"
echo "repo=$REPO"
echo "hermes_home=$HH"

chmod +x "$HARNESS"/hooks/*.py

# 1) フックのスクリプト
mkdir -p "$HH/agent-hooks"
for f in "$HARNESS"/hooks/*.py; do
  ln -sfn "$f" "$HH/agent-hooks/$(basename "$f")"
done
ln -sfn "$HARNESS/hooks/policy.json" "$HH/agent-hooks/policy.json"

# 2) スキル
mkdir -p "$HH/skills/software-development"
ln -sfn "$HARNESS/skills/agent-dev-org" "$HH/skills/software-development/agent-dev-org"

# 3) hooks ブロック（既にあれば触らない）
if grep -q '^hooks:' "$HH/config.yaml"; then
  echo "config.yaml に hooks: があるため追記しない（手で確認する）"
else
  cp "$HH/config.yaml" "$HH/config.yaml.bak-$(date +%Y%m%d%H%M%S)"
  cat >> "$HH/config.yaml" <<YAML

# --- harness/install.sh が追記。ここから下は harness が管理する ---
hooks:
  pre_tool_call:
    - matcher: "terminal"
      command: "$HH/agent-hooks/git_guard.py"
      timeout: 20
      fail_closed: true
    - matcher: "terminal"
      command: "$HH/agent-hooks/adb_guard.py"
      timeout: 15
      fail_closed: true
  post_tool_call:
    - matcher: "terminal"
      command: "$HH/agent-hooks/audit_log.py"
      timeout: 5
# --- ここまで ---
YAML
  echo "config.yaml に hooks を追記した"
fi

# 4) 同意（gateway は対話プロンプトを出さないので明示的に許可する）
AL="$HH/shell-hooks-allowlist.json"
python3 - "$AL" "$HH/agent-hooks" <<'PY'
import json, sys
from pathlib import Path
path, hooks = Path(sys.argv[1]), Path(sys.argv[2])
want = [
    ("pre_tool_call", str(hooks / "git_guard.py")),
    ("pre_tool_call", str(hooks / "adb_guard.py")),
    ("post_tool_call", str(hooks / "audit_log.py")),
]
data = {"approvals": []}
if path.exists():
    try:
        data = json.loads(path.read_text())
    except Exception:
        data = {"approvals": []}
cur = {(a["event"], a["command"]) for a in data.get("approvals", [])}
for e, c in want:
    if (e, c) not in cur:
        data.setdefault("approvals", []).append({"event": e, "command": c})
path.write_text(json.dumps(data, indent=2) + "\n")
print("allowlist:", len(data["approvals"]), "entries")
PY

# 5) git の pre-push（リポジトリ内のフックを git 自身に呼ばせる）
chmod +x "$HARNESS/githooks/pre-push"
git -C "$REPO" config core.hooksPath "$HARNESS/githooks"
echo "core.hooksPath=$(git -C "$REPO" config core.hooksPath)"

echo "== verify =="
HERMES_BIN="$(command -v hermes || echo /app/venv/bin/hermes)"
"$HERMES_BIN" hooks list 2>&1 | tail -12
