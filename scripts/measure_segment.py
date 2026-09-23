"""
量分段控件指示器的实际像素轮廓。

用途：验收 SegmentRow 的"液态"形变到底有没有发生。
指标不是"看着像不像"，而是**指示器的像素宽度**：
  - 静止时 宽度 == 一格宽；
  - 移动中 宽度 > 一格宽（横向拉长），高度 < 36dp（纵向压扁）。
把切换过程的连拍帧喂进来，逐帧打印宽度，就能判定形变有没有真的发生、
以及它是不是"平滑衰减"（宽度从峰值单调回落到静止值）。

用法：python measure_segment.py <帧目录> [--y-band 1290 1400]
"""
import sys
import glob
import os
from PIL import Image

ACCENT = (0x00, 0x71, 0xE3)
TOL = 12


def bands_of(img, y0=None, y1=None):
    """找出所有命中强调色的像素，按 y 聚成条带，返回 [(y_top, y_bottom, x_min, x_max)]。"""
    px = img.convert("RGB").load()
    w, h = img.size
    ys = range(y0 or 0, min(y1 or h, h))
    rows = {}
    for y in ys:
        xs = [x for x in range(w) if all(abs(px[x, y][i] - ACCENT[i]) <= TOL for i in range(3))]
        if xs:
            rows[y] = (min(xs), max(xs))
    if not rows:
        return []
    out, cur = [], None
    for y in sorted(rows):
        if cur is None or y - cur[1] > 24:
            if cur:
                out.append(cur)
            cur = [y, y, rows[y][0], rows[y][1]]
        else:
            cur[1] = y
            cur[2] = min(cur[2], rows[y][0])
            cur[3] = max(cur[3], rows[y][1])
    if cur:
        out.append(cur)
    return [tuple(b) for b in out]


def main():
    argv = sys.argv[1:]
    files, yband = [], None
    i = 0
    while i < len(argv):
        if argv[i] == "--y-band":
            yband = (int(argv[i + 1]), int(argv[i + 2]))
            i += 3
        else:
            files.append(argv[i])
            i += 1

    paths = []
    for a in files:
        paths.extend(sorted(glob.glob(os.path.join(a, "*.png"))) if os.path.isdir(a) else [a])
    if not paths:
        print("没有输入帧")
        return

    for f in paths:
        img = Image.open(f)
        b = bands_of(img, *(yband or (None, None)))
        name = os.path.basename(f)
        if not b:
            print(f"{name}: 没找到指示器")
            continue
        parts = []
        for (yt, yb, xl, xr) in b:
            parts.append(f"y{yt}-{yb} h={yb - yt + 1} x{xl}-{xr} w={xr - xl + 1}")
        print(f"{name}: " + " | ".join(parts))


if __name__ == "__main__":
    main()
