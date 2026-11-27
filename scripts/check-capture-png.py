#!/usr/bin/env python3
"""保存した PNG にオーバーレイのボタンが写っていないことを、画素で確かめる。

`CaptureOverlayButton` は `FLAG_SECURE` を付けた別ウィンドウなので、投影の絵（＝保存した
PNG）にも `adb shell screencap` の絵にも写らないはず。この道具はその2点を数字にする。

    # 1) 画面の見た目（screencap）と、保存した PNG を比べる
    adb exec-out screencap -p > /tmp/screen.png
    adb exec-out run-as net.st.hatsudesk cat files/captures/<名前>.png > /tmp/capture.png
    scripts/check-capture-png.py --capture /tmp/capture.png --screen /tmp/screen.png

    # 2) ボタンの矩形が分かっているなら、画面写真は無しでもよい
    scripts/check-capture-png.py --capture /tmp/capture.png --rect 965,819,115,115

出す数字:
- `diff box` … 画面写真と保存 PNG で色が違う領域。ボタンが出ている場所。
- `white in box` … その領域の「白っぽい画素」の割合。ボタンのリングは白（0xE6FFFFFF）なので、
  焼き付いていれば高い値になる。写っていなければ下の絵の色になる。
- `black in box` … その領域が真っ黒（≦8）の割合。端末によっては「ボタンの場所だけ黒く
  塗り潰される」ことがあるので、下の絵が出るのか黒くなるのかをここで見る。
- `rest mean` / `rest black` … ボタンの外側の平均輝度と、真っ黒な画素の割合。
  **画面全体が黒くなっていないか**はここで見る（投影・スクリーンショットの全体が
  黒くなる端末では、ボタンを出した瞬間にこれが 0 に落ちる）。

画素は 8bit の RGB/RGBA（Android の screencap と `Bitmap.compress(PNG)` の出力）だけを読む。
標準ライブラリだけで動く（依存を増やさない）。
"""

from __future__ import annotations

import argparse
import struct
import sys
import zlib

BLACK = 8
WHITE = 200


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def read_png(path: str) -> tuple[int, int, list[bytes]]:
    """PNG を (幅, 高さ, 行ごとの RGB バイト列) にする。8bit の非インタレースだけ。"""
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path}: PNG ではありません")

    pos = 8
    size = None
    idat = bytearray()
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos : pos + 8])
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or interlace != 0:
                raise SystemExit(f"{path}: 8bit 非インタレースの PNG だけを読めます")
            size = (width, height, color)
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if size is None:
        raise SystemExit(f"{path}: IHDR がありません")

    width, height, color = size
    channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(color)
    if channels is None:
        raise SystemExit(f"{path}: 色形式 {color} は読めません")
    raw = zlib.decompress(bytes(idat))
    stride = width * channels

    rows: list[bytes] = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset : offset + stride])
        offset += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up_left = previous[i - channels] if i >= channels else 0
                line[i] = (line[i] + _paeth(left, previous[i], up_left)) & 0xFF
        elif filter_type != 0:
            raise SystemExit(f"{path}: 未知のフィルタ {filter_type}")
        rows.append(bytes(line))
        previous = line

    if channels == 3:
        return width, height, rows
    # RGBA / グレー / グレー+α は RGB に均す。
    rgb_rows = []
    for line in rows:
        if channels == 4:
            rgb_rows.append(bytes(b for i in range(0, stride, 4) for b in line[i : i + 3]))
        elif channels == 2:
            rgb_rows.append(bytes(b for i in range(0, stride, 2) for _ in range(3) for b in (line[i],)))
        else:
            rgb_rows.append(bytes(b for i in range(stride) for _ in range(3) for b in (line[i],)))
    return width, height, rgb_rows


def luminance(pixel: bytes) -> int:
    return (pixel[0] * 299 + pixel[1] * 587 + pixel[2] * 114) // 1000


def diff_box(a: tuple[int, int, list[bytes]], b: tuple[int, int, list[bytes]]) -> tuple[int, int, int, int] | None:
    """2枚で色が違う画素を囲む矩形。無ければ None。"""
    if a[0] != b[0] or a[1] != b[1]:
        raise SystemExit(f"大きさが違います: {a[0]}x{a[1]} と {b[0]}x{b[1]}")
    width, height = a[0], a[1]
    left, top, right, bottom = width, height, -1, -1
    for y in range(height):
        row_a, row_b = a[2][y], b[2][y]
        if row_a == row_b:
            continue
        for x in range(width):
            i = x * 3
            if row_a[i : i + 3] != row_b[i : i + 3]:
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
    if right < 0:
        return None
    return left, top, right - left + 1, bottom - top + 1


def quad(text: str) -> tuple[int, int, int, int]:
    parts = text.split(",")
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("x,y,w,h の形で書いてください")
    return tuple(int(p) for p in parts)  # type: ignore[return-value]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--capture", required=True, help="保存した PNG（投影から撮った1枚）")
    parser.add_argument("--screen", help="同じ画面の screencap。--rect の代わりに差分で矩形を出す")
    parser.add_argument("--rect", type=quad, help="ボタンの矩形 x,y,w,h")
    args = parser.parse_args()

    capture = read_png(args.capture)
    width, height = capture[0], capture[1]
    box = args.rect
    if box is None:
        if not args.screen:
            raise SystemExit("--screen か --rect のどちらかが要ります")
        box = diff_box(capture, read_png(args.screen))
        if box is None:
            raise SystemExit("画面写真と保存 PNG に違いがありません（ボタンが出ていない可能性）")

    x, y, w, h = box
    x2, y2 = min(width, x + w), min(height, y + h)
    x, y = max(0, x), max(0, y)
    if x >= x2 or y >= y2:
        raise SystemExit(f"矩形 {box} が画像の外です（{width}x{height}）")

    inside = 0
    white = 0
    black = 0
    for row in capture[2][y:y2]:
        for col in range(x, x2):
            pixel = row[col * 3 : col * 3 + 3]
            inside += 1
            if pixel[0] >= WHITE and pixel[1] >= WHITE and pixel[2] >= WHITE:
                white += 1
            if luminance(pixel) <= BLACK:
                black += 1

    rest = 0
    rest_sum = 0
    rest_black = 0
    for row_index, row in enumerate(capture[2]):
        for col in range(width):
            if x <= col < x2 and y <= row_index < y2:
                continue
            value = luminance(row[col * 3 : col * 3 + 3])
            rest += 1
            rest_sum += value
            if value <= BLACK:
                rest_black += 1

    print(f"image          {width}x{height}")
    print(f"box            x={x} y={y} w={x2 - x} h={y2 - y}")
    print(f"white in box   {100.0 * white / inside:5.1f}%")
    print(f"black in box   {100.0 * black / inside:5.1f}%")
    print(f"rest mean      {rest_sum / rest:6.1f}")
    print(f"rest black     {100.0 * rest_black / rest:5.1f}%")

    # 目安。ボタンの白いリングは 44dp の円の縁なので、焼き付いていれば white in box は
    # ふつう 10% を超える。全体が黒くなる端末では rest mean が一桁に落ちる。
    ok = True
    if white >= inside * 0.10 and black <= inside * 0.10:
        print("NG: ボタンらしい白い画素が保存 PNG のボタン領域に写っています")
        ok = False
    if rest_sum / rest <= 12.0 and rest_black >= rest * 0.98:
        print("NG: ボタンの外側がほぼ真っ黒です（画面全体が黒くなっている可能性）")
        ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
