"""Huckleberry (NZ organic grocer) — Shopify JSON API scraper.

The cleanest integration in the whole project: Shopify exposes
  /search/suggest.json  → matched products with prices (search queries)
  /products.json        → full catalog, 250/page (catalog sweeps)
as plain JSON over bare HTTP. No browser, no DOM, no LLM matching —
names and prices arrive structured.

Verified live (Sep 2026): "milk" → Dairy Dale Blue Milk $4.49,
Anchor Blue Milk 2L $5.40; catalog = 1,877 products across 8 pages.
"""
from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Optional

import sys
sys.path.insert(0, str(Path(__file__).parent))
from price_db import PriceDB, Price
from store_discovery import slugify, haversine_km

BASE = "https://huckleberry.co.nz"
UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/151.0.0.0 Safari/537.36")

# Huckleberry Auckland stores (from their site) — pick the city-centre one for
# distance attribution from the default scout origin.
HUCK_LAT, HUCK_LNG = -36.8575, 174.7562   # Huckleberry Queen Street
ORIGIN = (-36.8485, 174.7633)


def _get(url: str, retries: int = 2) -> dict:
    last_ex: Exception | None = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode())
        except Exception as ex:  # noqa: BLE001
            last_ex = ex
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"huckleberry fetch failed: {last_ex}")


def search_products(query: str, limit: int = 12) -> list[dict]:
    """Search Huckleberry's catalog. Returns [{title, price, url}, ...]."""
    q = urllib.parse.quote(query)
    data = _get(
        f"{BASE}/search/suggest.json?q={q}"
        f"&resources[type]=product&resources[limit]={limit}")
    results = (data.get("resources", {}).get("results", {})
               .get("products", []))
    out = []
    for p in results:
        price_raw = p.get("price") or ""
        try:
            price = float(str(price_raw).replace("$", "").strip())
        except ValueError:
            continue
        out.append({
            "title": p.get("title", ""),
            "price": price,
            "url": f"{BASE}{p.get('url', '')}",
        })
    return out


def catalog_sweep(db: PriceDB, pages: int = 10, page_size: int = 250,
                  gap_s: float = 2.0) -> int:
    """Pull the whole catalog into the price DB (history + current snapshot).

    Returns the number of prices recorded. Each page = one request, so a
    full sweep of ~1,900 products is ~8-10 requests with polite gaps.
    """
    # Find or create the Huckleberry store row
    with db.tx() as cur:
        cur.execute("SELECT id FROM stores WHERE brand='Huckleberry' LIMIT 1")
        row = cur.fetchone()
    if row is not None:
        store_id = row["id"]
    else:
        from price_db import Store
        store = Store(
            osm_id="huck-online",
            name="Huckleberry (online)",
            brand="Huckleberry",
            lat=HUCK_LAT, lng=HUCK_LNG,
            address="Queen Street, Auckland",
            distance_km=haversine_km(*ORIGIN, HUCK_LAT, HUCK_LNG),
        )
        store_id = db.upsert_store(store)

    total = 0
    for page in range(1, pages + 1):
        data = _get(f"{BASE}/products.json?limit={page_size}&page={page}")
        products = data.get("products", [])
        if not products:
            break
        for prod in products:
            variants = prod.get("variants") or [{}]
            v0 = variants[0]
            price_raw = v0.get("price")
            try:
                price = float(price_raw)
            except (TypeError, ValueError):
                continue
            title = prod.get("title", "") or ""
            handle = prod.get("handle", "")
            db.upsert_price(Price(
                store_id=store_id,
                product_slug=slugify(title),
                product_name=title,
                price_cents=int(round(price * 100)),
                currency="NZD",
                unit="",                       # Shopify variants carry no size
                unit_price=None,
                sku=str(v0.get("sku") or prod.get("id") or ""),
                page_url=f"{BASE}/products/{handle}" if handle else None,
            ))
            total += 1
        if len(products) < page_size:
            break
        time.sleep(gap_s)
    return total


def search_to_prices(query: str, db: PriceDB) -> list[dict]:
    """Search Huckleberry for a query and persist matches as app-shaped rows."""
    hits = search_products(query)
    with db.tx() as cur:
        cur.execute("SELECT id FROM stores WHERE brand='Huckleberry' LIMIT 1")
        row = cur.fetchone()
    store_id = row["id"] if row else None
    rows = []
    for h in hits:
        if store_id:
            db.upsert_price(Price(
                store_id=store_id,
                product_slug=slugify(h["title"]),
                product_name=h["title"],
                price_cents=int(round(h["price"] * 100)),
                currency="NZD",
                unit="",
                sku="",
                page_url=h["url"],
            ))
        rows.append({
            "store": "Huckleberry",
            "storeChain": "Huckleberry",
            "price": h["price"],
            "currency": "NZD",
            "unit": None,
            "address": "Queen Street, Auckland",
            "distanceKm": round(haversine_km(*ORIGIN, HUCK_LAT, HUCK_LNG), 2),
            "confidence": 1.0,
            "reasoning": "scraped from Huckleberry's online shop (Shopify JSON)",
            "productName": h["title"],
            "url": h["url"],
        })
    return rows


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--sweep", action="store_true", help="full catalog sweep")
    ap.add_argument("--search", type=str, help="query test")
    args = ap.parse_args()
    db = PriceDB()
    if args.sweep:
        n = catalog_sweep(db)
        print(f"sweep done: {n} prices | DB: {db.stats()}")
    if args.search:
        for r in search_to_prices(args.search, db):
            print(f"  ${r['price']:.2f}  {r['productName'][:50]}")