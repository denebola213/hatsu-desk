#!/usr/bin/env python3
"""pre_tool_call: adb 経由の画面操作を、自アプリの宣言済み領域に限定する。

文字列一致では引用符・変数展開で抜けられる。adb のサブコマンドをトークンに
分解し、数値は「純粋な数字リテラル」だけを受け付ける（$X 等は拒否）。

方針:
- タップ・スワイプは regions.json に載っている矩形の中だけ許可する
- 座標が数字リテラルでないものは拒否する
- 文字入力・キーイベント・monkey・sendevent は禁止する
- uiautomator dump は自パッケージが前面のときだけ許可する
- 他パッケージの停止・消去は禁止する

regions.json が無いときは全タップを拒否する（fail-safe）。
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
POLICY_PATH = Path(os.environ.get("HERMES_HOOK_POLICY", HERE / "policy.json"))
INPUT_DENY = {"text", "keyevent", "press", "roll", "draganddrop", "motionevent", "tap"}


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False))
    sys.exit(0)


def ok():
    emit({})


def block(reason):
    emit({"decision": "block", "reason": reason})


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


def load_regions(policy):
    src = Path(policy.get("tap_regions_source", ""))
    if not src.exists():
        return []
    try:
        return json.loads(src.read_text(encoding="utf-8")).get("regions", [])
    except Exception:
        return []


def inside(x, y, regions):
    for r in regions:
        if r["l"] <= x <= r["r"] and r["t"] <= y <= r["b"]:
            return r.get("name", "region")
    return None


def as_int(tok):
    """数字リテラルのみ許可する。$X や `cmd` は拒否。"""
    return int(tok) if re.fullmatch(r"\d+", tok) else None


def foreground_package(policy):
    fake = os.environ.get("HERMES_HOOK_FAKE_FG")
    if fake is not None:
        return fake
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


def shell_c_index(toks):
    """-c または -lc / -ic のような複合短オプションの位置を返す。"""
    for i, a in enumerate(toks):
        if a.startswith("--"):
            continue
        if re.fullmatch(r"-[a-zA-Z]*c[a-zA-Z]*", a):
            return i
    return None


def adb_device_commands(toks, depth=0):
    """adb の shell/exec-out に渡る端末側コマンドを列挙する。"""
    for i, t in enumerate(toks):
        base = t.rsplit("/", 1)[-1]
        if base == "adb":
            i2 = i + 1
            while i2 < len(toks):
                a = toks[i2]
                if a in ("-s", "-H", "-P", "-L", "-t") and i2 + 1 < len(toks):
                    i2 += 2
                    continue
                if a.startswith("-"):
                    i2 += 1
                    continue
                if a in ("shell", "exec-out"):
                    rest = toks[i2 + 1:]
                    yield " ".join(rest)
                    break
                return
        elif base in ("sh", "bash", "zsh", "dash") and depth < 3:
            k = shell_c_index(toks)
            if k is not None and k + 1 < len(toks):
                yield from adb_device_commands(tokenize(toks[k + 1]), depth + 1)


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        ok()

    if os.environ.get("HOOK_DEBUG"):
        Path("/tmp/hook-adb-payload.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=1))

    if payload.get("tool_name") != "terminal":
        ok()

    cmd = (payload.get("tool_input") or {}).get("command") or ""
    if "adb" not in cmd:
        ok()

    policy = load_policy()
    pkg = policy["package"]
    regions = load_regions(policy)

    for device_cmd in adb_device_commands(tokenize(cmd)):
        toks = tokenize(device_cmd)
        if not toks:
            continue

        # monkey / sendevent はトークン一致で拒否（部分一致では誤爆・迂回する）
        for t in toks:
            b = t.rsplit("/", 1)[-1]
            if b in ("monkey", "sendevent"):
                block(f"`{b}` は使用しない（自動操作に当たる）。")

        # pm / am の対象パッケージは引数トークンで判定する
        for idx, t in enumerate(toks):
            if t == "pm" and idx + 1 < len(toks) and toks[idx + 1] in ("clear", "uninstall"):
                target = toks[idx + 2] if idx + 2 < len(toks) else ""
                if target != pkg:
                    block(f"`pm {toks[idx + 1]}` は自パッケージ以外に実行しない（対象={target or '不明'}）。")
            if t == "am" and idx + 1 < len(toks) and toks[idx + 1] == "force-stop":
                target = toks[idx + 2] if idx + 2 < len(toks) else ""
                if target != pkg:
                    block(f"`am force-stop` は自パッケージ以外に実行しない（対象={target or '不明'}）。")

        if toks[0] == "input" and len(toks) >= 2:
            kind = toks[1]
            if kind in ("text", "keyevent", "press", "roll", "draganddrop", "motionevent"):
                block("文字入力・キーイベントは使用しない。画面操作はタップとスワイプのみ。")
            if kind == "tap":
                if len(toks) < 4:
                    block("タップ座標を数字リテラルで指定する。")
                x, y = as_int(toks[2]), as_int(toks[3])
                if x is None or y is None:
                    block("タップ座標は数字リテラルで指定する（変数展開・引用は不可）。")
                if not inside(x, y, regions):
                    block(
                        f"({x},{y}) は自アプリの宣言済み領域の外。"
                        "app/src/main/assets/regions.json に無い座標は押さない。"
                    )
            if kind == "swipe":
                if len(toks) < 6:
                    block("スワイプ座標を数字リテラルで指定する。")
                vals = [as_int(t) for t in toks[2:6]]
                if any(v is None for v in vals):
                    block("スワイプ座標は数字リテラルで指定する。")
                if not (inside(vals[0], vals[1], regions) and inside(vals[2], vals[3], regions)):
                    block("スワイプの端点が宣言済み領域の外。")

        if "uiautomator" in toks and "dump" in toks:
            fg = foreground_package(policy)
            if fg != pkg:
                block(
                    f"前面が {fg or '不明'} なので画面読取しない。"
                    f"uiautomator dump は {pkg} が前面のときだけ許可する。"
                )

    ok()


if __name__ == "__main__":
    main()
