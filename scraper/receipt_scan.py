"""Receipt-to-savings: OCR a grocery receipt, structure its items, price each
item against the scouted chains, and estimate savings at the cheapest store.

Pipeline (all on the scraper host):
  1. preprocess   — downscale, re-encode JPEG (phone photos are huge)
  2. OCR+structure— gemma3:latest (VISION_MODEL) reads the receipt and returns
                    strict JSON {store, items[{name, qty, line_total}], total}
                    with a resilience ladder (gemma3 x2 -> gemma4:e4b)
  3. price items  — word-wise DB lookup first (instant), LLM matcher for the
                    misses; cheapest per chain; savings = max(0, receipt - cheapest)
  4. totals       — receipt_total, subtotal_matched, estimated_savings

Hard rule: NEVER invent items. If the model doesn't see a receipt, we say so.
"""
from __future__ import annotations

import base64
import io
import json
import re
import time
import urllib.request
from pathlib import Path
from typing import Optional

from price_db import PriceDB
from price_agent import _ollama_chat, _strip_to_json
from store_discovery import slugify

OLLAMA_GENERATE = "http://192.168.1.72:11434/api/generate"
# Benchmarked on real receipts (PAK'nSAVE Dunedin, Sep 2026):
#   gemma4:e4b: 13-14s, 6/8 prices correct, total extracted & sum matches
#   gemma3:latest: 28-29s, 4/8 correct, hallucinated a price, no total
# gemma4:e4b wins both speed and accuracy (MoE: only ~3.1GB VRAM resident).
OCR_MODEL = "gemma4:e4b"
OCR_FALLBACK_MODEL = "gemma3:latest"
OCR_TIMEOUT_S = 120

MAX_IMAGE_BYTES = 6 * 1024 * 1024
MAX_LONG_EDGE = 1600

# Chains we scout (canonical brand names as they appear in stores.brand)
SCOUTED_BRANDS = {
    "new world": "New World",
    "pak'nsave": "Pak'nSave",
    "pak'ns save": "Pak'nSave",
    "pak'nsave": "Pak'nSave",
    "pak n save": "Pak'nSave",
    "pak ns save": "Pak'nSave",
    "paknsave": "Pak'nSave",
    "pns": "Pak'nSave",
    "new world": "New World",
    "the warehouse": "The Warehouse",
    "warehouse": "The Warehouse",
}
# NZ chains we recognise but don't scout (yet)
UNSCOUTED_BRANDS = {
    "countdown": "Countdown",
    "woolworths": "Woolworths (Countdown)",
    "woolworths nz": "Woolworths (Countdown)",
    "four square": "Four Square",
    "foursquare": "Four Square",
    "supervalue": "SuperValue",
    "fresh choice": "FreshChoice",
    "freshchoice": "FreshChoice",
    "new world metro": "New World",     # metro IS New World
    "pak'nsave metro": "Pak'nSave",
}

STRUCTURE_PROMPT = """You read New Zealand supermarket receipts. Transcribe the purchases.

Return STRICT JSON only, no prose:
{"is_receipt": true,
 "store": "<store name printed on the receipt header>",
 "items": [{"name": "<product name as printed>", "qty": <number of units, default 1>, "line_total": <amount charged for the line in NZD dollars, e.g. 4.59>}],
 "subtotal": <number or null>,
 "total": <number or null>}

Rules:
- "items" contains PURCHASED PRODUCTS ONLY.
- EXCLUDE: subtotal, total, EFTPOS/cash/change lines, GST lines, loyalty/points/
  clubcard lines, surcharges, thank-you lines, promotions text, dates, addresses.
- Weighted items (per kg produce): qty 1 and line_total = the price printed for it.
- If the same product is scanned twice as separate lines, keep both lines.
- If the photo is NOT a receipt, return exactly {"is_receipt": false, "items": []}.
- NEVER invent items. Transcribe only what is printed. If a price is unreadable,
  omit that item rather than guessing."""

STRUCTURE_NUDGE = "Your previous reply was invalid. Respond again with STRICT JSON only. If it is not a receipt say so via is_receipt=false. Never invent items."


