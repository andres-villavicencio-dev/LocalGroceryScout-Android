#!/usr/bin/env python3
"""The Warehouse grocery catalog sweep via product sitemaps + JSON-LD.

The Warehouse's product pages expose schema.org Product JSON-LD readable
via bare HTTP (name + price + NZD). Their sitemaps enumerate every product
URL; the URL slug itself (e.g. arnotts-tim-tam-biscuits-200g) lets us
filter to groceries before fetching.

Politeness: 1.5s jittered gaps, ~250-400 grocery URLs per sweep ≈ 8-10 min.
Not scheduled with the 7am/7pm cron's other phases (that would stack
The Warehouse + PnS sweeps together); run via the runner on the 7pm fire
where the catalog gate already paces PnS.
"""
import argparse
import json
import random
import re
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from price_db import PriceDB, Price
from store_discovery import slugify

UA = {"User-Agent": ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                     "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"),
      "Accept": "text/html"}

BASE = "https://www.thewarehouse.co.nz"
SITEMAP_PRODUCT_RE = re.compile(r"<loc>https://www\.thewarehouse\.co\.nz/sitemap_(\d+)-product\.xml</loc>")

# grocery keywords for slug filtering. Conservative: false positives just cost
# a fetch; false negatives miss products.
GROCERY_KW = [
    "milk", "bread", "biscuit", "cereal", "coffee", "tea-bags", "rice",
    "pasta", "noodle", "juice", "sauce", "flour", "sugar", "honey", "milo",
    "cocoa", "chocolate", "confectionery", "lolly", "chips", "popcorn",
    "soup", "beans", "tomato", "tuna", "salmon", "oil", "vinegar", "salt",
    "pepper", "spice", "herb", "custard", "jelly", "peanut-butter", "jam",
    "spread", "muesli", "oats", "porridge", "cracker", "snack", "drink",
    "water", "cola", "lemonade", "butter", "cheese", "yoghurt", "yogurt",
    "cream", "egg", "flavoured-milk", "protein", "vegemite", "marmite",
    "hummus", "salsa", "dip-", "-dip", "cake-mix", "icing", "baking",
]

def get(url, timeout=25):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode(errors="replace")

def sitemap_index_urls():
    idx = get(f"{BASE}/sitemap_index.xml")
    nums = SITEMAP_PRODUCT_RE.findall(idx)
    return [f"{BASE}/sitemap_{n}-product.xml" for n in nums]

def grocery_urls(limit_pages=2):
    urls = []
    sms = sitemap_index_urls()
    for sm in sms[:limit_pages]:
        body = get(sm, timeout=30)
        for loc in re.findall(r"<loc>([^<]+)</loc>", body):
            slug = loc.lower()
            if any(k in slug for k in GROCERY_KW):
                urls.append(loc)
        time.sleep(1.0)
    return sorted(set(urls))

def jsonld_price(url):
    html = get(url)
    for b in re.findall(r'<script type="application/ld\+json">(.*?)</script>', html, re.S):
        try:
            d = json.loads(b)
        except json.JSONDecodeError:
            continue
        if d.get("@type") == "Product":
            offers = d.get("offers", {})
            try:
                price = float(offers.get("price"))
            except (TypeError, ValueError):
                continue
            # Product image: JSON-LD image may be a string or a list of
            # strings/objects ("ImageObject"/"url").
            img = d.get("image")
            if isinstance(img, list):
                img = img[0] if img else None
            if isinstance(img, dict):
                img = img.get("url")
            return {"name": d.get("name", ""), "price": price, "image": img}
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", type=int, default=10, help="product sitemaps to walk (each ~5k URLs)")
    ap.add_argument("--limit", type=int, default=400, help="max product pages to fetch")
    args = ap.parse_args()

    db = PriceDB()
    with db.tx() as cur:
        cur.execute("SELECT id FROM stores WHERE brand='The Warehouse' LIMIT 1")
        row = cur.fetchone()
    store_id = row["id"] if row else None

    t0 = time.time()
    urls = grocery_urls(limit_pages=args.pages)
    print(f"[tw-catalog] {len(urls)} grocery-ish URLs from {min(args.pages, 10)} sitemap pages")
    n_ok = 0
    for i, u in enumerate(urls[:args.limit]):
        try:
            p = jsonld_price(u)
        except Exception:
            p = None
        if p and store_id:
            db.upsert_price(Price(
                store_id=store_id,
                product_slug=slugify(p["name"]),
                product_name=p["name"],
                price_cents=int(round(p["price"] * 100)),
                currency="NZD",
                unit="",
                sku=u.rsplit("/", 1)[-1].replace(".html", ""),
                page_url=u,
                image_url=p.get("image"),
            ))
            n_ok += 1
        if (i + 1) % 50 == 0:
            print(f"  {i+1}/{min(len(urls), args.limit)} fetched ({n_ok} priced)")
        time.sleep(1.5 * random.uniform(0.8, 1.3))
    stats = db.stats()
    print(f"[tw-catalog] done in {(time.time()-t0)/60:.1f} min: {n_ok} prices · "
          f"DB {stats['prices']} current / {stats['price_history']} history")

if __name__ == "__main__":
    main()
