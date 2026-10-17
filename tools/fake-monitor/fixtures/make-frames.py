"""Regenerates the fake monitor's video frames.

Real JPEGs, so a parent app can decode and show them. A bar moves across the
frame so a frozen picture is obvious at a glance.

    cd tools/fake-monitor/fixtures && python3 make-frames.py
"""
from PIL import Image, ImageDraw

WIDTH, HEIGHT, FRAMES = 640, 480, 10

for i in range(FRAMES):
    image = Image.new("RGB", (WIDTH, HEIGHT), (40, 44, 52))
    draw = ImageDraw.Draw(image)
    x = 20 + i * 60
    draw.rectangle([x, 180, x + 60, 300], fill=(230, 180, 60))
    draw.text((20, 20), f"fake-monitor frame {i}", fill=(240, 240, 240))
    image.save(f"frame-{i:02d}.jpg", quality=60)
