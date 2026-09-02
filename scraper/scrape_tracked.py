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
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from price_db import PriceDB
from price_agent import PriceAgent
from store_discovery import slugify

AUCKLAND = (-36.8485, 174.7633)   # default scout origin


def main() -> int:
    db = PriceDB()
    items = db.tracked_items()
    if not items:
        print("No tracked items — add some via /tracked API first.")
        return 0

    # Phase 1: catalog sweep — cheap bare-HTTP category walk fills the DB with
    # ~1,200 current PAK'nSave prices in ~3 min. Runs ONCE DAILY (evening fire
    # only) — PnS's Cloudflare bans aggressive crawlers, and with a 2x-daily
    # cron the morning fire skips the catalog entirely. The cooldown file
    # additionally pauses it if Cloudflare flags us regardless.
    import datetime
    if datetime.datetime.now().hour != 19:
        print("[catalog] skipped (morning fire — catalog runs on the 7pm fire)")
    else:
        try:
            import subprocess
            r = subprocess.run(
                [sys.executable, "catalog_scraper.py", "--pages", "2"],
                cwd=str(Path(__file__).parent), capture_output=True, text=True,
                timeout=900)
            tail = (r.stdout or "").strip().splitlines()
            if tail:
                print(f"[catalog] {tail[-1]}")
        except Exception as ex:  # noqa: BLE001
            print(f"[catalog] failed: {ex}")

    # Phase 1.5: Huckleberry catalog sweep — Shopify JSON API, ~10 requests
    # for ~1,900 products. Runs on EVERY fire (it's that cheap and polite).
    try:
        import huckleberry
        n = huckleberry.catalog_sweep(db)
        print(f"[huckleberry] sweep: {n} prices")
    except Exception as ex:  # noqa: BLE001
        print(f"[huckleberry] sweep failed: {ex}")

    # Phase 1.6: The Warehouse grocery catalog — sitemap walk + JSON-LD
    # product pages (~400 products, ~10 min with polite gaps). Runs on the
    # evening fire only, alongside the PnS catalog gate.
    if datetime.datetime.now().hour == 19:
        try:
            import subprocess
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

    stats = db.stats()
    print(
        f"\nDone in {time.time() - t0:.0f}s · {total_prices} prices · "
        f"DB: {stats['prices']} current / {stats['price_history']} history points"
        + (f" · failed: {', '.join(failures)}" if failures else "")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())