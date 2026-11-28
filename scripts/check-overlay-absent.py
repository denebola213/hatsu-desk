#!/usr/bin/env python3
"""保存した PNG にオーバーレイの取得ボタンが写っていないことを画素で確かめる。

受入条件「保存した PNG に自分のボタンが写っていない」を、目視ではなく数値で見る道具。

考え方: **同じ画面を2枚そろえて、ボタンの矩形だけを比べる。**

- `--visible` … ボタンが見えている状態の `adb exec-out screencap -p`
- `--saved`   … アプリが保存した PNG（ボタンは隠してから撮っている）

同じ画面なので、ボタンの矩形の外は2枚で一致するはず。矩形の中だけが違えば、
ボタンは「見えている画像にだけ」写っていて、保存した画像には写っていない。
背景の色に依らない（白でも暗い色でも同じ判定になる）。

準備:

    adb shell uiautomator dump /sdcard/ui.xml
    adb shell cat /sdcard/ui.xml | grep -o 'content-desc="いまの画面を1枚保存"[^>]*'
    adb exec-out screencap -p > visible.png
    # ボタンを押して保存したあと
    adb exec-out run-as net.st.hatsudesk cat files/captures/<name>.png > saved.png

実行:

    python3 scripts/check-overlay-absent.py --rect 964 819 1080 935 \\
        --saved saved.png --visible visible.png

`--control` を省くと、矩形の真上に同じ大きさの対照区画を取る（ボタンの無い、同じ画面）。
そこも2枚で一致することを確かめる。
"""
import argparse
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow が要ります（pip install pillow）")

# 矩形の中でこれだけ違えば、ボタンが「見えている画像にだけ」写っていると判断する。
# ボタンは 44dp（この端末で 116px）の円で、塗りが 40% の黒なので平均で 50 前後動く。
MIN_INSIDE_DIFF = 10.0

# 矩形の外（対照区画）は同じ画面なので、これ以下なら「同じ画面」と見なす。
MAX_CONTROL_DIFF = 2.0


def crop_pixels(image, rect):
    x1, y1, x2, y2 = rect
    if x2 > image.width or y2 > image.height:
        sys.exit(f"矩形 {rect} が画像 {image.width}x{image.height} の外")
    crop = image.crop(rect)
    return [crop.getpixel((x, y)) for y in range(crop.height) for x in range(crop.width)]


def mean_rgb(pixels):
    n = len(pixels)
    return tuple(round(sum(p[i] for p in pixels) / n, 1) for i in range(3))


def mean_diff(a, b):
    return round(sum(abs(pa[i] - pb[i]) for pa, pb in zip(a, b) for i in range(3)) / (len(a) * 3), 2)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rect", nargs=4, type=int, required=True, metavar=("X1", "Y1", "X2", "Y2"))
    parser.add_argument("--saved", required=True, help="アプリが保存した PNG")
    parser.add_argument("--visible", required=True, help="ボタンが見えている状態の screencap")
    parser.add_argument(
        "--control",
        nargs=4,
        type=int,
        metavar=("X1", "Y1", "X2", "Y2"),
        help="対照区画（既定: 矩形の真上に同じ大きさ）",
    )
    args = parser.parse_args()

    x1, y1, x2, y2 = args.rect
    control = args.control or [x1, y1 - (y2 - y1), x2, y1]
    saved = Image.open(args.saved).convert("RGB")
    visible = Image.open(args.visible).convert("RGB")

    inside_saved = crop_pixels(saved, args.rect)
    inside_visible = crop_pixels(visible, args.rect)
    control_saved = crop_pixels(saved, control)
    control_visible = crop_pixels(visible, control)

    inside = mean_diff(inside_saved, inside_visible)
    outside = mean_diff(control_saved, control_visible)

    print(f"矩形 {tuple(args.rect)}")
    print(f"  saved   平均RGB {mean_rgb(inside_saved)}")
    print(f"  visible 平均RGB {mean_rgb(inside_visible)}")
    print(f"  差（ボタンの矩形）     {inside}")
    print(f"対照 {tuple(control)}")
    print(f"  saved   平均RGB {mean_rgb(control_saved)}")
    print(f"  visible 平均RGB {mean_rgb(control_visible)}")
    print(f"  差（ボタンの無い区画） {outside}")

    if outside > MAX_CONTROL_DIFF:
        print("NG: 対照区画が一致しない。同じ画面の2枚ではない（時刻や通知の変化を疑う）")
        return 1
    if inside < 3.0:
        print("NG: ボタンの矩形も一致している。比較用の画像にボタンが写っていない")
        return 1
    if inside < MIN_INSIDE_DIFF:
        print("NG: 矩形の差が小さい。ボタンが保存画像にも写っている可能性がある")
        return 1

    print("OK: ボタンは保存した PNG に写っていない（見えている画像にだけ写っている）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
