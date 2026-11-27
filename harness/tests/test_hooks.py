#!/usr/bin/env python3
"""ハーネスのフックを合成ペイロードで検証する。

レビューで見つかった迂回（-C、引用符、sh -c、変数展開、refspec）を回帰として固定する。
python3 harness/tests/test_hooks.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HOOKS = Path(__file__).resolve().parent.parent / "hooks"
PKG = "net.st.hatsudesk"


def run(hooks_dir, hook, cmd, cwd):
    payload = {
        "hook_event_name": "pre_tool_call",
        "tool_name": "terminal",
        "tool_input": {"command": cmd},
        "cwd": str(cwd),
        "session_id": "test",
        "profile": "default",
    }
    p = subprocess.run(
        [sys.executable, str(hooks_dir / hook)],
        input=json.dumps(payload), capture_output=True, text=True,
        env=dict(os.environ), timeout=40,
    )
    try:
        d = json.loads(p.stdout.strip() or "{}")
    except Exception:
        d = {"_raw": p.stdout[:200], "_err": p.stderr[:200]}
    if d.get("decision") == "block":
        return "block", d.get("reason", "")
    if d.get("action") == "approve":
        return "approve", d.get("message", "")
    if "_raw" in d:
        return "error", json.dumps(d, ensure_ascii=False)[:200]
    return "allow", ""


def setup():
    tmp = Path(tempfile.mkdtemp(prefix="hooktest-"))

    # フックは policy.json を自分の隣から読む。テスト用の複製を作り、
    # その隣にテスト用の policy.json を置く（本番コードに継ぎ目を作らない）。
    hooks = tmp / "hooks"
    hooks.mkdir()
    for f in HOOKS.glob("*.py"):
        shutil.copy2(f, hooks / f.name)

    # 前面パッケージの判定を差し替えるため、adb の代役を policy で指す。
    fake_adb = tmp / "adb"
    fake_adb.write_text(
        '#!/bin/sh\necho "topResumedActivity=ActivityRecord{1 u0 com.other.app/.Main t1}"\n'
    )
    fake_adb.chmod(0o755)

    repo = tmp / "repo"
    repo.mkdir()
    subprocess.run(["git", "init", "-q", "-b", "main", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "t@example.com"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "t"], check=True)
    (repo / "a.txt").write_text("x")
    subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "init"], check=True)

    regions = tmp / "regions.json"
    regions.write_text(json.dumps({
        "version": 1,
        "regions": [{"name": "capture", "l": 0, "t": 0, "r": 100, "b": 100}],
    }))
    policy = hooks / "policy.json"
    policy.write_text(json.dumps({
        "protected_repos": [str(repo)],
        "repos": ["denebola213/hatsu-desk"],
        "package": PKG,
        "review_marker": "<!-- agent-dev-org:review-approved -->",
        "tap_regions_source": str(regions),
        "tools": {"adb": str(fake_adb)},
    }, ensure_ascii=False))
    return tmp, repo, hooks


CASES = [
    # (hook, command, expect, 説明)
    ("git_guard.py", "git push origin main", "block", "main 直 push"),
    ("git_guard.py", 'git push origin "main"', "block", "引用した main"),
    ("git_guard.py", "git push origin main --force", "block", "main + force"),
    ("git_guard.py", "git -C {repo} push origin main", "block", "-C 経由の push"),
    ("git_guard.py", "git -C {repo} push --force", "block", "-C 経由の force"),
    ("git_guard.py", "git -c core.hooksPath=/dev/null push origin main", "block", "-c 経由"),
    ("git_guard.py", "sh -c 'git push origin main'", "block", "sh -c 経由"),
    ("git_guard.py", "bash -lc 'git -C {repo} push origin main'", "block", "bash -lc 経由"),
    ("git_guard.py", "git push origin HEAD:main", "block", "HEAD:main"),
    ("git_guard.py", "git push origin +main:main", "block", "+main:main"),
    ("git_guard.py", "git push --force-with-lease origin impl/1-x", "block", "force-with-lease"),
    ("git_guard.py", "git push", "block", "引数なし push（main 上）"),
    ("git_guard.py", "git commit -m x", "block", "main 上で commit"),
    ("git_guard.py", "git -C {repo} commit -m x", "block", "-C 経由の commit"),
    ("git_guard.py", "gh pr merge 999999 --squash", "block", "未承認マージ（PR 不在）"),
    ("git_guard.py", "gh pr merge 999999", "block", "squash なし"),
    ("git_guard.py", "gh api -X PUT repos/o/r/pulls/1/merge", "block", "gh api マージ"),
    ("git_guard.py", "git push origin impl/1-x", "allow", "作業ブランチへの push"),
    ("git_guard.py", "git status", "allow", "status"),
    ("git_guard.py", "git log --oneline -3", "allow", "log"),
    ("git_guard.py", "ls -la", "allow", "無関係コマンド"),
    ("git_guard.py", "gh pr list", "allow", "pr list"),
    ("adb_guard.py", "adb shell input tap 500 1200", "block", "領域外タップ"),
    ("adb_guard.py", "adb shell input tap 10 10", "allow", "領域内タップ"),
    ("adb_guard.py", "adb shell 'input tap 500 1200'", "block", "引用したタップ"),
    ("adb_guard.py", "adb shell input tap $X $Y", "block", "変数展開のタップ"),
    ("adb_guard.py", "adb shell input text hello", "block", "文字入力"),
    ("adb_guard.py", "adb shell input keyevent 4", "block", "キーイベント"),
    ("adb_guard.py", "adb shell monkey -p net.st.hatsudesk 10", "block", "monkey"),
    ("adb_guard.py", "adb shell pm clear com.other.app # net.st.hatsudesk", "block", "他パッケージの pm clear（囮コメント）"),
    ("adb_guard.py", "adb shell pm clear net.st.hatsudesk", "allow", "自パッケージの pm clear"),
    ("adb_guard.py", "adb shell uiautomator 'dump' /sdcard/u.xml", "block", "引用した dump（前面が別）"),
    ("adb_guard.py", "adb shell uiautomator dump /sdcard/u.xml", "block", "前面が別パッケージ"),
    ("adb_guard.py", "adb install -r app.apk", "allow", "install"),
    ("adb_guard.py", "adb shell input swipe 10 10 90 90 300", "allow", "領域内スワイプ"),
    ("adb_guard.py", "adb shell input swipe 10 10 900 900 300", "block", "領域外スワイプ"),
]


def main():
    tmp, repo, hooks = setup()
    failures = 0
    print(f"{'hook':<12} {'expect':<7} {'got':<7} コマンド")
    for hook, cmd, expect, note in CASES:
        c = cmd.format(repo=repo)
        got, reason = run(hooks, hook, c, repo)
        okmark = "  " if got == expect else "× "
        if got != expect:
            failures += 1
        print(f"{okmark}{hook:<10} {expect:<7} {got:<7} {note}")
        if got != expect:
            print(f"    cmd: {c}\n    reason: {reason}")
    print(f"\n{len(CASES) - failures}/{len(CASES)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