# ----------------------------------------------------------------- images

def preprocess_image(image_bytes: bytes) -> tuple[bytes, str]:
    """Downscale + re-encode for the VLM. Returns (jpeg_bytes, error_or_'')."""
    if len(image_bytes) > MAX_IMAGE_BYTES:
        return b"", f"image too large ({len(image_bytes)/1e6:.1f}MB > 6MB)"
    try:
        from PIL import Image
    except ImportError:            # Pillow missing: pass bytes through untouched
        return image_bytes, ""
    try:
        img = Image.open(io.BytesIO(image_bytes))
        img = img.convert("RGB")
        w, h = img.size
        long_edge = max(w, h)
        if long_edge > MAX_LONG_EDGE:
            scale = MAX_LONG_EDGE / long_edge
            img = img.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=82)
        return buf.getvalue(), ""
    except Exception as ex:         # noqa: BLE001 — corrupt/unknown image
        return b"", f"unreadable image: {ex}"


# ------------------------------------------------------------------ ollama

def _generate_json(model: str, prompt: str, image_b64: str, timeout_s: int) -> dict:
    payload = {
        "model": model,
        "prompt": prompt,
        "images": [image_b64],
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.1, "num_ctx": 8192},
    }
    req = urllib.request.Request(
        OLLAMA_GENERATE, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout_s) as r:
        body = json.loads(r.read().decode())
    raw = body.get("response", "").strip()
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        raise ValueError(f"no JSON in {model} reply")
    return json.loads(raw[start:end + 1])


def extract_receipt(image_b64: str) -> dict:
    """OCR + structure a receipt image. Raises ValueError when every attempt
    fails; returns {'is_receipt': False} when the model sees no receipt.

    Dual-read reconciliation: vision models hallucinate occasional prices
    ($5.99 -> $85.99) inconsistently run-to-run, so we read the receipt TWICE
    with the primary model and keep the majority price per item. When a
    printed total exists and disagrees with the sum, the re-read prompt
    points out the mismatch explicitly.
    """
    def _parse(data: dict) -> tuple[list[dict], Optional[float], Optional[float], Optional[str]]:
        items = []
        for it in data.get("items") or []:
            try:
                name = str(it.get("name", "")).strip()
                qty = float(it.get("qty") or 1)
                line_total = float(it.get("line_total") or 0)
            except (TypeError, ValueError):
                continue
            if not name or line_total <= 0:
                continue                  # priceless line = useless line
            items.append({"name": name[:80], "qty": qty,
                          "line_total": round(line_total, 2)})
        return (items, _num(data.get("subtotal")),
                _num(data.get("total")),
                (data.get("store") or "").strip() or None)

    def _one_read(model: str, prompt: str, timeout_s: int) -> dict:
        data = _generate_json(model, prompt, image_b64, timeout_s)
        if not data.get("is_receipt"):
            return {"is_receipt": False, "store": None, "items": [],
                    "subtotal": None, "total": None}
        items, subtotal, total, store = _parse(data)
        if not items:
            return {"is_receipt": False, "store": None, "items": [],
                    "subtotal": None, "total": None}
        return {"is_receipt": True, "store": store, "items": items,
                "subtotal": subtotal, "total": total}

    def _norm_key(name: str) -> str:
        import re as _re
        words = [w for w in re.split(r"[^a-z0-9]+", name.lower()) if len(w) >= 3]
        return "-".join(words[:4])

    def _reconcile(a: dict, b: dict) -> dict:
        """Majority-vote per item across two readings. Items agreeing keep
        their price; disagreements keep the CHEAPER price (hallucinations
        inflate: $5.99 -> $85.99, never the reverse)."""
        items_b = {_norm_key(i["name"]): i for i in b["items"]}
        items = []
        for it in a["items"]:
            twin = items_b.get(_norm_key(it["name"]))
            if twin and abs(twin["line_total"] - it["line_total"]) > 0.02:
                it = {**it, "line_total": min(it["line_total"],
                                              twin["line_total"])}
            items.append(it)
        # items only in the second reading (first read missed them)
        keys_a = {_norm_key(i["name"]) for i in a["items"]}
        extras = [i for k, i in items_b.items() if k not in keys_a]
        out = {**a, "items": items + extras}
        # prefer a read that produced a total; reconcile totals too
        out["total"] = a.get("total") or b.get("total")
        out["subtotal"] = a.get("subtotal") or b.get("subtotal")
        out["store"] = a.get("store") or b.get("store")
        return out

    attempts = [
        (OCR_MODEL, STRUCTURE_PROMPT, OCR_TIMEOUT_S),
        (OCR_MODEL, STRUCTURE_PROMPT + "\n\n" + STRUCTURE_NUDGE, OCR_TIMEOUT_S),
        (OCR_FALLBACK_MODEL, STRUCTURE_PROMPT, 120),
    ]
    first = second = None
    last_err: Exception | None = None
    for model, prompt, timeout_s in attempts:
        try:
            r = _one_read(model, prompt, timeout_s)
            if not r.get("is_receipt"):
                return r
            if first is None:
                first = r
            elif second is None:
                second = r
                break
        except Exception as ex:     # noqa: BLE001
            last_err = ex
            continue
    if first is None:
        raise ValueError(f"receipt OCR failed on all attempts: {last_err}")
    if second is not None:
        return _reconcile(first, second)
    return first


