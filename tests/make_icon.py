# -*- coding: utf-8 -*-
"""生成启动器图标（纯标准库，4x 超采样抗锯齿）：深色圆角方块 + 琥珀色时钟指针"""
import math
import os
import struct
import zlib

BG = (28, 39, 51, 255)        # #1C2733
AMBER = (242, 178, 62, 255)   # #F2B23E
WHITE = (237, 241, 245, 255)  # #EDF1F5


def write_png(path, size, pixels):
    raw = b""
    for row in pixels:
        raw += b"\x00" + b"".join(struct.pack("4B", *p) for p in row)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def in_rounded(px, py, x0, y0, x1, y1, r):
    if px < x0 or px > x1 or py < y0 or py > y1:
        return False
    cx = min(max(px, x0 + r), x1 - r)
    cy = min(max(py, y0 + r), y1 - r)
    return (px - cx) ** 2 + (py - cy) ** 2 <= r * r


def seg_dist(px, py, ax, ay, bx, by):
    abx, aby = bx - ax, by - ay
    apx, apy = px - ax, py - ay
    denom = abx * abx + aby * aby
    t = 0.0 if denom == 0 else max(0.0, min(1.0, (apx * abx + apy * aby) / denom))
    dx, dy = apx - t * abx, apy - t * aby
    return math.hypot(dx, dy)


def render(size):
    S = 4
    W = size * S
    half = W / 2.0
    inset = W * 0.04
    radius = W * 0.22
    ring_r = W * 0.345
    ring_w = W * 0.055
    hour_len = ring_r * 0.52
    min_len = ring_r * 0.80

    # 指针角度：10:08（经典表盘摆位）；θ 从 12 点顺时针
    hour_ang = math.radians((10 + 8 / 60.0) * 30)
    min_ang = math.radians(8 * 6)
    h_ax, h_ay = half, half
    h_bx, h_by = half + hour_len * math.sin(hour_ang), half - hour_len * math.cos(hour_ang)
    m_ax, m_ay = half, half
    m_bx, m_by = half + min_len * math.sin(min_ang), half - min_len * math.cos(min_ang)

    acc_r = acc_g = acc_b = acc_a = 0.0
    out = []
    for oy in range(size):
        row = []
        for ox in range(size):
            acc_r = acc_g = acc_b = acc_a = 0.0
            for sy in range(S):
                for sx in range(S):
                    x = ox * S + sy * 0 + sx + 0.5
                    y = oy * S + sy + 0.5
                    c = (0, 0, 0, 0)
                    if in_rounded(x, y, inset, inset, W - inset, W - inset, radius):
                        c = BG
                        d = math.hypot(x - half, y - half)
                        if abs(d - ring_r) <= ring_w / 2:
                            c = AMBER
                        elif seg_dist(x, y, h_ax, h_ay, h_bx, h_by) <= ring_w / 2:
                            c = AMBER
                        elif seg_dist(x, y, m_ax, m_ay, m_bx, m_by) <= ring_w / 2:
                            c = AMBER
                        elif math.hypot(x - half, y - half) <= ring_w * 1.35:
                            c = WHITE
                    acc_r += c[0]; acc_g += c[1]; acc_b += c[2]; acc_a += c[3]
            n = S * S
            row.append((int(round(acc_r / n)), int(round(acc_g / n)),
                        int(round(acc_b / n)), int(round(acc_a / n))))
        out.append(row)
    return out


def main():
    base = r"F:\日历\android\app\src\main\res"
    for dpi, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                      ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = os.path.join(base, "mipmap-" + dpi)
        if not os.path.isdir(d):
            os.makedirs(d)
        p = os.path.join(d, "ic_launcher.png")
        write_png(p, size, render(size))
        print("生成", p, size, "x", size)


if __name__ == "__main__":
    main()
