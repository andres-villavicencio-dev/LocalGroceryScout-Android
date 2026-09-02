"""Vision-based price scraper: screenshot a page, let a VLM read the prices.

For sites that resist structured extraction (Akamai bot walls, client-only
rendering, hostile DOMs), the last-mile approach is: open the page in a
normal browser, screenshot it, and have a local vision model (gemma3 via
ollama) extract product tiles as JSON.

Pipeline:
  1. Playwright chromium (full fingerprint, anti-detection flags) navigates
     to a search/category URL.
  2. Screenshot the viewport; scroll + stitch if the page is long.
  3. gemma3:latest via ollama /api/generate reads the image and returns
     {"tiles": [{"name", "price", "unit"}]}.
  4. Sanity filter: prices must parse and land in a plausible band; names
     deduped. Optional second pass on a zoomed crop if the first extraction
     is empty/ambiguous.
  5. Persist to the shared prices/price_history tables with vision
     provenance ("vision-scraped from <brand>").

Latency: ~31s per screenshot extraction + ~2s navigation. This is for
targeted queries and tracked staples — NOT catalog sweeps.

Verified against gemma3 (Sep 2026): clean text extraction of
"Anchor Blue Milk 2L  $5.40" from a rendered image.
"""
from __future__ import annotations

import base64
import json
import re
import time
import urllib.request
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).parent))
from price_db import PriceDB, Price
from store_discovery import slugify

OLLAMA_URL = "http://192.168.1.72:11434/api/generate"
VISION_MODEL = "gemma3:latest"

# Price sanity band: NZ grocery items. Rejects OCR misreads like $199.00
# for a $1.99 milk or $0.05 phantom tiles.
MIN_PRICE = 0.30
MAX_PRICE = 120.0

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/151.0.0.0 Safari/537.36")

EXTRACTION_PROMPT = """You are looking at a screenshot of a supermarket online shop.
Extract every product tile you can see as JSON. Respond with STRICT JSON only:
{"tiles": [{"name": "<product name>", "price": <number, dollars>, "unit": "<size like 2L or 500g or null>"}]}
Rules:
- price is a NUMBER (no $ sign): 5.40 means $5.40
- include promo/special prices if shown, use the current selling price
- skip banners, menus, ads, and anything that is not a product tile
- unit is the pack size if visible, else null
- COPY NAMES CHARACTER BY CHARACTER from the image — do not invent or approximate"""


def _browser_context(playwright):
    # Sharp-text capture settings, validated against gemma3 (Sep 2026):
    # a narrower viewport + deviceScaleFactor=2 makes product-tile text big
    # and crisp, which is the difference between exact names and VLM
    # hallucination. 900x1100 @2x = 1800x2200 screenshot — names read
    # character-perfect, extraction ~22s.
    browser = playwright.chromium.launch(
        headless=True,
        args=["--no-sandbox", "--disable-blink-features=AutomationControlled"],
    )
    ctx = browser.new_context(
        viewport={"width": 900, "height": 1100},
        device_scale_factor=2,
        user_agent=UA,
        locale="en-NZ",
    )
    ctx.add_init_script(
        "Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")
    return browser, ctx


def screenshot_pages(url: str, out_prefix: str, scrolls: int = 2,
                     settle_s: float = 4.0) -> list[str]:
    """Open url in headless Chromium and capture N single-viewport screenshots.

    One screenshot per scroll position — NOT stitched. Validation against
    gemma3 showed stitched screenshots cause tile hallucination (seams +
    downscale confuse the VLM), while per-viewport shots read
    character-perfect. Each shot is extracted separately and rows merged.
    """
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        browser, ctx = _browser_context(p)
        page = ctx.new_page()
        try:
            page.goto(url, timeout=45000, wait_until="domcontentloaded")
            page.wait_for_timeout(int(settle_s * 1000))
            # dismiss common cookie walls that would cover the products
            for label in ["Accept all", "Accept All", "OK", "Got it"]:
                try:
                    page.get_by_role("button", name=label, exact=False).first.click(timeout=1500)
                    page.wait_for_timeout(800)
                except Exception:  # noqa: BLE001
                    pass
            shots = []
            for i in range(scrolls + 1):
                path = f"{out_prefix}_{i}.png"
                page.screenshot(path=path)
                shots.append(path)
                if i < scrolls:
                    page.mouse.wheel(0, 1000)
                    page.wait_for_timeout(1200)
            return shots
        finally:
            browser.close()


