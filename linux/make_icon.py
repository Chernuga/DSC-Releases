"""Render the DeepSeek Code icon for Windows.

The same design as the Android launcher: a white ">_" prompt on a dark panel.
Drawn as shapes rather than text so it renders identically without depending on
a font being installed.

Windows wants a multi-resolution .ico, so the glyph is drawn at several sizes
and packed into one file.
"""
import os

from PIL import Image, ImageDraw

DARK = (18, 22, 28, 255)      # matches the Android launcher background
WHITE = (255, 255, 255, 255)


def draw(size, pad_ratio=0.0):
    """One square icon at `size` px."""
    # Draw large, then downsample: gives clean anti-aliased edges at every size
    # without hand-tuning coordinates for each one.
    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    pad = int(s * pad_ratio)
    d.rounded_rectangle(
        [pad, pad, s - pad - 1, s - pad - 1],
        radius=int(s * 0.18),
        fill=DARK,
    )

    # The ">" chevron: two strokes meeting at the right, centred on the panel.
    stroke = max(scale, int(s * 0.085))
    r = stroke // 2

    # Chevron occupies the left of the glyph.
    cx1, cx2 = s * 0.34, s * 0.50
    cy1, cy2, cy3 = s * 0.34, s * 0.50, s * 0.66

    def line(x1, y1, x2, y2):
        d.line([x1, y1, x2, y2], fill=WHITE, width=stroke)
        for (x, y) in ((x1, y1), (x2, y2)):
            d.ellipse([x - r, y - r, x + r, y + r], fill=WHITE)

    line(cx1, cy1, cx2, cy2)
    line(cx2, cy2, cx1, cy3)

    # The "_" cursor, sitting on the baseline to the right.
    line(s * 0.57, cy3, s * 0.72, cy3)

    return img.resize((size, size), Image.LANCZOS)


def main():
    # assets/ rather than build/: the repository's .gitignore excludes build/
    # directories as generated output, and this folder holds source art that must
    # be committed.
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
    os.makedirs(out, exist_ok=True)

    sizes = [16, 24, 32, 48, 64, 128, 256]
    frames = [draw(n) for n in sizes]

    ico = os.path.join(out, "icon.ico")
    frames[-1].save(ico, format="ICO", sizes=[(n, n) for n in sizes])

    # The PNG is what Linux packaging uses: electron-builder takes a PNG for the
    # Linux icon, and an .ico is Windows-only. 512 px so the largest launcher size
    # is covered without upscaling.
    png = os.path.join(out, "icon.png")
    draw(512).save(png)

    print("wrote", ico, os.path.getsize(ico), "bytes")
    print("wrote", png, os.path.getsize(png), "bytes")


if __name__ == "__main__":
    main()
