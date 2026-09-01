#!/usr/bin/env python3
"""Catalog scraper: walks PAK'nSave's category tree via bare HTTP.

The category pages embed the full product payload (Algolia initialResults)
in __NEXT_DATA__ — no browser, no LLM. Each product hit carries:
  productId ("5092718-EA-000"), brand, name, displayName (pack size),
  singlePrice.price in CENTS, comparativePrice (per-unit info).

Every scrape appends to price_history (the chart time series) and updates
the current-price snapshot in `prices`. Store attribution: all products
belong to the IP-selected PAK'nSAVE store.

Usage:
  python3 catalog_scraper.py                 # all categories, 2 pages each
  python3 catalog_scraper.py --pages 4       # deeper walk
  python3 catalog_scraper.py --cats pantry drinks
"""
import argparse
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from price_db import PriceDB, Price
from store_discovery import haversine_km, slugify

HDRS = {
    "User-Agent": ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"),
    "Accept": "text/html,application/xhtml+xml",
}

# Real aisles (the shop menu). 'featured' excluded — it's marketing, not groceries.
CATEGORIES = [
    "pantry", "chilled-frozen-and-desserts", "fruit-and-vegetables",
    "meat-poultry-and-seafood", "fridge-deli-and-eggs", "bakery",
    "drinks", "hot-and-cold-drinks", "snacks-treats-and-easy-meals",
    "household-and-cleaning", "health-and-body", "baby-and-toddler",
    "pets", "frozen", "beer-wine-and-cider",
]
BASE = "https://www.paknsave.co.nz"
# PAK'nSave Botany (nearest with online shop to the default origin)
PNS_LAT, PNS_LNG = -36.9480, 174.9060
ORIGIN = (-36.8485, 174.7633)


def fetch_category_page(cat: str, pg: int, retries: int = 2) -> dict:
    """GET one category page, return the Algolia results object."""
    url = f"{BASE}/shop/category/{cat}?pg={pg}"
    last_ex: Exception | None = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers=HDRS)
            with urllib.request.urlopen(req, timeout=30) as r:
                html = r.read().decode(errors="replace")
            data = json.loads(re.search(
                r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S).group(1))
            return data["props"]["pageProps"]["serverState"][
                "initialResults"]["popularity-si"]["results"][0]
        except Exception as ex:  # noqa: BLE001
            last_ex = ex
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"{cat} pg{pg}: {last_ex}")


def hits_to_prices(hits: list[dict], store_id: int) -> list:
    from price_agent import Price  # avoid circular import at module top
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
        out.append(Price(
            store_id=store_id,
            product_slug=slugify(name),
            product_name=name,
            price_cents=int(cents),
            currency="NZD",
            unit=h.get("displayName"),          # pack size, e.g. "400g"
            unit_price=unit_price,
            sku=h.get("productId"),
            page_url=f"{BASE}/shop/product/{h.get('productId', '').lower()}?name={slugify(h.get('name', '')).replace(' ', '-')}",
        ))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", type=int, default=2, help="pages per category")
    ap.add_argument("--cats", nargs="*", help="subset of category slugs")
    ap.add_argument("--gap", type=float, default=1.5, help="seconds between requests")
    args = ap.parse_args()

    db = PriceDB()
    cats = args.cats or CATEGORIES

    # Find (or create) the PAK'nSAVE store row for attribution
    with db.tx() as cur:
        cur.execute("SELECT id, lat, lng FROM stores WHERE brand LIKE 'Pak%Save' ORDER BY distance_km LIMIT 1")
        row = cur.fetchone()
    if row is None:
        from price_db import Store
        dist = haversine_km(*ORIGIN, PNS_LAT, PNS_LNG)
        store = Store(osm_id="pns-online", name="Pak'nSave (online)",
                      brand="Pak'nSave", lat=PNS_LAT, lng=PNS_LNG,
                      distance_km=dist)
        store_id = db.upsert_store(store)
    else:
        store_id = row["id"]

    total_new, t0 = 0, time.time()
    print(f"📦 Catalog scrape: {len(cats)} categories × {args.pages} pages "
          f"({args.gap}s gap) → store_id {store_id}")
    for cat in cats:
        try:
            seen_ids: set[str] = set()
            cat_new, cat_max_page = 0, args.pages
            for pg in range(1, cat_max_page + 1):
                res = fetch_category_page(cat, pg)
                hits = res.get("hits", [])
                if not hits:
                    break
                prices = hits_to_prices(hits, store_id)
                for p in prices:
                    db.upsert_price(p)
                    total_new += 1
                    cat_new += 1
                if pg >= res.get("nbPages", 1):
                    break
                time.sleep(args.gap)
            print(f"  ✓ {cat}: {cat_new} products")
        except Exception as ex:  # noqa: BLE001
            print(f"  ✗ {cat}: {ex}")
        time.sleep(args.gap)

    stats = db.stats()
    print(f"\nDone in {(time.time() - t0) / 60:.1f} min · {total_new} prices · "
          f"DB: {stats['prices']} current / {stats['price_history']} history")
    return 0


if __name__ == "__main__":
    sys.exit(main())
