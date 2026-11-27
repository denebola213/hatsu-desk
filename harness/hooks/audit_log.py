#!/usr/bin/env python3
"""post_tool_call: 端末・git・gh の操作を JSONL で記録する。

ゲートではなく観測。QA が「何が main に入ったか」を後から辿るために使う。
"""
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

LOG = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))) / "logs" / "dev-org.jsonl"


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        print("{}")
        return

    tool = payload.get("tool_name") or ""
    inp = payload.get("tool_input") or {}
    text = inp.get("command") or inp.get("path") or ""
    if not any(k in text for k in ("adb", "git ", "gh ", "gradle", "podman")):
        print("{}")
        return

    entry = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "session": payload.get("session_id"),
        "profile": payload.get("profile"),
        "tool": tool,
        "cwd": payload.get("cwd"),
        "text": text[:600],
    }
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    print("{}")


if __name__ == "__main__":
    main()
