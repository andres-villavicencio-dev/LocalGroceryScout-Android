"""Browser-agent price scraper for NZ Foodstuffs online shops.

Per brand (New World / Pak'nSave / Four Square):
  1. Open the brand's search page for the query.
  2. Extract every product tile: name, price, per-unit price, SKU, link.
  3. Ask the local ollama model to pick the best tile match(es) for the query
     (fuzzy matching: "milk" should match "Value Standard Milk 2L", not "milk
     powder" — and the LLM also normalises the unit).
  4. Persist prices to the PriceDB.

Anti-detection: real Chrome UA, en-NZ locale, webdriver property removed.
Woolworths NZ is not supported (Akamai); see store_discovery.BRAND_SITES.

The Foodstuffs sites auto-select the nearest store from IP — fine for our
use case (the phone/laptop sits on the same LAN as the user).
"""
from __future__ import annotations

import json
import re
import time
import urllib.request
from dataclasses import dataclass
from typing import Optional

from playwright.sync_api import sync_playwright

from price_db import PriceDB, Price
from store_discovery import BRAND_SITES, slugify

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/151.0.0.0 Safari/537.36")

# JS that walks a Foodstuffs search page and returns all product tiles.
# Uses the stable handles found during recon:
#   - card root:      [data-testid^="product-<SKU>"]  (excludes -search-bar etc.)
#   - price:          itemprop="price" meta (New World) or
#                     price-dollars/price-cents testids (Pak'nSave)
#   - name:           ?name=... slug in the product link
#   - per-unit price: [data-testid="non-promo-unit-price"]
EXTRACT_JS = """
() => {
  const out = [];
  const cards = document.querySelectorAll('[data-testid^="product-"]');
  for (const card of cards) {
    const tid = card.getAttribute('data-testid');
    if (!/^product-\\d+-/.test(tid)) continue;      // skip -search-bar, -title, …
    const meta = card.querySelector('meta[itemprop="price"]');
    let price = meta ? parseFloat(meta.getAttribute('content')) : null;
    let currency = card.querySelector('meta[itemprop="priceCurrency"]')?.getAttribute('content') || 'NZD';
    if (price === null || isNaN(price)) {
      const d = card.querySelector('[data-testid="price-dollars"]')?.innerText?.trim();
      const c = card.querySelector('[data-testid="price-cents"]')?.innerText?.trim();
      if (d !== undefined && c !== undefined && d !== null && c !== null) {
        price = parseFloat(d) + parseFloat(c) / 100;
      }
    }
    // per-unit price: [data-testid="non-promo-unit-price"]
    const per_unit = card.querySelector('[data-testid="non-promo-unit-price"]')?.innerText?.trim() || null;
    const link = card.querySelector('a[href*="/product/"]');
    const href = link ? link.getAttribute('href') : null;
    let slug = null;
    if (href) {
      const m = href.match(/[?&]name=([^&]+)/);
      if (m) {
        try { slug = decodeURIComponent(m[1]); } catch (e) { slug = m[1]; }
      }
    }
    // Fallback title: aria-label or title attribute on the link
    let title = link?.getAttribute('title') || link?.getAttribute('aria-label') || null;
    // New World puts name in a heading inside the card
    if (!title) {
      const h = card.querySelector('[data-testid="product-title"], h3, [class*="productName"]');
      title = h?.innerText?.trim() || null;
    }
    // SKU from the data-testid
    const sku = tid.replace(/^product-/, '');
    if (price !== null && !isNaN(price)) {
      out.push({sku, slug, title, price, currency, per_unit, href});
    }
  }
  return out;
}
"""


@dataclass
class Tile:
    sku: str
    slug: Optional[str]
    title: Optional[str]
    price: float
    currency: str
    per_unit: Optional[str]
    href: Optional[str]


@dataclass
class LLMChoice:
    sku: str
    match_confidence: float
    canonical_name: str
    unit: Optional[str]
    reasoning: str


def extract_tiles(page, query: str) -> list[Tile]:
    """Load the search page for `query` and extract all product tiles."""
    tiles: list[Tile] = []
    for attempt in range(2):
        try:
            page.wait_for_selector('[data-testid^="product-"]', timeout=20000)
            page.wait_for_timeout(2500)  # let per-unit prices settle
            break
        except Exception:
            if attempt == 1:
                return tiles
            page.wait_for_timeout(3000)
    raw = page.evaluate(EXTRACT_JS)
    seen = set()
    for r in raw:
        if r["sku"] in seen:
            continue
        seen.add(r["sku"])
        tiles.append(Tile(**r))
    return tiles


