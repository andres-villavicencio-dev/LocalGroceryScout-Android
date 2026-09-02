"""FastAPI service exposing the scraper agent + price DB to the Android app.

Endpoints:
  GET  /health              — liveness + DB stats
  GET  /prices              — all cached prices (with optional store filter)
  POST /search              — the main endpoint. Body:
                                {"query": "milk", "lat": -36.85, "lng": 174.76,
                                 "region": "NZ", "radius_m": 6000, "force_refresh": false}
                              Freshness policy: if we have prices for a matching
                              product scraped within FRESH_WINDOW_S, serve cache
                              (fast). Otherwise run the browser agent (slow,
                              30-90s) and persist.
                              Response mirrors the app's SearchResult schema,
                              with a "source" field: "cache" or "live-scrape".

Run:  uvicorn api:app --host 0.0.0.0 --port 8300
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

sys.path.insert(0, str(Path(__file__).parent))

from price_db import PriceDB                     # noqa: E402
from price_agent import PriceAgent               # noqa: E402

DB_PATH = Path(__file__).parent / "prices.db"
FRESH_WINDOW_S = 3 * 24 * 3600   # 3 days — supermarket prices move weekly anyway

db = PriceDB(DB_PATH)
agent = PriceAgent(db)
app = FastAPI(title="Grocery Scout Scraper API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],            # the Android app has no origin; fine
    allow_methods=["*"],
    allow_headers=["*"],
)


class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=80)
    lat: float
    lng: float
    region: str = "NZ"
    radius_m: int = 6000
    force_refresh: bool = False


@app.get("/health")
def health():
    return {"ok": True, "stats": db.stats(),
            "fresh_window_s": FRESH_WINDOW_S}


@app.get("/prices")
def list_prices(store: str | None = None, limit: int = 200):
    with db.tx() as cur:
        if store:
            cur.execute(
                """SELECT p.*, s.name AS store_name, s.brand AS store_brand
                   FROM prices p JOIN stores s ON s.id=p.store_id
                   WHERE s.brand LIKE ? ORDER BY p.scraped_at DESC LIMIT ?""",
                (store, limit))
        else:
            cur.execute(
                """SELECT p.*, s.name AS store_name, s.brand AS store_brand
                   FROM prices p JOIN stores s ON s.id=p.store_id
                   ORDER BY p.scraped_at DESC LIMIT ?""", (limit,))
        return [dict(r) for r in cur.fetchall()]


class HistoryRequest(BaseModel):
    query: str = Field(min_length=1, max_length=80)
    days: int = Field(default=90, ge=1, le=365)
    store: str | None = None      # filter by store brand, e.g. "Pak'nSave"


@app.get("/tracked")
def list_tracked():
    return {"items": db.tracked_items()}


class TrackedAddRequest(BaseModel):
    query: str = Field(min_length=1, max_length=80)


@app.post("/tracked")
def add_tracked(req: TrackedAddRequest):
    db.add_tracked(req.query)
    return {"ok": True, "items": db.tracked_items()}


class TrackedRemoveRequest(BaseModel):
    query: str = Field(min_length=1, max_length=80)


@app.post("/tracked/remove")
def remove_tracked(req: TrackedRemoveRequest):
    db.remove_tracked(req.query)
    return {"ok": True, "items": db.tracked_items()}


@app.post("/tracked/seed")
def seed_tracked():
    return {"ok": True, "items": db.seed_tracked()}


@app.post("/history")
def history(req: HistoryRequest):
    """Time series of scraped prices for charting.

    Returns every scrape (append-only) matching the query, oldest first,
    grouped per store so the app can draw one line per chain.
    """
    from store_discovery import slugify
    rows = db.price_history(slugify(req.query), days=req.days)
    if req.store:
        rows = [r for r in rows if req.store.lower() in (r.get("store_brand") or "").lower()]

    # Group by (store_brand, product) for per-line chart series
    series: dict[tuple, list[dict]] = {}
    for r in rows:
        key = (r["store_brand"], r["product_slug"])
        series.setdefault(key, []).append({
            "t": r["scraped_at"],                   # unix seconds
            "date": time.strftime("%Y-%m-%d", time.localtime(r["scraped_at"])),
            "price": r["price_cents"] / 100,
            "currency": r["currency"],
            "unit": r["unit"],
            "unit_price": r["unit_price"],
        })
    return {
        "query": req.query,
        "days": req.days,
        "series": [
            {
                "store": brand,
                "product": slug,
                "points": pts,
                "min": min(p["price"] for p in pts),
                "max": max(p["price"] for p in pts),
                "latest": pts[-1]["price"],
            }
            for (brand, slug), pts in sorted(series.items())
        ],
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }


@app.post("/search")
def search(req: SearchRequest):
    from store_discovery import slugify
    t0 = time.time()
    qslug = slugify(req.query)

    # --- freshness policy: serve cache if fresh enough -----------------------
    if not req.force_refresh:
        cached = db.fresh_prices_for_query(qslug, FRESH_WINDOW_S)
        if cached:
            cached.sort(key=lambda r: (r.get("distance_km") or 999, r["price_cents"]))
            results = [{
                "store": r["store_name"], "storeChain": r["store_brand"],
                "price": r["price_cents"] / 100, "currency": r["currency"],
                "unit": r["unit"], "address": "unknown",
                "distanceKm": round(r["distance_km"], 2) if r["distance_km"] else None,
                "confidence": 1.0,   # scraped price = ground truth
                "reasoning": "cached scrape",
                "productName": r["product_name"],
                "url": r["page_url"],
            } for r in cached]
            cheapest = min(results, key=lambda r: r["price"])
            return {
                "query": req.query,
                "productName": results[0]["productName"],
                "results": results,
                "summary": f"Cached prices (scraped {time.strftime('%a %H:%M', time.localtime(db.stats()['last_scrape']))}); "
                           f"cheapest: {cheapest['store']} ${cheapest['price']:.2f}",
                "source": "cache",
                "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                "duration_s": round(time.time() - t0, 2),
            }

    # --- live scrape ---------------------------------------------------------
    result = agent.run(req.query, req.lat, req.lng, req.region,
                       radius_m=req.radius_m)

    # Huckleberry (Shopify JSON API) — always query it on live scrapes; the
    # clean JSON prices merge into the same result shape.
    try:
        import vision_scraper
        v_rows = vision_scraper.vision_search(req.query, db)
        if v_rows:
            result["results"].extend(v_rows)
            result["results"].sort(key=lambda r: r["price"])
    except Exception as ex:  # noqa: BLE001
        print(f"[vision] search failed: {ex}")

    try:
        import huckleberry
        h_rows = huckleberry.search_to_prices(req.query, db)
        if h_rows:
            result["results"].extend(h_rows)
            result["results"].sort(key=lambda r: r["price"])
            result["summary"] = (
                f"{result['summary']} · Huckleberry: {len(h_rows)} more"
                if result["summary"] else
                f"Huckleberry: {len(h_rows)} prices"
            )
    except Exception as ex:  # noqa: BLE001 — never fail the search over this
        print(f"[huckleberry] search failed: {ex}")

    result["source"] = "live-scrape"
    result["duration_s"] = round(time.time() - t0, 1)
    return result