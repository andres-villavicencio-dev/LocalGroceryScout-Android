"""Store discovery via OSM/Overpass and brand → online-shop mapping.

Discovery flow:
  1. Query Overpass for shop=supermarket within `radius_m` of the coords.
  2. Keep stores whose brand we can scrape online (BRAND_SITES whitelist).
  3. Persist to PriceDB with distance from origin.

The Foodstuffs chains (New World, Pak'nSave, Four Square) share one web
platform; the site auto-selects the nearest store from your IP, and exposes
a "change store" flow. v1 uses the IP-selected store and records its slug.
"""
from __future__ import annotations

import math
import re
import time
import urllib.parse
import urllib.request
from typing import Optional

from price_db import PriceDB, Store

# ---------------------------------------------------------------- brand sites

BRAND_SITES: dict[str, dict] = {
    "New World": {
        "platform": "foodstuffs",
        "search_url": "https://www.newworld.co.nz/shop/search?q={q}&pg=1",
        "product_url": "https://www.newworld.co.nz/shop/product/{sku}?name={slug}",
    },
    "Pak'nSave": {
        "platform": "foodstuffs",
        "search_url": "https://www.paknsave.co.nz/shop/search?q={q}&pg=1",
        "product_url": "https://www.paknsave.co.nz/shop/product/{sku}?name={slug}",
    },
    "Four Square": {
        # Four Square stores are convenience stores — NO online shop exists
        # (foursquare.co.nz/search returns "Oops, nothing to see here").
        "platform": "none",
        "reason": "no e-commerce site",
    },
    # Woolworths NZ sits behind Akamai bot detection; headless requests are
    # killed with ERR_HTTP2_PROTOCOL_ERROR. Revisit with residential proxies
    # or their mobile API. v1: discover the stores, mark as unsupported online.
    "Woolworths": {
        "platform": "unsupported",
        "reason": "Akamai bot detection blocks headless browsers",
    },
    "Countdown": {   # legacy brand name still tagged in OSM
        "platform": "unsupported",
        "reason": "rebranded to Woolworths; same Akamai block",
    },
}

SUPPORTED_BRANDS = [b for b, c in BRAND_SITES.items() if c["platform"] == "foodstuffs"]


def normalize_brand(raw: str) -> Optional[str]:
    """Map an OSM brand tag to a canonical BRAND_SITES key.

    OSM contributors are inconsistent: "PAK'nSAVE", "Pak'nSave", "paknsave",
    curly vs straight apostrophes. Normalize by lowercasing and stripping
    non-alphanumerics, then compare against the same normalization of every
    whitelist key.
    """
    if not raw:
        return None
    canon = {re.sub(r"[^a-z0-9]", "", k.lower()): k for k in BRAND_SITES}
    key = re.sub(r"[^a-z0-9]", "", raw.lower())
    return canon.get(key)


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


# ---------------------------------------------------------------- overpass

OVERPASS_URLS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

OVERPASS_QUERY = """
[out:json][timeout:25];
(
  node["shop"="supermarket"](around:{radius},{lat},{lng});
  way["shop"="supermarket"](around:{radius},{lat},{lng});
);
out center tags;
"""


def overpass_supermarkets(lat: float, lng: float, radius_m: int = 2500,
                          retries: int = 2, backoff_s: float = 2.0) -> list[dict]:
    """Query Overpass for supermarkets near a point; returns raw OSM elements."""
    q = OVERPASS_QUERY.format(radius=radius_m, lat=lat, lng=lng)
    last_err: Optional[Exception] = None
    for attempt in range(retries + 1):
        for base in OVERPASS_URLS:
            url = base + "?data=" + urllib.parse.quote(q)
            req = urllib.request.Request(
                url, headers={"User-Agent": "LocalGroceryScout/0.1 (price research; contact via GitHub)"})
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    import json
                    data = json.loads(resp.read().decode())
                return data.get("elements", [])
            except Exception as ex:  # noqa: BLE001 — want to try mirrors
                last_err = ex
                time.sleep(backoff_s * (attempt + 1))
    raise RuntimeError(f"Overpass failed on all mirrors: {last_err}")


def _address_from_tags(tags: dict) -> Optional[str]:
    parts = [
        tags.get("addr:housenumber"), tags.get("addr:street"),
        tags.get("addr:suburb"), tags.get("addr:city"),
    ]
    joined = ", ".join(p for p in parts if p)
    return joined or None


def discover_stores(lat: float, lng: float, db: PriceDB, radius_m: int = 2500,
                    supported_only: bool = True) -> list[Store]:
    """Find supermarkets near (lat, lng), persist them, return them."""
    elements = overpass_supermarkets(lat, lng, radius_m)
    stores: list[Store] = []
    for el in elements:
        tags = el.get("tags", {}) or {}
        raw_brand = tags.get("brand") or tags.get("name")
        name = tags.get("name") or raw_brand
        if not raw_brand or not name:
            continue
        brand = normalize_brand(raw_brand)
        if supported_only and brand is None:
            continue
        if brand is None:
            brand = raw_brand  # keep unknown brands in DB when supported_only=False
        if el.get("type") == "node":
            elat, elng = el.get("lat"), el.get("lon")   # OSM uses "lon", not "lng"!
        else:  # way → use centre
            c = el.get("center", {})
            elat, elng = c.get("lat"), c.get("lon")
        if elat is None or elng is None:
            continue
        store = Store(
            osm_id=f"{el['type'][0]}{el['id']}",
            name=name,
            brand=brand,
            lat=elat, lng=elng,
            address=_address_from_tags(tags),
            distance_km=haversine_km(lat, lng, elat, elng),
        )
        store.id = db.upsert_store(store)
        stores.append(store)
    stores.sort(key=lambda s: s.distance_km or 0)
    return stores


# ---------------------------------------------------------------- slugify

def slugify(text: str) -> str:
    s = text.lower().strip()
    s = re.sub(r"[^a-z0-9\s-]", "", s)
    s = re.sub(r"[\s-]+", " ", s)
    return s.strip()