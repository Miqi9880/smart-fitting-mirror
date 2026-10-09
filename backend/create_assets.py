"""Author the project's original transparent demonstration shirts (CC0)."""
from pathlib import Path
from PIL import Image, ImageDraw

ASSET_DIR = Path(__file__).parent / "assets"
COLORS = {"upper_blue": "#4A78BD", "upper_sand": "#B08259", "upper_preview": "#648565"}
OUTLINE = [(150, 120), (220, 100), (245, 155), (355, 155), (380, 100), (450, 120),
           (555, 260), (480, 320), (430, 255), (438, 720), (162, 720), (170, 255), (120, 320), (45, 260)]


def create_assets():
    ASSET_DIR.mkdir(exist_ok=True)
    for name, color in COLORS.items():
        image = Image.new("RGBA", (600, 800), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        draw.polygon(OUTLINE, fill=color)
        draw.line(OUTLINE + [OUTLINE[0]], fill="#243D5B", width=4)
        draw.line([(221, 107), (241, 167), (359, 167), (379, 107)], fill="#DEE7F0", width=6)
        draw.line([(168, 701), (432, 701)], fill="#DEE7F0", width=3)
        draw.rounded_rectangle((337, 265, 395, 313), radius=8, outline="#DEE7F0", width=3)
        image.save(ASSET_DIR / f"{name}.png")


if __name__ == "__main__":
    create_assets()
