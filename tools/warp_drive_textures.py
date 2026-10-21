#!/usr/bin/env python3
"""Warp drive textures (16 x 16): the coil (a steel frame round copper windings, their inner ring glowing cyan once the
drive is formed) and the core's face (a ring of field emitters round a bright centre; dim until formed)."""
import os, random
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'create_relativity', 'textures', 'block')
FRAME, FRAME_HI, FRAME_LO = (70, 74, 82), (110, 115, 124), (44, 46, 52)
COPPER, COPPER_LO = (224, 149, 106), (154, 90, 62)


def noisy(c, a=4, rnd=random.Random(5)):
    return tuple(max(0, min(255, int(v + rnd.uniform(-a, a)))) for v in c) + (255,)


def coil(glow):
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            edge = min(x, y, 15 - x, 15 - y)
            if edge == 0:
                c = FRAME_LO
            elif edge == 1:
                c = FRAME_HI if (x + y) % 2 else FRAME
            elif edge in (2, 3, 4):
                # Windings, round and round.
                c = COPPER if (x + y + edge) % 2 == 0 else COPPER_LO
            elif edge == 5:
                c = glow
            else:
                c = (40, 42, 48) if glow == (40, 60, 72) else tuple(min(255, int(v * 0.55)) for v in glow)
            px[x, y] = noisy(c, 3)
    for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        px[x, y] = noisy((170, 174, 180), 2)
    return img


def core(lit):
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            r = (dx * dx + dy * dy) ** 0.5
            edge = min(x, y, 15 - x, 15 - y)
            if edge == 0:
                c = FRAME_LO
            elif r < 2.6:
                c = (235, 255, 255) if lit else (90, 120, 130)
            elif r < 4.0:
                c = (90, 220, 245) if lit else (50, 80, 92)
            elif r < 5.2:
                c = FRAME_HI
            elif r < 6.6 and int((dx + 8) + (dy + 8)) % 3 == 0:
                c = (60, 170, 210) if lit else (40, 60, 72)
            else:
                c = FRAME
            px[x, y] = noisy(c, 3)
    return img


coil((40, 60, 72)).save(os.path.join(OUT, 'warp_coil.png'))
coil((80, 220, 240)).save(os.path.join(OUT, 'warp_coil_formed.png'))
core(False).save(os.path.join(OUT, 'warp_core.png'))
core(True).save(os.path.join(OUT, 'warp_core_formed.png'))
