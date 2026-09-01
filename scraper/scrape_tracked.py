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

    agent = PriceAgent(db)
    lat, lng = AUCKLAND
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