# ------------------------------------------------------------------ ollama

OLLAMA_URL = "http://192.168.1.72:11434/api/chat"
MATCH_MODEL = "gemma4:e4b"

MATCH_SYSTEM = """You match grocery search queries to product listings from NZ supermarket websites.
Given a user query and a JSON array of product tiles (name/slug, per-unit price), pick the best matches.

Rules:
- Respond with STRICT JSON only: {"matches": [{"sku": str, "confidence": 0.0-1.0, "canonical_name": str, "unit": str|null, "reasoning": str}]}
- Match the user's intent: "milk" = fresh standard milk (2L or 3L bottles), NOT milk powder, flavoured, or UHT long-life unless nothing else matches.
- Prefer exact category matches; confidence < 0.5 if unsure.
- canonical_name: HUMAN-READABLE display name, title case, e.g. "Value Standard Milk 2L" — do NOT echo the slug with dashes.
- unit: derive the pack size from per_unit (e.g. price $4.89 with per_unit "$2.45/1L" → unit "2L"; $8.99 with "$3.00/1L" → "3L"). If no per_unit, use the size from the name, else null.
- Return at most 3 matches, best first.
- No prose outside JSON."""


def llm_match_tiles(query: str, tiles: list[Tile]) -> list[LLMChoice]:
    """Ask ollama to pick which tiles best satisfy the query."""
    tile_payload = [
        {"sku": t.sku, "name": t.slug or t.title or t.sku, "per_unit": t.per_unit}
        for t in tiles
    ][:40]
    payload = {
        "model": MATCH_MODEL,
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.1, "num_ctx": 8192},
        "messages": [
            {"role": "system", "content": MATCH_SYSTEM},
            {"role": "user", "content": f'Query: "{query}"\n\nTiles:\n{json.dumps(tile_payload, ensure_ascii=False)}'},
        ],
    }
    # gemma4:e4b occasionally emits malformed JSON even with format:json.
    # One retry with a corrective nudge turns a flaky failure into a delay.
    last_err: Exception | None = None
    for attempt in range(2):
        if attempt == 1:
            payload["messages"] = payload["messages"] + [{
                "role": "user",
                "content": "Your previous reply was invalid JSON. Respond again with STRICT JSON only, no prose, no trailing commas.",
            }]
        req = urllib.request.Request(
            OLLAMA_URL,
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=180) as resp:
            body = json.loads(resp.read().decode())
        content = body["message"]["content"].strip()
        # defensive: strip fences/prose
        if content.startswith("```"):
            content = re.sub(r"^```[a-z]*\n?", "", content)
            content = re.sub(r"\n?```$", "", content)
        start = content.find("{")
        end = content.rfind("}")
        if start >= 0 and end > start:
            content = content[start:end + 1]
        try:
            data = json.loads(content)
            last_err = None
            break
        except json.JSONDecodeError as ex:
            last_err = ex
    else:
        raise ValueError(f"LLM matcher returned invalid JSON twice: {last_err}")
    out = []
    by_sku = {t.sku: t for t in tiles}
    for m in data.get("matches", []):
        t = by_sku.get(m.get("sku"))
        if not t:
            continue
        out.append(LLMChoice(
            sku=m["sku"],
            match_confidence=float(m.get("confidence", 0.5)),
            canonical_name=m.get("canonical_name") or t.slug or t.title or t.sku,
            unit=m.get("unit"),
            reasoning=m.get("reasoning", ""),
        ))
    return out


# ------------------------------------------------------------------ agent

