#!/usr/bin/env python3
"""pre_tool_call: adb 経由の画面操作を、自アプリの宣言済み領域に限定する。

方針:
- タップ・スワイプは regions.json に載っている矩形の中だけ許可する
- 文字入力・キーイベント・monkey・sendevent は禁止する
- uiautomator dump は自パッケージが前面のときだけ許可する
- 他パッケージの停止・消去は禁止する

regions.json が無いときは全タップを拒否する（fail-safe）。
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


def load_policy():
    with open(POLICY_PATH, encoding="utf-8") as f:
        return json.load(f)


def load_regions(policy):
    src = Path(policy.get("tap_regions_source", ""))
    if not src.exists():
        return []
    try:
        data = json.loads(src.read_text(encoding="utf-8"))
    except Exception:
        return []
    return data.get("regions", [])


def inside(x, y, regions):
    for r in regions:
        if r["l"] <= x <= r["r"] and r["t"] <= y <= r["b"]:
            return r.get("name", "region")
    return None


def foreground_package(policy):
    adb = policy.get("tools", {}).get("adb") or shutil.which("adb") or "adb"
    try:
        out = subprocess.run(
            [adb, "shell", "dumpsys", "activity", "activities"],
            capture_output=True, text=True, timeout=6,
        )
        m = re.search(r"topResumedActivity=.*?\s([\w.]+)/", out.stdout)
        return m.group(1) if m else ""
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
    if "adb" not in cmd:
        ok()

    policy = load_policy()
    pkg = policy["package"]
    flat = re.sub(r"\s+", " ", cmd)

    # 明示的な拒否（他パッケージへの破壊的操作を除く）
    for denied in policy.get("deny_shell", []):
        if denied in flat:
            if denied in ("pm clear", "pm uninstall", "am force-stop"):
                if pkg in flat:
                    continue
                block(f"`{denied}` は自パッケージ以外に実行しない。")
            block(f"`{denied}` は使用しない（自動操作に当たる）。")

    if re.search(r"\binput\s+(text|keyevent|press|roll|draganddrop)\b", flat):
        block("文字入力・キーイベントは使用しない。画面操作はタップとスワイプのみ。")

    regions = load_regions(policy)

    taps = re.findall(r"\binput\s+tap\s+(\d+)\s+(\d+)", flat)
    for xs, ys in taps:
        name = inside(int(xs), int(ys), regions)
        if not name:
            block(
                f"({xs},{ys}) は自アプリの宣言済み領域の外。"
                "app/src/main/assets/regions.json に無い座標は押さない。"
            )

    swipes = re.findall(r"\binput\s+swipe\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)", flat)
    for x1, y1, x2, y2 in swipes:
        if not (inside(int(x1), int(y1), regions) and inside(int(x2), int(y2), regions)):
            block("スワイプの端点が宣言済み領域の外。")

    if "uiautomator dump" in flat:
        fg = foreground_package(policy)
        if fg != pkg:
            block(
                f"前面が {fg or '不明'} なので画面読取しない。"
                f"uiautomator dump は {pkg} が前面のときだけ許可する。"
            )

    ok()


if __name__ == "__main__":
    main()
