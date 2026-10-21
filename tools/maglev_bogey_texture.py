#!/usr/bin/env python3
"""Maglev bogey's texture (32 x 32), regions as MaglevBogeyRender maps them: a magnet's north face (red frame), south face
(blue), side (copper windings, the red half forward), top and bottom; the chassis plate; its column. After Simulated's
redstone magnet."""
import os, random
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'create_relativity', 'textures', 'block', 'maglev_bogey.png')
random.seed(11)
img = Image.new('RGBA', (32, 32))
px = img.load()


def noisy(c, a=4):
    return tuple(max(0, min(255, int(v + random.uniform(-a, a)))) for v in c) + (255,)


FRAME = (60, 63, 70)
IRON = (111, 115, 120)
DARK = (44, 46, 52)


def pole_face(x0, colour):
    for y in range(16):
        for x in range(16):
            edge = min(x, y, 15 - x, 15 - y)
            if edge == 0:
                c = FRAME
            elif edge <= 2:
                shade = 1.15 if edge == 1 else 0.95
                c = tuple(min(255, int(v * shade)) for v in colour)
            elif edge == 3:
                c = FRAME
            elif 6 <= x <= 9 and 6 <= y <= 9:
                c = IRON
            else:
                c = DARK
            px[x0 + x, y] = noisy(c)


pole_face(0, (176, 58, 58))
pole_face(16, (58, 93, 176))
# Side: the windings, the red half forward (u 0-7).
for y in range(16, 24):
    for x in range(16):
        if y in (16, 23):
            c = FRAME
        else:
            copper = (224, 149, 106) if x % 2 == 0 else (154, 90, 62)
            tint = (176, 58, 58) if x < 8 else (58, 93, 176)
            c = tuple(int(a * 0.7 + b * 0.3) for a, b in zip(copper, tint))
            if x in (0, 15):
                c = FRAME
        px[x, y] = noisy(c, 3)
# Top and bottom.
for y in range(16, 24):
    for x in range(16, 32):
        c = FRAME if (x in (16, 31) or y in (16, 23)) else (78, 82, 90)
        px[x, y] = noisy(c, 3)
# Chassis plate: steel, rivets at the corners.
for y in range(24, 32):
    for x in range(16):
        c = (128, 133, 141)
        if y in (24, 31) or x in (0, 15):
            c = (96, 100, 108)
        if (x, y) in ((2, 26), (13, 26), (2, 29), (13, 29)):
            c = (168, 172, 178)
        px[x, y] = noisy(c, 4)
# Column: darker steel, a seam down the middle.
for y in range(24, 32):
    for x in range(16, 32):
        c = (88, 92, 100) if x not in (23, 24) else (70, 73, 80)
        px[x, y] = noisy(c, 3)
img.save(OUT)