def extract_tiles(image_path: str, retries: int = 1) -> list[dict]:
    """VLM extraction: gemma3 reads product tiles from a screenshot."""
    b64 = base64.b64encode(Path(image_path).read_bytes()).decode()
    last_err = None
    for attempt in range(retries + 1):
        payload = {
            "model": VISION_MODEL,
            "prompt": EXTRACTION_PROMPT,
            "images": [b64],
            "stream": False,
            "format": "json",
        }
        req = urllib.request.Request(
            OLLAMA_URL,
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
            method="POST")
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                body = json.loads(r.read().decode())
            raw = body.get("response", "").strip()
            # tolerate fences/prose around the JSON
            start = raw.find("{")
            end = raw.rfind("}")
            if start >= 0 and end > start:
                raw = raw[start:end + 1]
            data = json.loads(raw)
            tiles = data.get("tiles", [])
            # sanity filter
            good = []
            for t in tiles:
                try:
                    price = float(t.get("price"))
                except (TypeError, ValueError):
                    continue
                if not (MIN_PRICE <= price <= MAX_PRICE):
                    continue
                name = (t.get("name") or "").strip()
                if not name or len(name) < 2:
                    continue
                good.append({
                    "name": name,
                    "price": price,
                    "unit": t.get("unit") or None,
                })
            if good:
                return good
            last_err = f"no sane tiles (raw had {len(tiles)})"
        except Exception as ex:  # noqa: BLE001
            last_err = ex
        # retry with a second pass
    print(f"    [vision] extraction failed: {last_err}")
    return []


def scrape_site_search(brand: str, url: str, query: str, db: PriceDB,
                       store_id: int, scrolls: int = 2) -> list[dict]:
    """Full vision pipeline for one search page. Returns app-shaped rows."""
    prefix = f"/tmp/vision_{slugify(brand)}_{int(time.time())}"
    try:
        shots = screenshot_pages(url, prefix, scrolls=scrolls)
        tiles = []
        for shot in shots:
            tiles.extend(extract_tiles(shot))
            Path(shot).unlink(missing_ok=True)
        # dedupe identical names across overlapping scroll shots
        seen_names = set()
        deduped = []
        for t in tiles:
            if slugify(t["name"]) not in seen_names:
                seen_names.add(slugify(t["name"]))
                deduped.append(t)
        tiles = deduped
    finally:
        import glob as _glob
        for leftover in _glob.glob(f"{prefix}_*.png"):
            Path(leftover).unlink(missing_ok=True)

    rows = []
    seen = set()
    for t in tiles:
        key = slugify(t["name"])
        if key in seen:
            continue
        seen.add(key)
        # NOTE: vision rows are NOT persisted anymore. gemma3 occasionally
        # hallucinates names even at 2x resolution ("Hilton Creamy Milk" for
        # a Meadow Fresh tile), and a wrong name poisons the cache/history.
        # The Warehouse has a trusted JSON-LD catalog sweep (tw_catalog.py);
        # other vision-enabled sites would need a name-plausibility gate here
        # before ever writing to the DB.
        rows.append({
            "store": brand,
            "storeChain": brand,
            "price": t["price"],
            "currency": "NZD",
            "unit": t["unit"],
            "address": "online",
            "distanceKm": None,
            "confidence": 0.7,          # VLM-read prices: real but OCR-graded
            "reasoning": f"vision-scraped from {brand}'s online shop (gemma3 screenshot extraction)",
            "productName": t["name"],
            "url": url,
        })
    return rows


# ---------------------------------------------------------------- site registry

VISION_SITES: dict[str, dict] = {
    # The big missing chain. Akamai kills headless *DOM* access with
    # ERR_HTTP2_PROTOCOL_ERROR before the page even renders — a screenshot
    # pipeline can't fix a page that never loads, so this stays disabled until
    # the transport itself works (residential proxy / headed browser).
    "Woolworths": {
        "enabled": False,
        "search_url": "https://www.woolworths.co.nz/shop/search?q={q}",
        "reason": "Akamai blocks page load entirely; revisit with proxy",
    },
    # The Warehouse: client-rendered search, Playwright loads it fine (verified).
    # Prices visible on tiles; DOM extraction is fiddly — vision is simpler.
    "The Warehouse": {
        "enabled": True,
        "search_url": "https://www.thewarehouse.co.nz/search?q={q}",
    },
}


def vision_search(query: str, db: PriceDB, brands: list[str] | None = None) -> list[dict]:
    """Run vision scraping for the query across enabled vision sites."""
    import urllib.parse
    out = []
    for brand, cfg in VISION_SITES.items():
        if not cfg.get("enabled"):
            continue
        if brands and brand not in brands:
            continue
        with db.tx() as cur:
            cur.execute(
                "SELECT id FROM stores WHERE brand=? LIMIT 1", (brand,))
            row = cur.fetchone()
        if row is None:
            from price_db import Store
            store = Store(osm_id=f"vision-{slugify(brand)}",
                          name=f"{brand} (online)", brand=brand,
                          lat=-36.8485, lng=174.7633, distance_km=None)
            store_id = db.upsert_store(store)
        else:
            store_id = row["id"]
        url = cfg["search_url"].format(q=urllib.parse.quote(query))
        print(f"  [vision:{brand}] screenshot → gemma3 …")
        try:
            rows = scrape_site_search(brand, url, query, db, store_id)
            out.extend(rows)
            print(f"  [vision:{brand}] {len(rows)} tiles extracted")
        except Exception as ex:  # noqa: BLE001
            print(f"  [vision:{brand}] failed: {ex}")
    return out


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--query", required=True)
    ap.add_argument("--brands", nargs="*")
    args = ap.parse_args()
    db = PriceDB()
    rows = vision_search(args.query, db, args.brands)
    for r in rows:
        print(f"  ${r['price']:.2f}  {r['productName'][:60]}")
    print(f"\n{len(rows)} vision-scraped rows")