def _num(v) -> float | None:
    try:
        return round(float(v), 2)
    except (TypeError, ValueError):
        return None


# ----------------------------------------------------------------- store

def canonical_store(raw: str | None) -> dict:
    """Map a receipt store name to {raw, canonical, scouted}. Apostrophes and
    punctuation vary wildly ("PAK'nSAVE" / "PAK'n SAVE" / "PAK N SAVE")."""
    if not raw:
        return {"raw": None, "canonical": None, "scouted": False}
    lc = raw.lower().replace("'", "").replace("\u2019", "").strip()
    lc = " ".join(lc.split())                     # collapse whitespace
    collapsed = lc.replace(" ", "")               # "PAK'n SAVE" -> "paknsave"
    for key, brand in {**UNSCOUTED_BRANDS, **SCOUTED_BRANDS}.items():
        k = key.replace("'", "")
        if k in lc or k in collapsed:
            scouted = brand in SCOUTED_BRANDS.values()
            return {"raw": raw.strip(), "canonical": brand, "scouted": scouted}
    return {"raw": raw.strip(), "canonical": None, "scouted": False}


# ---------------------------------------------------------------- pricing

def _stem(t: str) -> str:
    """Crude singular/possessive normalisation: eggs->egg, beans->bean.
    Keeps grocery matching tolerant to plural drift."""
    if len(t) > 3 and t.endswith("s") and not t.endswith("ss"):
        return t[:-1]
    return t


def _tokenize(s: str) -> set[str]:
    return {_stem(t) for t in re.split(r"[^a-z0-9]+", s.lower()) if len(t) >= 3}


def _product_words(s: str) -> list[str]:
    """Words for DB matching: drop pack-size tokens (400g, 2l, 12pk, 750, x2)
    — pack sizes don't appear in product slugs and poison AND-matching."""
    words = []
    for t in re.split(r"[^a-z0-9]+", s.lower()):
        if len(t) < 3:
            continue
        if re.fullmatch(r"\d+", t):
            continue                              # bare number
        if re.fullmatch(r"\d+(g|kg|ml|l|pk|m)", t):
            continue                              # size token
        words.append(t)
    return words or sorted(_tokenize(s))


def _cheapest_by_chain(rows: list[dict]) -> list[dict]:
    """Min price per chain from fresh_prices_for_query rows."""
    best: dict[str, dict] = {}
    for r in rows:
        brand = r["store_brand"]
        if brand not in best or r["price_cents"] < best[brand]["price_cents"]:
            best[brand] = r
    out = []
    for brand, r in sorted(best.items(), key=lambda kv: kv[1]["price_cents"]):
        out.append({
            "storeChain": brand,
            "store": r.get("store_name") or brand,
            "price": r["price_cents"] / 100,
            "productName": r["product_name"],
        })
    return out


