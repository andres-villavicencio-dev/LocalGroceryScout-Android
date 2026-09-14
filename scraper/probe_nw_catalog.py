#!/usr/bin/env python3
"""Probe New World's category URL structure through Playwright.

NW is a Foodstuffs Next.js site like PnS (which uses /shop/category/{cat}?pg={n}).
Cloudflare blocks bare HTTP, so probe via headless Chromium.
"""
import json
import re
import sys

from playwright.sync_api import sync_playwright

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/151.0.0.0 Safari/537.36")

PROBES = [
    "https://www.newworld.co.nz/shop/category/pantry?pg=1",
    "https://www.newworld.co.nz/shop/browse/pantry",
    "https://www.newworld.co.nz/shop/search?q=milk&pg=1",
]

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, args=[
        "--no-sandbox", "--disable-blink-features=AutomationControlled"])
    ctx = browser.new_context(viewport={"width": 1366, "height": 900},
                              user_agent=UA, locale="en-NZ")
    ctx.add_init_script("Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")
    page = ctx.new_page()
    for url in PROBES:
        try:
            r = page.goto(url, timeout=45000, wait_until="domcontentloaded")
            page.wait_for_timeout(4000)
            status = r.status if r else "?"
            title = page.title()[:60]
            n_cards = page.evaluate(
                "document.querySelectorAll('[data-testid^=\"product-\"]').length")
            next_data = page.evaluate(
                "(() => { const el = document.getElementById('__NEXT_DATA__');"
                " return el ? el.textContent.length : 0; })()")
            print(f"{status} | cards={n_cards} | NEXT_DATA={next_data} | {title} | {url}")
        except Exception as ex:  # noqa: BLE001
            print(f"FAIL | {type(ex).__name__}: {str(ex)[:70]} | {url}")
    browser.close()