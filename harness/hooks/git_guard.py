#!/usr/bin/env python3
"""pre_tool_call: git / gh の操作をリポジトリ単位で制限する（ガードレール）。

境界ではない。main を守っているのは GitHub のブランチ保護と認証の分離で、
このフックは書き方を変えれば通る。誤操作を減らすために置いている。

これは**ガードレール**であって安全装置ではない。main を守っているのは
GitHub 側のブランチ保護（直 push 不可・承認必須・管理者を含む）。この
フックは「うっかり main を触る」事故を手前で減らすためのもの。コマンド
文字列を見て判断する以上、書き方を変えれば通る。

方針:
- 保護リポジトリの main への直接 push と main 上での commit を禁止する
- force 系 push を禁止する
- PR マージは squash のみ、かつレビュー承認（マーカー）済みのみ
- gh api 経由のマージ・ref 書き換えも塞ぐ
- 破壊的な git は人間の承認に回す

内部エラーは exit 0（fail_closed 側で拾う）。
"""
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
POLICY_PATH = HERE / "policy.json"
BRANCHES = ("main", "master")


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False))
    sys.exit(0)


def ok():
    emit({})


def block(reason):
    emit({"decision": "block", "reason": reason})


def approve(message, key):
    emit({"action": "approve", "message": message, "rule_key": key})


def tokenize(s):
    try:
        lex = shlex.shlex(s, posix=True, punctuation_chars=True)
        lex.whitespace_split = True
        return list(lex)
    except Exception:
        return s.split()


def load_policy():
    with open(POLICY_PATH, encoding="utf-8") as f:
        return json.load(f)


