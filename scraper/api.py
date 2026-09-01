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
    result["source"] = "live-scrape"
    result["duration_s"] = round(time.time() - t0, 1)
    return result