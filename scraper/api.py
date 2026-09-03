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
import json
import time
import urllib.request
import base64
import re
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
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


class ReceiptScanJSON(BaseModel):
    """Base64 fallback for clients that can't do multipart."""
    image_b64: str = Field(min_length=100)


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



def fs_image_url(sku: str | None) -> str | None:
    """Foodstuffs CDN product image URL from a SKU (e.g. 5260709-EA-000).

    Pattern verified live: a.fsimg.co.nz/product/retail/fan/image/400x400/
    <digits>.png — the digits are the leading numeric part of the SKU.
    Returns None for SKUs from other platforms (no guessing 404s).
    """
    if not sku:
        return None
    digits = sku.split("-")[0].split("_")[0]
    if not digits.isdigit() or len(digits) < 5:
        return None
    return f"https://a.fsimg.co.nz/product/retail/fan/image/400x400/{digits}.png"


# ── Same-product cross-chain comparison ──────────────────────────────────────

COMPARE_STOP = {"the", "and", "with", "new", "zealand", "original", "fresh",
                "size", "pack", "ea", "value", "standard", "choice"}


def _sig_tokens(name: str) -> set:
    return {t for t in re.split(r"[^a-z0-9]+", name.lower())
            if len(t) >= 2 and t not in COMPARE_STOP}


class CompareRequest(BaseModel):
    productName: str = Field(min_length=1, max_length=160)


@app.post("/compare")
def compare(req: CompareRequest):
    """Find the SAME product (same brand, type, and pack size) sold under a
    DIFFERENT name at other chains — for the 'also available at' section.

    Two-stage matching:
      1. candidate pool via token-overlap (Jaccard >= 0.25), price rows only
      2. one gemma4 call verifies same-product-AND-same-pack-size (shops name
         things differently; 2L vs 1L of the same line is NOT a match)
    Returns absolute prices; the app computes deltas vs its cheapest row.
    """
    from price_agent import OLLAMA_URL, MATCH_MODEL
    anchor = req.productName.strip()
    anchor_toks = _sig_tokens(anchor)
    if not anchor_toks:
        return {"matches": []}

    like = " OR ".join(f"p.product_slug LIKE '%' || ? || '%'" for _ in anchor_toks)
    with db.tx() as cur:
        cur.execute(
            f"""SELECT p.product_name, p.product_slug, MIN(p.price_cents) AS price_cents,
                       p.unit, p.sku, p.page_url,
                       s.brand AS store_brand, s.name AS store_name, s.id AS store_id
                FROM prices p JOIN stores s ON s.id = p.store_id
                WHERE ({like})
                GROUP BY p.product_name, s.brand""",
            tuple(anchor_toks))
        pool = [dict(r) for r in cur.fetchall()]

    anchor_lc = anchor.lower()
    exact_hits, scored = [], []
    for r in pool:
        # Exact-name match at a DIFFERENT chain = guaranteed same product,
        # skip the LLM. (Same name, same brand — the reliable case.)
        if r["product_name"].lower() == anchor_lc:
            exact_hits.append(r)
            continue
        toks = _sig_tokens(r["product_slug"])
        if not toks:
            continue
        jac = len(anchor_toks & toks) / len(anchor_toks | toks)
        if jac >= 0.34:  # tighter: reworded candidates need real overlap
            r["similarity"] = jac
            scored.append(r)
    scored.sort(key=lambda r: -r["similarity"])
    candidates = scored[:12]

    # Build verified matches: exact hits first (no LLM), then LLM-checked ones.
    def _row(c):
        return {
            "store": c["store_name"], "storeChain": c["store_brand"],
            "price": c["price_cents"] / 100, "currency": "NZD",
            "unit": c["unit"] or None, "productName": c["product_name"],
            "imageUrl": fs_image_url(c["sku"]), "url": c["page_url"],
        }

    seen_chains = set()
    verified = []
    for c in exact_hits:
        if c["store_brand"] in seen_chains:
            continue
        seen_chains.add(c["store_brand"])
        row = _row(c); row["reasoning"] = "same product name at another chain"
        verified.append(row)

    if not candidates and not verified:
        return {"matches": []}

    # LLM verification: which reworded candidates are the same product + pack size?
    data = {"matches": []}
    if candidates:
      cand_payload = [
          {"index": i, "name": c["product_name"], "unit": c["unit"] or "",
           "store": c["store_brand"], "price": c["price_cents"] / 100}
          for i, c in enumerate(candidates)
      ]
      sys_prompt = """You compare grocery products listed by different NZ supermarket chains. The SAME product is often named differently per shop (e.g. "Anchor Blue Milk 2L" = "Anchor Whole Milk 2L Bottle", "Weet-Bix 1.2kg" = "Sanitarium Weet-Bix 1.2kg").
Given an ANCHOR product and a list of CANDIDATES, identify which candidates are the SAME product AND SAME pack size as the anchor.
Rules:
- Brand must match (Anchor vs Pams is NOT the same product).
- Same product line but different pack size (2L vs 1L) is NOT a match.
- Different flavour/variant (Blue vs Trim, Original vs Chocolate) is NOT a match.
- Ignore wording differences like "Bottle"/"Bag"/"Fresh"/manufacturer prefix — judge the actual product.
- Respond STRICT JSON only: {"matches": [{"index": <int>, "confidence": 0.0-1.0}]}
- Return [] if none match. Only include confidence >= 0.6."""
      payload = {
          "model": MATCH_MODEL,
          "stream": False,
          "format": "json",
          "options": {"temperature": 0.1, "num_ctx": 8192},
          "messages": [
              {"role": "system", "content": sys_prompt},
              {"role": "user", "content": f'Anchor product: "{anchor}"\n\nCandidates:\n{json.dumps(cand_payload, ensure_ascii=False)}'},
          ],
      }
      # Same resilience ladder as llm_match_tiles: primary → local fallback.
      from price_agent import _ollama_chat, _strip_to_json, FALLBACK_MODEL, FALLBACK_TIMEOUT_S
      data = None
      for model, extra, timeout_s in [
          (MATCH_MODEL, {}, 60),
          (FALLBACK_MODEL, {"think": False}, FALLBACK_TIMEOUT_S),
      ]:
          try:
              body = _ollama_chat({**payload, "model": model, **extra}, timeout_s)
              data = json.loads(_strip_to_json(body["message"]["content"]))
              if model != MATCH_MODEL:
                  print(f"[compare] fallback {model} rescued the match")
              break
          except Exception as ex:  # noqa: BLE001
              print(f"[compare] {model} failed: {ex}")
              data = {"matches": []}

    for m in data.get("matches", []):
        mi = m.get("index")
        if not isinstance(mi, int) or not (0 <= mi < len(candidates)):
            continue
        if float(m.get("confidence", 0)) < 0.6:
            continue
        c = candidates[mi]
        if c["store_brand"] in seen_chains:
            continue
        seen_chains.add(c["store_brand"])
        row = _row(c)
        row["reasoning"] = "cross-chain same-product match (LLM-verified)"
        verified.append(row)

    verified.sort(key=lambda r: r["price"])
    return {"matches": verified[:5]}


