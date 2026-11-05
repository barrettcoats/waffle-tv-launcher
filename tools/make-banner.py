"""Draws the Android TV banner (res/drawable-xhdpi/banner.png): the icon and "Waffle" on the sky.

    python3 tools/make-banner.py path/to/icon.png   # a large render of res/drawable/ic_launcher.xml

Needs Pillow. The icon render comes from any SVG tool (the vector drawable is plain paths).
"""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 640, 360  # 320 x 180 dp at xhdpi
TOP, MID, BOT = (0x3F, 0x7F, 0xBF), (0x8E, 0xC3, 0xEA), (0xF3, 0xDD, 0xB0)  # Palette.Sky*

img = Image.new("RGBA", (W, H))
d = ImageDraw.Draw(img)
for y in range(H):
    t = y / (H - 1)
    a, b, u = (TOP, MID, t / 0.6) if t < 0.6 else (MID, BOT, (t - 0.6) / 0.4)
    d.line([(0, y), (W, y)], fill=tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3)))

icon = Image.open(sys.argv[1]).convert("RGBA").resize((150, 150), Image.LANCZOS)
font = ImageFont.truetype("app/src/main/res/font/inter_bold.ttf", 112)
text = "Waffle"
box = d.textbbox((0, 0), text, font=font)
gap = 34
x0 = (W - (icon.width + gap + box[2] - box[0])) // 2
img.alpha_composite(icon, (x0, (H - icon.height) // 2))
tx, ty = x0 + icon.width + gap, (H - (box[3] - box[1])) // 2 - box[1]
shadow = Image.new("RGBA", (W, H))
ImageDraw.Draw(shadow).text((tx, ty + 4), text, font=font, fill=(0, 0, 0, 90))
img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(6)))
ImageDraw.Draw(img).text((tx, ty), text, font=font, fill="white")
img.convert("RGB").save("app/src/main/res/drawable-xhdpi/banner.png", optimize=True)