RECEIPT_MATCH_SYSTEM = """You verify whether a RECEIPT LINE ITEM is the same product as one of the CANDIDATE product listings from NZ supermarkets.
Receipt lines are terse ("Watties Spaghetti 420g"); product names are longer ("Wattie's Spaghetti In Tomato Sauce 420g").

Rules:
- Respond STRICT JSON only: {"matches": [{"sku": str, "confidence": 0.0-1.0, "canonical_name": str, "reasoning": str}]}
- SAME product = same brand AND same category AND (same or unspecified) pack size.
- A DIFFERENT product from the same brand is NOT a match (Watties Spaghetti != Watties Baked Beans).
- Different pack size where both sizes are known = NOT a match.
- If nothing is the same product, return {"matches": []}.
- Only include matches with confidence >= 0.75. Max 3 matches, best first.
- canonical_name: copy the candidate's product name."""

RECEIPT_MATCH_FALLBACK_SYSTEM = RECEIPT_MATCH_SYSTEM  # same rules on fallback


def _llm_verify_item(name: str, tiles: list) -> list:
    """Receipt-strict LLM matching ladder: gemma4 -> qwen3.5:2b. Returns
    LLMChoice-like objects (sku = pool index, confidence, canonical_name)."""
    from price_agent import (MATCH_MODEL, FALLBACK_MODEL, FALLBACK_TIMEOUT_S,
                             LLMChoice)
    tile_payload = [{"sku": t.sku, "name": t.title or t.slug, "per_unit": t.per_unit}
                    for t in tiles][:40]
    user = f'Receipt line: "{name}"\n\nCandidate products:\n{json.dumps(tile_payload)}'
    attempts = [
        (MATCH_MODEL, RECEIPT_MATCH_SYSTEM, {}, 60),
        (FALLBACK_MODEL, RECEIPT_MATCH_FALLBACK_SYSTEM, {"think": False},
         FALLBACK_TIMEOUT_S),
    ]
    for model, system, extra, timeout_s in attempts:
        payload = {"model": model, "stream": False, "format": "json",
                   "options": {"temperature": 0.1, "num_ctx": 8192},
                   "messages": [
                       {"role": "system", "content": system},
                       {"role": "user", "content": user},
                   ], **extra}
        try:
            body = _ollama_chat(payload, timeout_s)
            data = json.loads(_strip_to_json(body["message"]["content"]))
            out = []
            for m in data.get("matches", []):
                try:
                    conf = float(m.get("confidence", 0))
                except (TypeError, ValueError):
                    continue
                if conf < 0.75:
                    continue
                out.append(LLMChoice(
                    sku=str(m.get("sku", "")), match_confidence=conf,
                    canonical_name=str(m.get("canonical_name", "")),
                    unit=None, reasoning=str(m.get("reasoning", ""))))
            if out:
                return out
        except Exception as ex:  # noqa: BLE001 — try next model in the ladder
            print(f"[receipt] verify {name!r} via {model}: {type(ex).__name__}: {str(ex)[:80]}")
            continue
    return []


