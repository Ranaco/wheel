"""Wheel's Play Store graphics, and the website's images, from the app's own look.

  icon-512.png             the app icon: the launcher icon's wheel, white on black
  feature-graphic.png      1024 x 500
  screenshot-N.png         captioned, from raw/ (emulator screenshots), at 2:1, Play's widest
  ../site/img/*.png        the site's icon, favicon and link preview

Run:  python play/make_assets.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"
SITE_IMG = HERE.parent / "site" / "img"

BLACK = (0, 0, 0)
WHITE = (242, 242, 243)
LABEL = (180, 180, 186)
DIM = (108, 108, 115)
EDGE = (36, 36, 40)
FONTS = Path("C:/Windows/Fonts")

# raw shot, caption, the line under it, and how much of the top and bottom to trim (status and nav bars)
SHOTS = [
    ("drive.png", "Turn to steer. Tilt to accelerate and brake.",
     "Your phone becomes an Xbox controller for PC racing games.", (0, 0)),
    ("menu.png", "A D-pad for the game's menus.",
     "Swipe or tap the middle, or lay the phone flat to bring it up.", (0, 0)),
    ("settings-1.png", "See exactly what the game gets.",
     "Set your neutral, then tune it to your hands.", (110, 75)),
    ("settings-2.png", "Make it yours.",
     "Steering lock, deadzones, pedals, rumble and clicks.", (110, 75)),
]


def font(size, bold=False):
    for name in (["seguisb.ttf", "segoeuib.ttf"] if bold else ["segoeui.ttf"]):
        if (FONTS / name).exists():
            return ImageFont.truetype(str(FONTS / name), size)
    return ImageFont.load_default(size)


def wheel(size, scale=4):
    """The launcher icon's wheel (ic_launcher_foreground.xml), white on transparent.
    Its 108-unit canvas has a 72-unit visible middle; that middle fills the image."""
    s = size * scale
    k = s / 72  # one icon unit
    image = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    def at(x):  # icon units -> pixels, with the 18-unit margin cropped off
        return (x - 18) * k

    cx = cy = at(54)
    draw.ellipse([cx - 26 * k, cy - 26 * k, cx + 26 * k, cy + 26 * k], fill="white")
    draw.ellipse([cx - 20.5 * k, cy - 20.5 * k, cx + 20.5 * k, cy + 20.5 * k], fill=(0, 0, 0, 0))
    draw.rectangle([at(34), at(51.5), at(74), at(56.5)], fill="white")
    draw.rectangle([at(51.5), at(56), at(56.5), at(75)], fill="white")
    draw.ellipse([cx - 7 * k, cy - 7 * k, cx + 7 * k, cy + 7 * k], fill="white")
    return image.resize((size, size), Image.LANCZOS)


def icon(size):
    image = Image.new("RGB", (size, size), BLACK)
    image.paste(wheel(size), (0, 0), wheel(size))
    return image


def framed(shot, height, radius=28):
    """A screenshot scaled to height, with rounded corners and a hairline edge, so it
    reads as a screen on the black page."""
    width = round(shot.width * height / shot.height)
    shot = shot.resize((width, height), Image.LANCZOS)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width - 1, height - 1], radius, fill=255)
    out = Image.new("RGB", (width + 4, height + 4), BLACK)
    ImageDraw.Draw(out).rounded_rectangle([0, 0, width + 3, height + 3], radius + 2, fill=EDGE)
    inner = Image.new("RGB", shot.size, BLACK)
    inner.paste(shot, (0, 0), mask)
    out.paste(inner, (2, 2), mask)
    return out


def screenshot(raw, caption, sub, trim):
    shot = Image.open(RAW / raw).convert("RGB")
    top, bottom = trim
    shot = shot.crop((0, top, shot.width, shot.height - bottom))
    canvas = Image.new("RGB", (2400, 1200), BLACK)
    draw = ImageDraw.Draw(canvas)
    draw.text((1200, 92), caption, font=font(64, bold=True), fill=WHITE, anchor="mm")
    draw.text((1200, 162), sub, font=font(36), fill=LABEL, anchor="mm")
    room_w, room_h = 2400 - 160, 1200 - 230
    height = min(room_h, round(shot.height * room_w / shot.width))
    frame = framed(shot, height)
    canvas.paste(frame, ((2400 - frame.width) // 2, 215 + (room_h - frame.height) // 2))
    return canvas


def feature():
    canvas = Image.new("RGB", (1024, 500), BLACK)
    draw = ImageDraw.Draw(canvas)
    mark = wheel(112)
    canvas.paste(mark, (64, 120), mark)
    draw.text((196, 176), "Wheel", font=font(84, bold=True), fill=WHITE, anchor="lm")
    draw.text((66, 290), "Your phone is the", font=font(34), fill=LABEL, anchor="lm")
    draw.text((66, 334), "racing wheel.", font=font(34), fill=LABEL, anchor="lm")
    draw.text((66, 400), "For PC games. Free.", font=font(24), fill=DIM, anchor="lm")
    shot = Image.open(RAW / "drive.png").convert("RGB")
    frame = framed(shot, 230, radius=16)
    canvas.paste(frame, (1024 - frame.width - 40, (500 - frame.height) // 2))
    return canvas


def preview(size=(1200, 630)):
    """The picture a shared link to the site shows."""
    canvas = Image.new("RGB", size, BLACK)
    draw = ImageDraw.Draw(canvas)
    mark = wheel(120)
    canvas.paste(mark, (80, 90), mark)
    draw.text((222, 150), "Wheel", font=font(88, bold=True), fill=WHITE, anchor="lm")
    draw.text((82, 262), "Your phone is the racing wheel for PC games.", font=font(36), fill=LABEL, anchor="lm")
    frame = framed(Image.open(RAW / "drive.png").convert("RGB"), 300, radius=18)
    canvas.paste(frame, ((size[0] - frame.width) // 2, 310))
    return canvas


def main():
    icon(512).save(HERE / "icon-512.png")
    feature().save(HERE / "feature-graphic.png")
    for n, (raw, caption, sub, trim) in enumerate(SHOTS, 1):
        screenshot(raw, caption, sub, trim).save(HERE / f"screenshot-{n}.png")
    SITE_IMG.mkdir(parents=True, exist_ok=True)
    icon(192).save(SITE_IMG / "icon-192.png")
    icon(64).save(SITE_IMG / "favicon.png")
    preview().save(SITE_IMG / "og.png")
    frame = framed(Image.open(RAW / "drive.png").convert("RGB"), 560, radius=26)
    frame.save(SITE_IMG / "drive.png")
    framed(Image.open(RAW / "menu.png").convert("RGB"), 560, radius=26).save(SITE_IMG / "menu.png")
    print("made", ", ".join(sorted(p.name for p in HERE.glob("*.png"))))


if __name__ == "__main__":
    main()
