#!/usr/bin/env python3
"""pre_tool_call: git / gh の操作をリポジトリ単位で制限する。

方針:
- 保護リポジトリの main への直接 push と main 上での commit を禁止する
- force push を禁止する
- PR マージは squash のみ、かつレビュー承認済みのみ
- 破壊的な git は人間の承認に回す

判定不能・内部エラーは exit 0 で通す（fail_closed 側で拾う）。
"""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
POLICY_PATH = HERE / "policy.json"


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False))
    sys.exit(0)


def ok():
    emit({})


def block(reason):
    emit({"decision": "block", "reason": reason})


def approve(message, key):
    emit({"action": "approve", "message": message, "rule_key": key})


def load_policy():
    with open(POLICY_PATH, encoding="utf-8") as f:
        return json.load(f)


def current_branch(repo):
    try:
        out = subprocess.run(
            ["git", "-C", repo, "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
        return out.stdout.strip()
    except Exception:
        return ""


def repo_root(path):
    try:
        out = subprocess.run(
            ["git", "-C", path, "rev-parse", "--show-toplevel"],
            capture_output=True, text=True, timeout=5,
        )
        return out.stdout.strip()
    except Exception:
        return ""


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        ok()

    if payload.get("tool_name") != "terminal":
        ok()

    cmd = (payload.get("tool_input") or {}).get("command") or ""
    cwd = payload.get("cwd") or os.getcwd()
    policy = load_policy()
    protected = [str(Path(p).resolve()) for p in policy.get("protected_repos", [])]

    # 対象リポジトリを決める（-C があれば優先、なければ cwd）
    m = re.search(r"git\s+-C\s+(\S+)", cmd)
    probe = m.group(1).strip("'\"") if m else cwd
    root = repo_root(probe)
    if not root:
        ok()
    root = str(Path(root).resolve())
    if not any(root == p or root.startswith(p + os.sep) for p in protected):
        ok()

    flat = re.sub(r"\s+", " ", cmd)

    # 1) force push
    if re.search(r"git\s+push", flat) and re.search(
        r"--force(-with-lease)?\b|(^|\s)-f(\s|$)", flat
    ):
        block("保護リポジトリへの force push は禁止。履歴は書き換えない。")

    # 2) main への push
    if re.search(r"git\s+push", flat):
        after = flat.split("git push", 1)[1]
        tokens = [t for t in after.split() if not t.startswith("-")]
        refspecs = [t for t in tokens if "/" in t or t in ("main", "master")]
        bad = [t for t in refspecs if re.search(r"(^|[:/])(main|master)$", t)]
        if bad:
            block(f"main への直接 push は禁止（{bad[0]}）。ブランチを切って PR を作る。")
        if not refspecs:
            br = current_branch(root)
            if br in ("main", "master"):
                block(f"現在 {br} 上にいる。ブランチを切ってから push する。")

    # 3) main 上での commit
    if re.search(r"git\s+commit", flat) and not re.search(r"git\s+-C", flat):
        br = current_branch(root)
        if br in ("main", "master"):
            block(f"{br} 上での commit は禁止。`git switch -c impl/<issue>-<slug>` で作業する。")

    # 4) PR マージのゲート
    if re.search(r"gh\s+pr\s+merge", flat):
        if "--squash" not in flat:
            block("マージは `gh pr merge <n> --squash --delete-branch` のみ許可。")
        m = re.search(r"gh\s+pr\s+merge\s+(\d+)", flat)
        if not m:
            block("PR 番号を明示する（`gh pr merge <n> --squash`）。")
        pr = m.group(1)
        gh = policy.get("tools", {}).get("gh") or shutil.which("gh") or "gh"
        marker = policy.get("review_marker", "<!-- agent-dev-org:review-approved -->")
        try:
            out = subprocess.run(
                [gh, "pr", "view", pr, "--json", "reviewDecision,state,isDraft,comments"],
                capture_output=True, text=True, timeout=15, cwd=root,
            )
            if out.returncode != 0 or not out.stdout.strip():
                block(f"PR #{pr} の状態を取得できない。マージしない。（{out.stderr.strip()[:120]}）")
            data = json.loads(out.stdout)
        except SystemExit:
            raise
        except Exception:
            block("PR のレビュー状態を取得できないのでマージしない。")
            return
        decision = data.get("reviewDecision") or ""
        state = data.get("state") or ""
        draft = data.get("isDraft")
        approved = decision == "APPROVED" or any(
            marker in ((c.get("body") or "")) for c in (data.get("comments") or [])
        )
        if draft:
            block("ドラフト PR はマージしない。")
        if state != "OPEN":
            block(f"PR #{pr} は {state or '不明'}。マージできない。")
        if not approved:
            block(
                f"PR #{pr} はレビュー未承認（reviewDecision={decision or 'NONE'}）。"
                "レビュー担当が承認マーカーをコメントするまでマージしない。"
            )

    # 5) 破壊的な git は承認に回す
    if re.search(r"git\s+reset\s+--hard|git\s+clean\s+-[a-zA-Z]*f|git\s+checkout\s+--\s+\.", flat):
        approve("作業ツリーを破棄する操作。実行してよいか。", "git-destructive")

    ok()


if __name__ == "__main__":
    main()