def price_item(item: dict, db: PriceDB, max_age_s: float = 3 * 24 * 3600) -> dict:
    """Price one receipt item: exact word-match lookup first, LLM verify on
    the OR-candidate pool as a rescue. Returns a result row (never raises)."""
    from price_agent import Tile
    name = item["name"]
    line_total = item["line_total"]
    t0 = time.time()

    qty = item.get("qty") or 1
    words = _product_words(name)
    if words:
        rows = db.fresh_prices_for_query("-".join(words), max_age_s)
    else:
        rows = db.fresh_prices_for_query(slugify(name), max_age_s)
    if rows:
        chains = _cheapest_by_chain(rows)
        if chains:
            cheapest = chains[0]
            # guard: word-match can drift (e.g. "fresh milk" hitting
            # "fresh cream milk drink"). Compare SIZE-STRIPPED, apostrophe-
            # normalised tokens with containment: receipt lines are terse
            # ("Watties Spaghetti 420g"), product names carry extra
            # descriptors ("Watties Spaghetti In Tomato Sauce 420g"), so the
            # item's words should mostly be IN the product's words.
            norm = lambda s: {t.replace("'", "") for t in _tokenize(s)
                              if not re.fullmatch(r"\d+(g|kg|ml|l|pk|m)?", t)}
            item_sizes = {t for t in re.split(r"[^a-z0-9]+", name.lower())
                          if re.fullmatch(r"\d+(g|kg|ml|l|pk|m)", t)}
            cand_sizes = {t for t in re.split(r"[^a-z0-9]+", cheapest["productName"].lower())
                          if re.fullmatch(r"\d+(g|kg|ml|l|pk|m)", t)}
            size_conflict = bool(item_sizes and cand_sizes and not (item_sizes & cand_sizes))
            itoks, ptoks = norm(name), norm(cheapest["productName"])
            # Extra candidate words are fine when they're descriptors
            # ("standard", "breakfast cereal", "in tomato sauce") but change
            # the product when they're category words ("snack bars", "drink").
            DESCRIPTOR_OK = {
                "standard", "fresh", "whole", "blue", "green", "gold",
                "light", "lite", "original", "classic", "breakfast",
                "cereal", "sauce", "tomato", "juice", "spray", "free",
                "range", "size", "big", "small", "new", "improved",
            }
            extra_words = ptoks - itoks
            ambiguous = any(w not in DESCRIPTOR_OK for w in extra_words)
            if ((not size_conflict) and (not ambiguous) and itoks and ptoks
                    and (len(itoks & ptoks) / len(itoks)) >= 0.6):
                savings = max(0.0, round(line_total - cheapest["price"] * qty, 2))
                return {**item, "matchStatus": "exact", "confidence": 1.0,
                        "match": cheapest, "alternatives": chains[1:4],
                        "savings": savings}
            # Ambiguous word-overlap hit (e.g. "Milo" cereal vs Milo snack
            # bars): fall through to the LLM verifier with the same pool —
            # never silently accept a guess the tokens can't make.

    # LLM stage: build a candidate pool with a looser OR query
    toks = sorted(set(_product_words(name)))
    if toks:
        like = " OR ".join("p.product_slug LIKE '%' || ? || '%'" for _ in toks)
        try:
            with db.tx() as cur:
                cur.execute(
                    f"""SELECT p.product_name, p.product_slug, MIN(p.price_cents)
                               AS price_cents, s.brand AS store_brand,
                               s.name AS store_name
                        FROM prices p JOIN stores s ON s.id = p.store_id
                        WHERE ({like})
                        GROUP BY p.product_name, s.brand
                        ORDER BY MIN(p.price_cents) LIMIT 200""",
                    tuple(toks))
                pool = [dict(r) for r in cur.fetchall()]
        except Exception:       # noqa: BLE001
            pool = []
        if pool:
            # Tile selection matters: a price-ordered pool for "egg classic"
            # is 60 rows of egg whisks and noodle junk, and the verifier
            # never sees the real eggs. Put products containing ALL receipt
            # content words first (AND-match), then pad with the rest.
            STOP = {"classic", "fresh", "value", "everyday"}
            content_words = {w for w in toks if w not in STOP} or set(toks)
            def _name_toks(r):
                return {t.replace("'", "") for t in _tokenize(r["product_name"])
                        if not re.fullmatch(r"\d+(g|kg|ml|l|pk|m)?", t)}
            full = [r for r in pool if content_words <= _name_toks(r)]
            rest = [r for r in pool if r not in full]
            pool = (full + rest)[:40]
        if pool:
            tiles = [Tile(sku=str(i), slug=r["product_slug"], title=r["product_name"],
                          per_unit="", price=f"${r['price_cents']/100:.2f}",
                          currency="NZD", href="")
                     for i, r in enumerate(pool)]
            try:
                choices = _llm_verify_item(name, tiles)
                if choices:
                    # LLMChoice.sku is the tile's sku == pool index (we built
                    # tiles with sku=str(index)).
                    prows = []
                    line_toks = {t.replace("'", "") for t in _tokenize(name)
                                 if not re.fullmatch(r"\d+(g|kg|ml|l|pk|m)?", t)}
                    for ch in choices:
                        try:
                            cand = pool[int(ch.sku)]
                        except (ValueError, IndexError):
                            continue
                        # Token sanity: the receipt's words must actually be
                        # representable in the candidate. The LLM sometimes
                        # blesses same-BRAND different-PRODUCT picks with
                        # high confidence (observed: eggs -> hash browns).
                        cand_toks = {t.replace("'", "") for t in
                                     _tokenize(cand["product_name"])
                                     if not re.fullmatch(r"\d+(g|kg|ml|l|pk|m)?", t)}
                        # Judge overlap on CONTENT words only — generic words
                        # like 'classic'/'fresh' appear across unrelated
                        # categories and inflate weak matches.
                        CONTENT_STOP = {"classic", "fresh", "value", "everyday",
                                        "selected", "quality", "premium"}
                        line_content = line_toks - CONTENT_STOP
                        cand_content = cand_toks - CONTENT_STOP
                        overlap = len(line_content & cand_content) / max(1, len(line_content))
                        if overlap < 0.5:
                            print(f"[receipt] LLM pick rejected: {name!r} -> "
                                  f"{cand['product_name']!r} overlap "
                                  f"{overlap:.2f} (conf {ch.match_confidence})")
                            continue
                        c_sizes = {t for t in re.split(r"[^a-z0-9]+",
                                  cand["product_name"].lower())
                                  if re.fullmatch(r"\d+(g|kg|ml|l|pk|m)", t)}
                        i_sizes = {t for t in re.split(r"[^a-z0-9]+", name.lower())
                                   if re.fullmatch(r"\d+(g|kg|ml|l|pk|m)", t)}
                        if i_sizes and c_sizes and not (i_sizes & c_sizes):
                            continue    # wrong pack size — try next choice
                        prows = [cand]
                        break
                    if prows:
                        target_name = prows[0]["product_name"]
                        same = db.fresh_prices_for_query(
                            slugify(target_name), max_age_s)
                        chains = _cheapest_by_chain(same) if same else []
                        if chains:
                            cheapest = chains[0]
                            savings = max(0.0, round(line_total - cheapest["price"] * qty, 2))
                            return {**item, "matchStatus": "llm",
                                    "confidence": choices[0].match_confidence,
                                    "match": cheapest,
                                    "alternatives": chains[1:4],
                                    "savings": savings}
            except Exception as ex:  # noqa: BLE001 — LLM stage is best-effort
                print(f"[receipt] LLM stage failed for {name!r}: "
                      f"{type(ex).__name__}: {str(ex)[:90]}")

    return {**item, "matchStatus": "none", "confidence": 0.0, "match": None,
            "alternatives": [], "savings": 0.0,
            "elapsed_s": round(time.time() - t0, 1)}


