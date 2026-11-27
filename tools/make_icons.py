#!/usr/bin/env python3
"""Generate Hub Cargolift launcher, adaptive and splash artwork from the official Cargo Lift logo.

Source art: tools/assets/logo.png  (the same logo used by the web project)
Outputs:
  res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png   legacy icons
  res/drawable-xxxhdpi/ic_launcher_foreground.png               adaptive foreground (108dp @4x)
  res/drawable-xxhdpi/splash_logo.png                           splash / loading artwork
"""
import math
import os

from PIL import Image, ImageFilter

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(BASE, "res")
LOGO = os.path.join(BASE, "tools", "assets", "logo.png")

CANVAS = 432            # adaptive icon layer = 108 dp at 4x
MASK_R = CANVAS / 3.0   # launcher mask radius = 36 dp (visible circle is 72 dp wide)


def load_logo():
    return Image.open(LOGO).convert("RGBA")


def art_radius(im):
    """Largest distance from the centre, in logo pixels, of the white artwork."""
    w, h = im.size
    px = im.load()
    cx, cy = w / 2.0, h / 2.0
    best = 0.0
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a > 200 and r > 190 and g > 190 and b > 190:
                d = math.hypot(x - cx, y - cy)
                if d > best:
                    best = d
    return best


def adaptive_foreground():
    """Logo scaled so the artwork stays inside the mask while the blue body covers it."""
    logo = load_logo()
    r = art_radius(logo)
    # 1) the artwork (arrows included) must fit inside the visible circle
    size_art = int(MASK_R * 0.93 * logo.size[0] / r)
    # 2) the blue rounded square must cover the whole visible circle
    size_cover = int(2 * MASK_R + 10)
    size = max(size_cover, min(size_art, int(CANVAS * 0.78)))
    size = min(size, CANVAS)
    print("   art radius %.1f px -> foreground %d px (cover %d, art %d)"
          % (r, size, size_cover, size_art))
    art = logo.resize((size, size), Image.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    off = (CANVAS - size) // 2
    canvas.alpha_composite(art, (off, off))
    return canvas


def splash_logo(size=768):
    """Logo, upscaled and sharpened so it stays crisp on the splash / loading screen.

    No halo: a glow around a rounded-square logo always ends up clipped at the
    image border, which shows up as a faint square edge on the gradient.
    """
    src = load_logo().resize((size, size), Image.LANCZOS)
    return src.filter(ImageFilter.UnsharpMask(radius=size / 256.0 * 2.0,
                                              percent=120,
                                              threshold=1))


def put(path, image):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, optimize=True)
    print("   wrote", os.path.relpath(path, BASE))


print("[icons] reading", os.path.relpath(LOGO, BASE))
logo = load_logo()

for folder, px in [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]:
    put(os.path.join(RES, folder, "ic_launcher.png"),
        logo.resize((px, px), Image.LANCZOS))

put(os.path.join(RES, "drawable-xxxhdpi", "ic_launcher_foreground.png"),
    adaptive_foreground())
put(os.path.join(RES, "drawable-xxhdpi", "splash_logo.png"),
    splash_logo())
print("[icons] done")
