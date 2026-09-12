"""Scrape product images from the phone apps' shelf screens.

Why: app-scraped chains (Woolworths/PAK'nSAVE via adb) have no SKU in their
tiles, so fs_image_url() can't build the Foodstuffs CDN URL for them — those
rows render as bare letter tiles in the app. When a walk has no image for a
tile, we literally scrape the picture: screenshot each PLP screen, crop each
product-tile image region (from the accessibility tree's ImageView bounds),
and store it under product_images/<product_slug>.jpg. The API serves them
from /img/<slug>.jpg.

Geometry notes (recon'd 2026-09-12 from uiautomator dumps):
- PAK'nSAVE PLP tiles: square ImageView ~380x380 next to the tile desc;
  the product NAME TextView sits at the top of the same tile container.
- Woolworths PLP tiles: the name TextView is followed by an image region
  in the same column — crops are taken relative to the name's bounds.
- Screenshots are PNG (screencap); crops saved as JPEG (quality 88) to
  keep ~400px product shots around 15-40KB.

Standalone check:
  venv python shelf_images.py --check <dump.xml>
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from html import unescape
from pathlib import Path
from typing import Optional

from PIL import Image, ImageOps

sys.path.insert(0, str(Path(__file__).parent))
from price_db import PriceDB  # noqa: E402
from store_discovery import slugify  # noqa: E402

from app_scraper import ADB, ADB_SERIAL, ui_dump, screen_texts, adb as adb_call

IMG_DIR = Path(__file__).parent / "product_images"
IMG_DIR.mkdir(exist_ok=True)


def _attrs(tag: str) -> dict:
    return dict(re.findall(r'([\w-]+)="([^"]*)"', tag))


# ── screen capture ───────────────────────────────────────────────────────────

def screenshot() -> Image.Image:
    """Current phone screen as a PIL image (PNG screencap piped to PIL)."""
    raw = subprocess.run(
        [ADB, "-s", ADB_SERIAL, "shell", "screencap", "-p"],
        capture_output=True, timeout=30).stdout
    # adb shell on some devices mangles \n -> \r\n; fix only if needed.
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raw = raw.replace(b"\r\n", b"\n")
    import io
    return Image.open(io.BytesIO(raw)).convert("RGB")


# ── tile image geometry ─────────────────────────────────────────────────────

def tile_image_bounds(xml: str, tile_name: str) -> Optional[tuple[int, int, int, int]]:
    """Find the product-image bounds for a tile named `tile_name`.

    Strategy: locate the tile's NAME TextView, then find the nearest
    square-ish large ImageView (>=200px side) at or below the name's top
    and horizontally overlapping the name's column. Tiles render as
    [image | text] or [text over image] containers — the big square in
    the tile's horizontal span is the product photo.
    """
    # 1. name node
    name_b = None
    for tag in re.findall(r"<node [^>]*>", xml):
        a = _attrs(tag)
        if unescape(a.get("text", "")).strip() == tile_name:
            name_b = tuple(int(v) for v in re.findall(r"\d+", a["bounds"]))
            break
    if not name_b:
        return None
    nx1, ny1, nx2, ny2 = name_b

    # 2. candidate square ImageViews near the name's column
    best = None
    for tag in re.findall(r"<node [^>]*>", xml):
        a = _attrs(tag)
        if "ImageView" not in a.get("class", ""):
            continue
        b = tuple(int(v) for v in re.findall(r"\d+", a["bounds"]))
        x1, y1, x2, y2 = b
        w, h = x2 - x1, y2 - y1
        if w < 200 or h < 200 or abs(w - h) > max(40, w // 4):
            continue  # product photos are big and square-ish
        # horizontally overlapping the name (same tile column) and
        # vertically inside the tile (image above or below the name, or
        # the name overlays the image)
        if x2 <= nx1 + 40 or x1 >= nx2 - 40:
            continue
        if y2 <= ny1 or y1 >= ny2 + 260:
            continue
        # prefer the closest candidate
        dist = abs((y1 + y2) // 2 - (ny1 + ny2) // 2)
        if best is None or dist < best[0]:
            best = (dist, b)
    return best[1] if best else None


def crop_tile_image(xml: str, shot: Image.Image, tile_name: str) -> Optional[bytes]:
    """Crop the tile's product photo from a screenshot; JPEG bytes or None."""
    b = tile_image_bounds(xml, tile_name)
    if not b:
        return None
    x1, y1, x2, y2 = b
    W, H = shot.size
    # clamp to screen
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(W, x2), min(H, y2)
    if x2 - x1 < 100 or y2 - y1 < 100:
        return None
    crop = shot.crop((x1, y1, x2, y2))
    crop = ImageOps.exif_transpose(crop)
    import io
    buf = io.BytesIO()
    crop.save(buf, "JPEG", quality=88)
    return buf.getvalue()


# ── per-shelf wiring ────────────────────────────────────────────────────────

def capture_for_screen(xml: str, tiles: list, app: str) -> int:
    """For every parsed tile on this screen that lacks an image, crop its
    photo from the current screenshot. Call immediately after ui_dump+parse,
    BEFORE scrolling. Returns count of images stored."""
    db = PriceDB()
    stored = 0
    try:
        shot = screenshot()
    except Exception as ex:  # noqa: BLE001 — images are best-effort
        print(f"  [img] screenshot failed: {type(ex).__name__}: {str(ex)[:60]}")
        return 0
    for t in tiles:
        slug = slugify(t.name)
        if not slug or (IMG_DIR / f"{slug}.jpg").exists():
            continue  # already have this product's photo
        try:
            data = crop_tile_image(xml, shot, t.name)
            if data:
                (IMG_DIR / f"{slug}.jpg").write_bytes(data)
                # record on the tile so ingest carries the URL
                t.img_slug = slug  # type: ignore[attr-defined]
                stored += 1
        except Exception as ex:  # noqa: BLE001 — one bad crop ≠ dead run
            print(f"  [img] crop failed for {t.name[:30]!r}: "
                  f"{type(ex).__name__}: {str(ex)[:50]}")
            continue
    if stored:
        print(f"  [img] stored {stored} tile images from this screen")
    return stored


# ── standalone dump check ──────────────────────────────────────────────────

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", metavar="DUMP.xml", help="find image bounds in a dump")
    args = ap.parse_args()
    if args.check:
        xml = Path(args.check).read_text(encoding="utf-8", errors="replace")
        texts = [unescape(t).strip() for t in re.findall(r'text="([^"]*)"', xml) if t.strip()]
        names = [t for t in texts if len(t) > 10 and not t.startswith("$")][:6]
        print(f"candidate tile names: {names}")
        for n in names:
            print(f"  {n!r}: {tile_image_bounds(xml, n)}")


if __name__ == "__main__":
    main()