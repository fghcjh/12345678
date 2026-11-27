#!/usr/bin/env python3
"""Generate the 忆聊 launcher icon: gradient tile + memory-linked chat bubble."""
from PIL import Image, ImageDraw, ImageFilter

S = 1024
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

# diagonal gradient background
grad = Image.new("RGB", (S, S))
d = ImageDraw.Draw(grad)
c1 = (108, 96, 246)   # violet
c2 = (56, 189, 248)   # cyan
for y in range(S):
    t = y / (S - 1)
    for_x = lambda x: min(1.0, (x / (S - 1) * 0.45 + t * 0.55))
    # draw one row is slow; do vertical strips blended horizontally via composite below
    pass
# faster: build small gradient then resize
small = Image.new("RGB", (64, 64))
px = small.load()
for y in range(64):
    for x in range(64):
        t = (x / 63.0) * 0.45 + (y / 63.0) * 0.55
        px[x, y] = (
            int(c1[0] + (c2[0] - c1[0]) * t),
            int(c1[1] + (c2[1] - c1[1]) * t),
            int(c1[2] + (c2[2] - c1[2]) * t),
        )
grad = small.resize((S, S), Image.BICUBIC)

mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.235), fill=255)
img.paste(grad, (0, 0), mask)

# soft inner glow top-left
glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse([-S * 0.25, -S * 0.35, S * 0.75, S * 0.45], fill=(255, 255, 255, 46))
glow = glow.filter(ImageFilter.GaussianBlur(90))
img = Image.alpha_composite(img, Image.composite(glow, Image.new("RGBA", (S, S), (0, 0, 0, 0)), mask))

d = ImageDraw.Draw(img)

# chat bubble (rounded rect + tail)
bw, bh = 470, 330
bx, by = (S - bw) / 2, S * 0.30
white = (255, 255, 255, 255)
d.rounded_rectangle([bx, by, bx + bw, by + bh], radius=105, fill=white)
d.polygon([(bx + 118, by + bh - 6), (bx + 232, by + bh - 6), (bx + 132, by + bh + 92)], fill=white)

# three dots inside bubble
dot_r = 30
cy = by + bh / 2
for i, cx in enumerate([bx + bw * 0.32, bx + bw * 0.5, bx + bw * 0.68]):
    col = (108, 96, 246, 255) if i != 1 else (56, 189, 248, 255)
    d.ellipse([cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r], fill=col)

# memory orbit: nodes + arcs, bottom-right
oc = (S * 0.79, S * 0.80)
for r, a in [(150, 70), (196, 40)]:
    d.arc([oc[0] - r, oc[1] - r, oc[0] + r, oc[1] + r], start=200, end=340, fill=(255, 255, 255, a), width=16)

nodes = [(oc[0] + 150 * 0.94, oc[1] - 150 * 0.34, 26), (oc[0] - 150 * 0.80, oc[1] - 150 * 0.60, 20)]
for nx, ny, nr in nodes:
    d.ellipse([nx - nr, ny - nr, nx + nr, ny + nr], fill=(255, 255, 255, 235))

img.save("/root/12345678/memochat/icon-1024.png")

for folder, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                     ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    img.resize((size, size), Image.LANCZOS).save(f"/root/12345678/memochat/res/{folder}/ic_launcher.png")
print("icons written")
