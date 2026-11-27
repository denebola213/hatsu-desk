#!/usr/bin/env python3
"""main の健全性を点検する。QA が定期実行する想定。

見るもの:
- ブランチ保護が有効か（管理者を含む・承認必須・force push 不可）
- リポジトリが public か（保護の前提）
- main の履歴が PR 由来か（squash の "(#N)" で判定）
- コントリビューター以外の PR が残っていないか

問題があれば終了コード 1。出力はそのまま Issue の本文にできる。
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
GH = ROOT / "harness" / "tools" / "gh"
REPO = "denebola213/hatsu-desk"
BASELINE = ROOT / "harness" / "audit-baseline"

problems: list[str] = []
notes: list[str] = []


def gh_api(path: str):
    out = subprocess.run([str(GH), "api", path], capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        return None
    try:
        return json.loads(out.stdout)
    except Exception:
        return None


def check_protection() -> None:
    prot = gh_api(f"repos/{REPO}/branches/main/protection")
    if prot is None:
        problems.append("ブランチ保護が取れない（未設定か権限不足）")
        return
    if not (prot.get("enforce_admins") or {}).get("enabled"):
        problems.append("enforce_admins が無効。管理者が迂回できる")
    reviews = prot.get("required_pull_request_reviews") or {}
    if (reviews.get("required_approving_review_count") or 0) < 1:
        problems.append("承認必須件数が 0")
    if (prot.get("allow_force_pushes") or {}).get("enabled"):
        problems.append("force push が許可されている")
    if not (prot.get("required_linear_history") or {}).get("enabled"):
        notes.append("linear history が無効。squash 前提が崩れる")


def check_visibility() -> None:
    repo = gh_api(f"repos/{REPO}")
    if repo is None:
        problems.append("リポジトリ情報が取れない")
        return
    if repo.get("visibility") != "public":
        problems.append("public でない。この保護構成は private 無料枠では組めない")


def check_history() -> None:
    if not BASELINE.exists():
        notes.append("harness/audit-baseline が無いので履歴の点検を省略")
        return
    base = BASELINE.read_text().strip()
    subprocess.run(["git", "-C", str(ROOT), "fetch", "-q", "origin", "main"], check=False)
    out = subprocess.run(
        ["git", "-C", str(ROOT), "log", "--format=%h\t%s", f"{base}..origin/main"],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        notes.append("履歴が読めない（origin/main があるか確認）")
        return
    for line in out.stdout.splitlines():
        if not line.strip():
            continue
        sha, _, subject = line.partition("\t")
        if "(#" not in subject:
            problems.append(f"PR 由来でないコミット: {sha} {subject}")


def check_outsiders() -> None:
    prs = gh_api(f"repos/{REPO}/pulls?state=open&per_page=50")
    if prs is None:
        return
    for pr in prs:
        assoc = pr.get("author_association")
        if assoc not in ("OWNER", "MEMBER", "COLLABORATOR"):
            notes.append(f"外部からの PR が開いている: #{pr.get('number')} {pr.get('user', {}).get('login')}")


def main() -> int:
    check_protection()
    check_visibility()
    check_history()
    check_outsiders()

    for p in problems:
        print(f"NG  {p}")
    for n in notes:
        print(f"--  {n}")
    if not problems:
        print("OK  main の前提は保たれている")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