# ------------------------------------------------------------ orchestrator

def scan_receipt(image_bytes: bytes, db: PriceDB) -> tuple[dict, int]:
    """Full pipeline. Returns (response_dict, http_status)."""
    t0 = time.time()
    jpeg, err = preprocess_image(image_bytes)
    if err:
        return {"error": err}, 422
    if not jpeg:
        return {"error": "empty image"}, 422

    try:
        receipt = extract_receipt(base64.b64encode(jpeg).decode())
    except ValueError as ex:
        return {"error": str(ex)}, 502
    if not receipt.get("is_receipt"):
        return {"error": "no receipt detected in photo"}, 422

    priced = [price_item(it, db) for it in receipt["items"]]
    subtotal_matched = round(sum(it["line_total"] for it in priced
                                 if it["matchStatus"] != "none"), 2)
    estimated_savings = round(sum(it["savings"] for it in priced), 2)

    resp = {
        "store": canonical_store(receipt.get("store")),
        "items": priced,
        "receiptTotal": _num(receipt.get("total")),
        "subtotal": _num(receipt.get("subtotal")),
        "subtotalMatched": subtotal_matched,
        "estimatedSavings": estimated_savings,
        "itemsCount": len(priced),
        "itemsPriced": sum(1 for it in priced if it["matchStatus"] != "none"),
        "processingMs": int((time.time() - t0) * 1000),
    }
    return resp, 200