def git_current_branch(path):
    try:
        out = subprocess.run(
            ["git", "-C", path, "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
        return out.stdout.strip() if out.returncode == 0 else ""
    except Exception:
        return ""


def git_root(path):
    try:
        out = subprocess.run(
            ["git", "-C", path, "rev-parse", "--show-toplevel"],
            capture_output=True, text=True, timeout=5,
        )
        return out.stdout.strip() if out.returncode == 0 else ""
    except Exception:
        return ""


def origin_slug(root):
    try:
        out = subprocess.run(
            ["git", "-C", root, "remote", "get-url", "origin"],
            capture_output=True, text=True, timeout=5,
        )
        url = out.stdout.strip()
    except Exception:
        return ""
    m = re.search(r"[:/]([\w.-]+/[\w.-]+?)(?:\.git)?$", url)
    return m.group(1) if m else ""


def path_is_protected(path, policy):
    root = git_root(path)
    if not root:
        return None
    root = str(Path(root).resolve())
    for p in policy.get("protected_repos", []):
        p = str(Path(p).resolve())
        if root == p or root.startswith(p + os.sep):
            return root
    return None


def slug_is_protected(slug, policy):
    if not slug:
        return False
    return slug.lower() in {s.lower() for s in policy.get("repos", [])}


def shell_c_index(toks):
    """-c または -lc / -ic のような複合短オプションの位置を返す。"""
    for i, a in enumerate(toks):
        if a.startswith("--"):
            continue
        if re.fullmatch(r"-[a-zA-Z]*c[a-zA-Z]*", a):
            return i
    return None


def git_invocations(toks, depth=0):
    """(subcommand, args, workdir) を列挙する。shell の -c も辿る。"""
    for i, t in enumerate(toks):
        base = t.rsplit("/", 1)[-1]
        if base == "git":
            i2 = i + 1
            workdir = None
            while i2 < len(toks):
                a = toks[i2]
                if a == "-C" and i2 + 1 < len(toks):
                    workdir = toks[i2 + 1]
                    i2 += 2
                    continue
                if a == "-c" and i2 + 1 < len(toks):
                    i2 += 2
                    continue
                if a.startswith("--git-dir") or a.startswith("--work-tree"):
                    i2 += 1 if "=" in a else 2
                    continue
                if a.startswith("-"):
                    i2 += 1
                    continue
                yield a, toks[i2 + 1:], workdir
                break
        elif base in ("sh", "bash", "zsh", "dash") and depth < 3:
            k = shell_c_index(toks)
            if k is not None and k + 1 < len(toks):
                yield from git_invocations(tokenize(toks[k + 1]), depth + 1)


def gh_invocation(toks):
    for i, t in enumerate(toks):
        if t.rsplit("/", 1)[-1] != "gh":
            continue
        repo = None
        i2 = i + 1
        while i2 < len(toks):
            a = toks[i2]
            if a in ("--repo", "-R") and i2 + 1 < len(toks):
                repo = toks[i2 + 1]
                i2 += 2
                continue
            if a.startswith("--repo="):
                repo = a.split("=", 1)[1]
                i2 += 1
                continue
            if a.startswith("-"):
                i2 += 1
                continue
            return a, toks[i2 + 1:], repo
    return None, [], None


def gate_merge(args, policy, cwd):
    if "--squash" not in args:
        block("マージは `gh pr merge <n> --squash --delete-branch` のみ許可。")
    nums = [a for a in args if a.isdigit()]
    if not nums:
        block("PR 番号を明示する（`gh pr merge <n> --squash`）。")
    pr = nums[0]
    gh = policy.get("tools", {}).get("gh") or shutil.which("gh") or "gh"
    marker = policy.get("review_marker", "<!-- agent-dev-org:review-approved -->")
    try:
        out = subprocess.run(
            [gh, "pr", "view", pr, "--json", "reviewDecision,state,isDraft,comments"],
            capture_output=True, text=True, timeout=15, cwd=cwd,
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
    approved = decision == "APPROVED" or any(
        marker in (c.get("body") or "") for c in (data.get("comments") or [])
    )
    if data.get("isDraft"):
        block("ドラフト PR はマージしない。")
    if state != "OPEN":
        block(f"PR #{pr} は {state or '不明'}。マージできない。")
    if not approved:
        block(
            f"PR #{pr} はレビュー未承認（reviewDecision={decision or 'NONE'}）。"
            "レビュー担当が承認マーカーをコメントするまでマージしない。"
        )


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        ok()

    if os.environ.get("HOOK_DEBUG"):
        Path("/tmp/hook-payload.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=1))

    if payload.get("tool_name") != "terminal":
        ok()

    cmd = (payload.get("tool_input") or {}).get("command") or ""
    cwd = payload.get("cwd") or os.getcwd()
    policy = load_policy()
    toks = tokenize(cmd)

    # --- git ---
    for sub, args, workdir in git_invocations(toks):
        here = workdir or cwd
        root = path_is_protected(here, policy)
        if not root:
            continue
        flags = [a for a in args if a.startswith("-")]
        positional = [a for a in args if not a.startswith("-")]

        if sub == "push":
            if any(f == "-f" or f.startswith("--force") for f in flags):
                block("保護リポジトリへの force push は禁止。履歴は書き換えない。")
            bad = []
            for r in positional:
                target = r.split(":")[-1] if ":" in r else r
                if target in BRANCHES:
                    bad.append(r)
            if bad:
                block(f"main への直接 push は禁止（{bad[0]}）。ブランチを切って PR を作る。")
            if not positional:
                br = git_current_branch(root)
                if br in BRANCHES:
                    block(f"現在 {br} 上にいる。ブランチを切ってから push する。")

        elif sub == "commit":
            br = git_current_branch(root)
            if br in BRANCHES:
                block(f"{br} 上での commit は禁止。`git switch -c impl/<issue>-<slug>` で作業する。")

        elif sub in ("reset", "clean", "checkout", "restore"):
            joined = " ".join(args)
            if ((sub == "reset" and "--hard" in args)
                    or (sub == "clean" and re.search(r"-[\w]*f", joined))
                    or (sub in ("checkout", "restore") and "--" in args and "." in args)):
                approve("作業ツリーを破棄する操作。実行してよいか。", "git-destructive")

    # --- gh ---
    sub, args, repo = gh_invocation(toks)
    if sub:
        root = path_is_protected(cwd, policy)
        origin = origin_slug(root) if root else ""
        protected = bool(root) or slug_is_protected(repo, policy) or slug_is_protected(origin, policy)
        if protected:
            if sub == "pr" and args and args[0] == "merge":
                gate_merge(args[1:], policy, root or cwd)
            elif sub == "api":
                joined = " ".join(args)
                if re.search(r"pulls/\d+/merge", joined) or (
                        re.search(r"-X\s*(POST|PUT|PATCH|DELETE)", joined) and "git/refs" in joined):
                    block("gh api 経由のマージ・ref 書き換えは禁止。`gh pr merge` を使う。")

    ok()


if __name__ == "__main__":
    main()