@app.post("/search")


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
                "imageUrl": fs_image_url(r["sku"]),
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

# ---------------------------------------------------------------- receipt

@app.post("/receipt/scan")
async def receipt_scan(request: Request):
    """Photograph a grocery receipt -> extracted items priced at their cheapest
    scouted store, with per-item + total savings estimate.

    Accepts multipart/form-data 'file' (preferred). Images are processed in
    memory and never written to disk.
    """
    import receipt_scan
    ctype = request.headers.get("content-type", "")
    if not ctype.startswith("multipart/form-data"):
        raise HTTPException(422, "send multipart/form-data with a 'file' field "
                                 "(or use /receipt/scan_json)")
    form = await request.form()
    upload = form.get("file")
    if upload is None:
        raise HTTPException(422, "multipart field 'file' missing")
    image_bytes = await upload.read()
    if not image_bytes:
        raise HTTPException(422, "empty upload")
    result, status = receipt_scan.scan_receipt(image_bytes, db)
    if status != 200:
        raise HTTPException(status, result.get("error", "receipt scan failed"))
    return result


@app.post("/receipt/scan_json")
def receipt_scan_json(req: ReceiptScanJSON):
    """Base64-JSON twin of /receipt/scan for simple HTTP clients."""
    import receipt_scan, base64 as _b64
    try:
        image_bytes = _b64.b64decode(req.image_b64, validate=True)
    except Exception:  # noqa: BLE001
        raise HTTPException(422, "image_b64 is not valid base64")
    result, status = receipt_scan.scan_receipt(image_bytes, db)
    if status != 200:
        raise HTTPException(status, result.get("error", "receipt scan failed"))
    return result
