#!/usr/bin/env python3
"""Scheduled scrape runner for the tracked grocery list.

Fired by a Hermes cron every 2 hours. For each active tracked item it runs
the browser agent (force refresh — bypass the cache since this IS the cache
filler), appends dated rows to price_history, and prints a compact digest
that the cron delivers to Telegram.

Design notes:
- One run = all tracked items, sequential (Playwright browsers are heavy;
  parallel would risk bot-detection and CPU spikes on the laptop).
- Each item is wrapped in try/except so one blocked site or LLM hiccup
  doesn't kill the whole run.
- Output is deliberately terse: cheapest price per item per chain.
"""
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from price_db import PriceDB
from price_agent import PriceAgent
from store_discovery import slugify

AUCKLAND = (-36.8485, 174.7633)   # default scout origin

# Discovery pool for list growth — 3 fresh picks per run. NZ supermarket
# staples only; anything already tracked (active OR soft-deleted — removed
# means removed) is skipped. Pool is bigger than a year of runs.
DISCOVERY_POOL = [
    "coffee", "orange juice", "apples", "yogurt", "beef mince", "sausages",
    "canned tuna", "honey", "peanut butter", "jam", "marmite", "muesli",
    "oats", "weet-bix", "flour", "sugar", "cooking oil", "canned tomatoes",
    "baked beans", "potatoes", "onions", "carrots", "broccoli", "lettuce",
    "avocado", "strawberries", "oranges", "mushrooms", "capsicum", "cucumber",
    "cream", "ice cream", "dark chocolate", "crackers", "potato chips",
    "almonds", "2-minute noodles", "soy sauce", "tomato sauce", "mayonnaise",
    "toilet paper", "dishwashing liquid", "laundry detergent", "toothpaste",
    "tinned spaghetti", "popcorn", "cornflakes", "couscous",
    "lentils", "chickpeas", "coconut milk", "olive oil", "salmon",
    "ham", "bacon", "kiwifruit", "mandarins", "pumpkin", "kumara",
    "zucchini", "cream cheese", "sour cream", "sparkling water",
]


def discover_new_items(db: PriceDB, count: int = 3) -> list[str]:
    """Pick `count` fresh items from the pool, excluding every query that has
    EVER been tracked (active or soft-deleted) so removed items stay removed."""
    with db.tx() as cur:
        cur.execute("SELECT query FROM tracked_items")
        known = {r["query"] for r in cur.fetchall()}
    fresh = [q for q in DISCOVERY_POOL if q not in known]
    if not fresh:
        return []
    return random.sample(fresh, min(count, len(fresh)))


