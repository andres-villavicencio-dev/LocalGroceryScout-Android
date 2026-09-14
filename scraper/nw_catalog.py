#!/usr/bin/env python3
"""New World catalog scraper: walks NW's category tree via Playwright.

NW is a Foodstuffs Next.js site like Pak'nSave: category pages embed the full
Algolia initialResults payload in __NEXT_DATA__. Unlike PnS, Cloudflare
serves bare HTTP a JS challenge on NW, so we go through headless Chromium
(the same launch recipe price_agent.py uses — real Chrome UA, en-NZ locale,
webdriver property removed).

Each product hit carries: productId ("5092718-EA-000"), brand, name,
displayName (pack size), singlePrice.price in CENTS, comparativePrice.

Every scrape appends to price_history and updates the current-price snapshot
in `prices`. Store attribution: the IP-selected New World branch (site picks
the nearest one from our IP — Auckland for this host).

Why this matters: NW's universe was 152 products because the only ingest
path was price_agent.py's LLM-picked top-3 per query. A category walk
captures every product, so cross-chain compare finally gets a full universe.

Usage:
  python3 nw_catalog.py                    # all categories, 2 pages each
  python3 nw_catalog.py --pages 4          # deeper walk
  python3 nw_catalog.py --cats pantry drinks
"""
import argparse
import json
import random
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from playwright.sync_api import sync_playwright

from price_db import PriceDB, Price, Store
from store_discovery import haversine_km, slugify

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/151.0.0.0 Safari/537.36")

BASE = "https://www.newworld.co.nz"

# The shop menu's real aisles (mirrors PnS's CATEGORIES; NW uses the same
# Foodstuffs taxonomy).
CATEGORIES = [
    "pantry", "chilled-frozen-and-desserts", "fruit-and-vegetables",
    "meat-poultry-and-seafood", "fridge-deli-and-eggs", "bakery",
    "drinks", "hot-and-cold-drinks", "snacks-treats-and-easy-meals",
    "household-and-cleaning", "health-and-body", "baby-and-toddler",
    "pets", "frozen", "beer-wine-and-cider",
]

# New World Victoria Park (nearest NW to the default origin; the site's
# auto-selected branch for an Auckland IP).
NW_LAT, NW_LNG = -36.8515, 174.7457
ORIGIN = (-36.8485, 174.7633)

COOLDOWN_FILE = Path(__file__).parent / "nw_cooldown.json"


def cooldown_remaining() -> float:
    try:
        until = json.loads(COOLDOWN_FILE.read_text())["until"]
        return max(0.0, until - time.time())
    except Exception:  # noqa: BLE001
        return 0.0


def record_cooldown(seconds: float) -> None:
    COOLDOWN_FILE.write_text(json.dumps({
        "until": time.time() + seconds,
        "reason": "cloudflare challenge / rate limit",
        "recorded_at": time.time(),
    }))


EXTRACT_NEXT_DATA_JS = """
(() => {
  const el = document.getElementById('__NEXT_DATA__');
  if (!el) return null;
  try { return el.textContent; } catch (e) { return null; }
})()
"""


def fetch_category_page(page, cat: str, pg: int) -> dict:
    """Load one NW category page in the given Playwright page; return the
    Algolia results object (first index result). Raises on challenge pages.
    """
    url = f"{BASE}/shop/category/{cat}?pg={pg}"
    resp = page.goto(url, timeout=45000, wait_until="domcontentloaded")
    page.wait_for_timeout(2500)
    if resp and resp.status == 429:
        record_cooldown(3600)
        raise RuntimeError(f"NW 429 on {cat} pg{pg} — cooling down 1h")
    if resp and resp.status == 403:
        # Challenge, not a rate-limit: no Retry-After, typically transient.
        record_cooldown(900)
        raise RuntimeError(f"NW 403 on {cat} pg{pg} — cooling down 15 min")
    raw = page.evaluate(EXTRACT_NEXT_DATA_JS)
    if not raw:
        # No embedded payload: either a challenge or a changed platform.
        title = page.title()
        if "Just a moment" in title:
            record_cooldown(3600)
            raise RuntimeError(f"NW Cloudflare challenge on {cat} pg{pg} — cooling down 1h")
        raise RuntimeError(f"No __NEXT_DATA__ on {cat} pg{pg} (title: {title[:40]})")
    data = json.loads(raw)
    return data["props"]["pageProps"]["serverState"]["initialResults"][
        "popularity-si"]["results"][0]