class PriceAgent:
    def __init__(self, db: PriceDB, ollama_url: str = OLLAMA_URL, model: str = MATCH_MODEL,
                 headless: bool = True):
        self.db = db
        self.ollama_url = ollama_url
        self.model = model

    def scrape_brand(self, brand: str, query: str, store: Optional = None) -> list[Tile]:
        """Scrape one brand's site for `query`; returns matched tiles."""
        cfg = BRAND_SITES.get(brand)
        if not cfg or cfg["platform"] != "foodstuffs":
            return []
        url = cfg["search_url"].format(q=urllib.parse.quote(query))
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True, args=[
                "--no-sandbox", "--disable-blink-features=AutomationControlled"])
            ctx = browser.new_context(viewport={"width": 1366, "height": 900},
                                      user_agent=UA, locale="en-NZ")
            ctx.add_init_script("Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")
            page = ctx.new_page()
            try:
                page.goto(url, timeout=45000, wait_until="domcontentloaded")
                page.wait_for_timeout(5000)
                tiles = extract_tiles(page, query)
            finally:
                browser.close()
        return tiles

    def run(self, query: str, lat: float, lng: float, region: str = "NZ",
            radius_m: int = 6000, top_n_stores: int = 3) -> dict:
        """Full agent run: discover stores → scrape each brand → persist prices.

        Returns a result dict mirroring the app's SearchResult JSON schema.
        """
        from store_discovery import discover_stores, SUPPORTED_BRANDS
        t0 = time.time()
        stores = discover_stores(lat, lng, self.db, radius_m, supported_only=True)

        # Scrape each brand's ONLINE shop once — the site exists regardless of
        # whether a physical branch is within radius. But only for brands that
        # have at least one store nearby (nobody wants Pak'nSave prices when
        # the nearest PnS is 40km away... except we can't know the PnS site's
        # store-selection from IP alone, so scrape all supported brands and
        # let distance sort it out — the Foodstuffs sites auto-select the
        # closest branch to our IP anyway, which is in Auckland for this host).
        brands_to_scrape = list(SUPPORTED_BRANDS)

        all_rows: list[dict] = []
        stores_checked = 0
        for brand in brands_to_scrape:
            # Nearest physical store of this brand, for distance attribution
            brand_store = next((s for s in stores if s.brand == brand), None)
            if brand_store is None:
                # No physical store nearby → still scrape online prices but
                # attribute them to the brand's nearest listed store? No —
                # skip: a price 40km away isn't useful for a "near me" app.
                # Exception: we still record it with distance=None if the
                # online shop is reachable, because the online price is the
                # chain's reference price. v1: skip.
                print(f"  [{brand}] no physical store within {radius_m}m — skipping")
                continue
            store = brand_store
            try:
                tiles = self.scrape_brand(brand, query)
            except Exception as ex:  # noqa: BLE001
                print(f"  [{brand}] scrape failed: {ex}")
                continue
            if not tiles:
                continue
            stores_checked += 1
            try:
                choices = llm_match_tiles(query, tiles)
            except Exception as ex:  # noqa: BLE001
                print(f"  [{brand}] LLM match failed: {ex}")
                continue
            for ch in choices:
                tile = next((t for t in tiles if t.sku == ch.sku), None)
                if not tile:
                    continue
                self.db.upsert_price(Price(
                    store_id=store.id,
                    product_slug=slugify(ch.canonical_name),
                    product_name=ch.canonical_name,
                    price_cents=int(round(tile.price * 100)),
                    currency=tile.currency,
                    unit=ch.unit,
                    unit_price=tile.per_unit,
                    sku=tile.sku,
                    page_url=(BRAND_SITES[brand]["product_url"]
                              .format(sku=tile.sku.replace("-EA-000", "_ea_000"),
                                      slug=urllib.parse.quote((tile.slug or ch.canonical_name).replace(" ", "-").lower()))
                              if tile.href is None else f"https://{brand.lower().replace(chr(39), '').replace(' ', '')}.co.nz{tile.href}"),
                ))
                # Foodstuffs CDN product image (verified pattern, 400x400 PNG)
                sku_digits = (tile.sku or "").split("-")[0].split("_")[0]
                image_url = (f"https://a.fsimg.co.nz/product/retail/fan/image/400x400/{sku_digits}.png"
                             if sku_digits.isdigit() and len(sku_digits) >= 5 else None)
                all_rows.append({
                    "store": store.name, "storeChain": brand,
                    "price": tile.price, "currency": tile.currency,
                    "unit": ch.unit or (tile.per_unit or "").replace("$", "").split("/")[-1] or None,
                    "address": store.address or "unknown",
                    "distanceKm": round(store.distance_km, 2) if store.distance_km else None,
                    "imageUrl": image_url,
                    "confidence": round(ch.match_confidence, 2),
                    "reasoning": ch.reasoning,
                    "source": "scraped",
                    "productName": ch.canonical_name,
                    "url": tile.href,
                })

        all_rows.sort(key=lambda r: r["price"])
        duration = time.time() - t0
        self.db.log_query(query, lat, lng, region, stores_checked, len(all_rows), duration)
        return {
            "query": query,
            "productName": all_rows[0]["productName"] if all_rows else "",
            "results": all_rows,
            "summary": (f"Scraped {stores_checked} store sites live; cheapest: "
                        f"{all_rows[0]['store']} ${all_rows[0]['price']:.2f}" if all_rows
                        else "No live prices found"),
            "source": "browser-agent-scrape",
            "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "duration_s": round(duration, 1),
        }


import urllib.parse  # noqa: E402  (used above; keep import at bottom to avoid clutter)