def main() -> int:
    db = PriceDB()
    items = db.tracked_items()

    # Discovery: grow the list by 3 fresh items per run before scraping, so
    # the new picks get their first prices in the same run that adds them.
    new_picks = discover_new_items(db, count=3)
    for q in new_picks:
        db.add_tracked(q)
    if new_picks:
        items = items + new_picks
        print(f"🌱 New this run: {', '.join(new_picks)}\n")

    if not items:
        print("No tracked items — add some via /tracked API first.")
        return 0

    # Phase 1: catalog sweep — cheap bare-HTTP category walk fills the DB with
    # ~1,200 current PAK'nSave prices in ~3 min. Cooldown-gated to at most
    # once every 3 days (matches the API's freshness window): PnS's Cloudflare
    # bans aggressive crawlers, so we stay polite. A cooldown file also pauses
    # it entirely if Cloudflare flags us.
    import datetime, subprocess
    _CAT_COOLDOWN_S = 3 * 24 * 3600
    _cat_stamp = Path(__file__).parent / ".catalog_last_run"
    _cat_age_ok = (
        not _cat_stamp.exists()
        or (time.time() - _cat_stamp.stat().st_mtime) >= _CAT_COOLDOWN_S
    )
    if not _cat_age_ok:
        print("[catalog] skipped (ran within the last 3 days)")
    else:
        try:
            r = subprocess.run(
                [sys.executable, "catalog_scraper.py", "--pages", "2"],
                cwd=str(Path(__file__).parent), capture_output=True, text=True,
                timeout=900)
            tail = (r.stdout or "").strip().splitlines()
            if tail:
                print(f"[catalog] {tail[-1]}")
            _cat_stamp.touch()
        except Exception as ex:  # noqa: BLE001
            print(f"[catalog] failed: {ex}")

    # Phase 1.6: The Warehouse grocery catalog — sitemap walk + JSON-LD
    # product pages (~400 products, ~10 min with polite gaps). Same 3-day
    # cooldown gate as the PnS catalog so it actually runs under the
    # Mon/Thu 7am cron instead of silently never firing.
    if _cat_age_ok:
        try:
            r = subprocess.run(
                [sys.executable, "tw_catalog.py", "--pages", "10", "--limit", "400"],
                cwd=str(Path(__file__).parent), capture_output=True, text=True,
                timeout=1800)
            tail = (r.stdout or "").strip().splitlines()
            if tail:
                print(f"[tw-catalog] {tail[-1]}")
        except Exception as ex:  # noqa: BLE001
            print(f"[tw-catalog] failed: {ex}")

    # Phase 2: tracked staples via browser agent (New World; PAK'nSave is
    # covered by the catalog sweep in phase 1 — the nearest physical PnS is
    # ~10km from the origin, so agent.run's distance gate would skip it and
    # duplicate what phase 1 already did).
    agent = PriceAgent(db)
    lat, lng = AUCKLAND
    # NW-only filter: restrict brands_to_scrape to New World via a monkeypatch
    # of SUPPORTED_BRANDS so agent.run doesn't waste 30s/item discovering that
    # PnS has no store nearby.
    import store_discovery as _sd
    _nw_only = [b for b in _sd.SUPPORTED_BRANDS if b != "Pak'nSave"]
    _sd.SUPPORTED_BRANDS = _nw_only
    print(f"🛒 Grocery scout run — {len(items)} items: {', '.join(items)}\n")

    total_prices = 0
    failures = []
    t0 = time.time()
    for q in items:
        try:
            t_item = time.time()
            result = agent.run(q, lat, lng, "NZ", radius_m=6000)
            n = len(result["results"])
            total_prices += n
            dur = time.time() - t_item
            if n:
                cheapest = result["results"][0]
                print(
                    f"✓ {q}: {n} prices in {dur:.0f}s — cheapest "
                    f"{cheapest['store']} ${cheapest['price']:.2f}"
                )
            else:
                print(f"○ {q}: no results in {dur:.0f}s")
        except Exception as ex:  # noqa: BLE001 — keep the run alive
            failures.append(q)
            print(f"✗ {q}: {type(ex).__name__}: {str(ex)[:90]}")
        time.sleep(5)  # be polite between site hits

    # Phase 3: chains behind bot walls, scraped via their android apps on the
    # phone (adb). Woolworths site = Akamai, Pak'nSave site = Cloudflare; the
    # apps on a trusted device are the only way in. Phone must be plugged in
    # + unlocked. Each shelf walk takes ~1-3 min — keep query maps small.
    #
    # Walks EVERY tracked staple that has a mapped category path, not just
    # tea: Woolworths has no other price source, so a staple missing from
    # APPS["woolworths"]["query_categories"] ages out of the freshness
    # window within days. Paths are deduped across queries (butter and eggs
    # share the "Eggs, Butter & Spreads" shelf — walk it once, not twice).
    try:
        from app_scraper import APPS, scrape_category, ingest_tiles
        for app_name in ("woolworths", "paknsave"):
            mapped = APPS[app_name]["query_categories"]
            paths: list[list[str]] = []
            for q in items:
                for p in mapped.get(q, []):
                    if p not in paths:
                        paths.append(p)
            if not paths:
                print(f"\n📱 {app_name} (app): no mapped shelves, skipped")
                continue
            print(f"\n📱 {app_name} (app): walking {len(paths)} shelves")
            for path in paths:
                label = " > ".join(path)
                try:
                    tiles = scrape_category(path, app_name)
                    n = ingest_tiles(db, tiles, path[-1], app_name)
                    print(f"  ✓ {label}: {len(tiles)} tiles, {n} ingested")
                except Exception as ex:  # noqa: BLE001 — one dead shelf ≠ dead run
                    print(f"  ✗ {label}: {type(ex).__name__}: {str(ex)[:90]}")
    except Exception as ex:  # noqa: BLE001 — missing phone must not kill the run
        print(f"✗ app scrapers: {type(ex).__name__}: {str(ex)[:90]}")

    stats = db.stats()
    print(
        f"\nDone in {time.time() - t0:.0f}s · {total_prices} prices · "
        f"DB: {stats['prices']} current / {stats['price_history']} history points"
        + (f" · failed: {', '.join(failures)}" if failures else "")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())