def hits_to_prices(hits: list[dict], store_id: int) -> list[Price]:
    out = []
    for h in hits:
        sp = h.get("singlePrice") or {}
        cents = sp.get("price")
        if cents is None:
            continue
        comp = sp.get("comparativePrice") or {}
        unit_price = None
        if comp.get("pricePerUnit"):
            unit_price = (f"${comp['pricePerUnit'] / 100:.2f}/"
                          f"{comp.get('unitQuantityUom', 'ea')}")
        name = f"{h.get('brand', '')} {h.get('name', '')}".strip()
        pid = str(h.get("productId") or "")
        image_url = (f"https://a.fsimg.co.nz/product/retail/fan/image/400x400/{pid}.png"
                     if pid.isdigit() and len(pid) >= 5 else None)
        out.append(Price(
            store_id=store_id,
            product_slug=slugify(name),
            product_name=name,
            price_cents=int(cents),
            currency="NZD",
            unit=h.get("displayName"),
            unit_price=unit_price,
            sku=pid,
            page_url=f"{BASE}/shop/product/{pid.lower()}?name={slugify(h.get('name', '')).replace(' ', '-')}",
            image_url=image_url,
        ))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", type=int, default=2, help="pages per category")
    ap.add_argument("--cats", nargs="*", help="subset of category slugs")
    ap.add_argument("--gap", type=float, default=10.0,
                    help="base seconds between page loads (jittered ±50%)")
    args = ap.parse_args()

    db = PriceDB()
    cats = args.cats or CATEGORIES

    with db.tx() as cur:
        cur.execute("SELECT id, lat, lng FROM stores WHERE brand LIKE 'New World%' "
                    "ORDER BY distance_km LIMIT 1")
        row = cur.fetchone()
    if row is None:
        dist = haversine_km(*ORIGIN, NW_LAT, NW_LNG)
        store = Store(osm_id="nw-online", name="New World (online)",
                      brand="New World", lat=NW_LAT, lng=NW_LNG,
                      distance_km=dist)
        store_id = db.upsert_store(store)
    else:
        store_id = row["id"]

    remaining = cooldown_remaining()
    if remaining > 0:
        until = time.strftime("%H:%M", time.localtime(time.time() + remaining))
        print(f"[cooldown] NW rate-limit active — skipping for "
              f"{remaining/3600:.1f}h (until {until})")
        return 0

    total_new, t0 = 0, time.time()
    print(f"📦 NW catalog scrape: {len(cats)} categories × {args.pages} pages "
          f"({args.gap}s gap) → store_id {store_id}")

    # Cloudflare tolerates the FIRST navigation in a fresh context; rapid
    # successors get challenged. So: fresh context per category, generous gap.
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=[
            "--no-sandbox", "--disable-blink-features=AutomationControlled"])

        def new_page():
            ctx = browser.new_context(viewport={"width": 1366, "height": 900},
                                      user_agent=UA, locale="en-NZ")
            ctx.add_init_script(
                "Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")
            return ctx, ctx.new_page()

        for cat in cats:
            ctx, page = None, None
            try:
                ctx, page = new_page()
                cat_new, seen_ids = 0, set()
                for pg in range(1, args.pages + 1):
                    res = fetch_category_page(page, cat, pg)
                    hits = res.get("hits", [])
                    if not hits:
                        break
                    prices = hits_to_prices(hits, store_id)
                    for pr in prices:
                        if pr.sku in seen_ids:
                            continue
                        seen_ids.add(pr.sku)
                        db.upsert_price(pr)
                        total_new += 1
                        cat_new += 1
                    if pg >= res.get("nbPages", 1):
                        break
                    time.sleep(args.gap * random.uniform(0.75, 1.25))
                print(f"  ✓ {cat}: {cat_new} products")
            except Exception as ex:  # noqa: BLE001
                print(f"  ✗ {cat}: {ex}")
            finally:
                if ctx:
                    ctx.close()
            time.sleep(args.gap * random.uniform(1.0, 1.5))

    stats = db.stats()
    print(f"\nDone in {(time.time() - t0) / 60:.1f} min · {total_new} prices · "
          f"DB: {stats['prices']} current / {stats['price_history']} history")
    return 0


if __name__ == "__main__":
    sys.exit(main())