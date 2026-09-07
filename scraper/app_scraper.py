"""Woolworths NZ price scraping via the Countdown Android app on the phone.

Why this exists: woolworths.co.nz sits behind Akamai bot detection and
paknsave.co.nz behind Cloudflare — plain HTTP and Playwright both get 403s
from this host. The Countdown android app (nz.co.countdown.android.pickup),
however, talks to the backend from a trusted device session. So we drive the
app on the Note 8 over adb and read products out of the accessibility tree.

How it works (recon'd 2026-09-07):
  - Search PLP is half-broken (renders only the promoted tile) — but full
    category browse works: Products tab -> category -> subcategory -> PLP
    with complete tiles (141 products on "Black & Breakfast Tea").
  - Tile text arrives as an ordered stream: NAME, "$", dollars, cents,
    "$X.XX per UNIT", badges (SAVE $x / Was $y / LOW PRICE), "LISTS", "ADD".
    We run a state machine over the ordered texts and split tiles on LISTS/ADD.
  - Scrolling: input swipe 540,1700 -> 540,600 loads the next batch; end when
    two consecutive dumps parse identically.

Ingest: same PriceDB.upsert_price path as the other chains, attributed to a
synthetic "Woolworths (app)" store row so app-sourced prices are traceable.
Shelf prices are recorded (member prices ignored) for cross-chain
comparability — the phone owner's Everyday Rewards session renders them but
they're not comparable to New World / Pak'nSave shelf prices.

Caveat: Woolworths is sunsetting this app ("DISCOVER THE NEW APP" banner).
When it dies, re-point this at the replacement app's UI — the approach
(adb + accessibility tree) transfers, the selectors won't.

Usage:
  python3 ww_app_scraper.py --query tea          # tracked-query category paths
  python3 ww_app_scraper.py --cat Drinks "Tea & Milk Drinks" "Black & Breakfast Tea"
  python3 ww_app_scraper.py --list-queries
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from html import unescape
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).parent))
from price_db import Price, PriceDB, Store  # noqa: E402

ADB = str(Path.home() / "Android/Sdk/platform-tools/adb")

# ---------------------------------------------------------------------------
# App registry — each grocery chain's app + its fulfillment store + taxonomy.
# The Countdown app renders Woolworths NZ prices (post-rebrand packaging);
# nz.co.countdown.android.pickup is kept by Woolworths as the legacy client.
# ---------------------------------------------------------------------------

APPS: dict[str, dict] = {
    "woolworths": {
        "package": "nz.co.countdown.android.pickup",
        "activity": "au.com.woolworths.android.onesite.modules.main.MainActivity",
        "store": Store(
            osm_id="ww-app-nz-barr-street",
            name="Woolworths 18 Barr Street (app)",
            brand="Woolworths",
            lat=-45.8642,
            lng=170.4536,
            address="18 Barr Street, Kenmure, Dunedin 9011",
            distance_km=1.2,
        ),
        # Tracked query -> category drill paths (top category -> shelf PLP).
        # Mirrors the app's Products taxonomy (recon'd from the tree).
        "query_categories": {
            "tea": [
                ["Drinks", "Tea & Milk Drinks", "Black & Breakfast Tea"],
                ["Drinks", "Tea & Milk Drinks", "Green Tea"],
                ["Drinks", "Tea & Milk Drinks", "Herbal & Fruit Teas"],
            ],
        },
    },
    "paknsave": {
        "package": "nz.co.paknsave.app",   # Foodstuffs (N.Z.) Ltd, Play Store
        "activity": "nz.co.paknsave.app.MainActivity",
        "store": Store(
            osm_id="pns-app-nz-dunedin",
            name="PAK'nSAVE Dunedin (app)",
            brand="Pak'nSave",
            lat=-45.8880,
            lng=170.5011,
            address="86 Hillside Road, South Dunedin, Dunedin 9012",
            distance_km=1.5,
        ),
        # Tile prices live in content-desc: "NAME, SIZE, DOLLARS, CENTS,
        # PRICE-UNIT, $X.XX/100" — more structured than Countdown's stream.
        "query_categories": {
            "tea": [
                ["HOT & COLD DRINKS", "TEA"],                      # all tea
                ["HOT & COLD DRINKS", "TEA", "BLACK TEA"],
                ["HOT & COLD DRINKS", "TEA", "GREEN TEA"],
                ["HOT & COLD DRINKS", "TEA", "FRUIT, HERBAL & FLAVOURED TEA"],
            ],
        },
    },
}

# ---------------------------------------------------------------------------
# adb helpers
# ---------------------------------------------------------------------------

def adb(*args: str, timeout: int = 30) -> str:
    out = subprocess.run([ADB, *args], capture_output=True, text=True,
                         timeout=timeout)
    return out.stdout


def ui_dump(attempt: int = 3) -> str:
    """Accessibility-tree XML of the current screen (with retries — the
    uiautomator dumper occasionally loses the idle race on busy screens)."""
    for i in range(attempt):
        adb("shell", "uiautomator", "dump", "/sdcard/lgs_dump.xml")
        xml = adb("shell", "cat", "/sdcard/lgs_dump.xml")
        if "<node" in xml and len(xml) > 500:
            return xml
        time.sleep(1.5)
    raise RuntimeError("uiautomator dump failed 3x")


def screen_texts(xml: str) -> list[str]:
    """Ordered non-empty text nodes from a dump (the tile stream)."""
    texts = re.findall(r'text="([^"]*)"', xml)
    return [unescape(t).strip() for t in texts if t and t.strip()]


def screen_descs(xml: str) -> list[str]:
    """Ordered non-empty content-desc values from a dump. PnS tiles keep the
    price in the desc, not in text nodes."""
    descs = re.findall(r'content-desc="([^"]*)"', xml)
    return [unescape(d).strip() for d in descs if d and d.strip()]


_PNS_TILE = re.compile(
    r'^(?P<name>.+),\s*(?P<size>[^,]+),\s*(?P<dollars>\d{1,3}),\s*'
    r'(?P<cents>\d{2}),\s*(?P<punit>[a-z]+),\s*\$(?P<pp>\d+\.\d{2})/(?P<pdiv>[a-z0-9]+)\.?$'
)


def parse_pns_tiles(descs: list[str]) -> list[Tile]:
    """PAK'nSAVE tile parser: product tiles are content-desc strings shaped
    'NAME, SIZE, DOLLARS, CENTS, PRICE-UNIT, $PP/PDIV'. Chrome descs (tabs,
    cart, banners) don't match the pattern and are skipped."""
    tiles = []
    for d in descs:
        m = _PNS_TILE.match(d)
        if not m:
            continue
        name = m.group("name").strip()
        cents = int(m.group("dollars")) * 100 + int(m.group("cents"))
        size = m.group("size").strip()
        tiles.append(Tile(name=name, price_cents=cents,
                          unit=_unit_from_name(f"{name} {size}"),
                          unit_price="$" + m.group("pp") + "/" + m.group("pdiv"),
                          badges=[]))
    return tiles


def tap(x: int, y: int, settle: float = 2.0) -> None:
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(settle)


def swipe_up(settle: float = 3.0) -> None:
    adb("shell", "input", "swipe", "540", "1700", "540", "600", "400")
    time.sleep(settle)


def tap_text(label: str, xml: str, settle: float = 3.0) -> bool:
    """Tap the first node whose text equals label; falls back to contains.
    Exact matches win because document order puts banners before nav tabs."""
    nodes = [(unescape(m.group(1)).strip(),
              (int(m.group(2)) + int(m.group(4))) // 2,
              (int(m.group(3)) + int(m.group(5))) // 2)
             for m in re.finditer(
                 r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)]
    for t, x, y in nodes:
        if t == label:
            tap(x, y, settle)
            return True
    for t, x, y in nodes:
        if label.lower() in t.lower() and len(t) < len(label) + 40:
            tap(x, y, settle)
            return True
    return False


# ---------------------------------------------------------------------------
# PLP tile parsing
# ---------------------------------------------------------------------------

_NAME_STOP = {"filter", "specials", "member price", "lists", "add", "low price",
              "special", "low price always", "products"}
_PRICE_BITS = re.compile(r"^\$?\d{1,3}$")
_PER_UNIT = re.compile(r"^\$?(\d+\.\d{2})\s*per\s*(.+)$", re.I)


@dataclass
class Tile:
    name: str
    price_cents: int
    unit: Optional[str] = None        # product-pack unit from the name ("400g")
    unit_price: Optional[str] = None  # "$2.30 per 1KG" verbatim
    badges: list[str] = field(default_factory=list)


def parse_tiles(texts: list[str], title: Optional[str] = None) -> list[Tile]:
    """State machine over the ordered accessibility texts of a PLP screen.

    Stream shape per tile: [Promoted] NAME, "$", dollars, cents,
    "$X.XX per UNIT", [badges], LISTS, ADD. Tiles are split on LISTS/ADD.

    Header handling: on a fresh PLP everything before "N products" (title,
    cart count, filter chips) is noise; on scrolled screens the header
    collapses to title+cart, so strip leading title/digits/chips instead.
    Tiles whose name scrolled under the sticky header appear as a bare
    price — they flush with no name and are dropped (the previous screen's
    dump already captured them; collect_plp dedupes across screens).
    """
    for i, t in enumerate(texts):
        if re.fullmatch(r"\d+ products", t):
            texts = texts[i + 1:]
            break
    else:
        if not title:
            return []   # no results header and no title hint — not a usable PLP
        while texts and (texts[0] == title
                         or re.fullmatch(r"\d{0,3}", texts[0])
                         or texts[0].lower() in _NAME_STOP):
            texts = texts[1:]

    tiles: list[Tile] = []
    name_parts: list[str] = []
    digits: list[str] = []
    badges: list[str] = []
    seen_dollar_sign = False
    last_per_unit: Optional[str] = None

    def flush():
        nonlocal name_parts, digits, badges, seen_dollar_sign, last_per_unit
        if name_parts and digits and seen_dollar_sign:
            name = " ".join(name_parts)
            name = re.sub(r"^Promoted\s+", "", name).strip()
            if name and name.lower().split()[0] not in _NAME_STOP:
                cents = int(digits[0]) * 100 + int(digits[1]) if len(digits) > 1 \
                    else int(digits[0]) * 100
                tiles.append(Tile(name=name, price_cents=cents,
                                  unit=_unit_from_name(name),
                                  unit_price=last_per_unit,
                                  badges=list(badges)))
        name_parts, digits, badges = [], [], []
        seen_dollar_sign = False
        last_per_unit = None

    for t in texts:
        low = t.lower()
        if low in ("lists", "add"):
            flush()
            continue
        if t == "$":
            seen_dollar_sign = True
            continue
        m = _PER_UNIT.match(t)
        if m:
            last_per_unit = t
            continue
        if seen_dollar_sign and _PRICE_BITS.match(t) and len(digits) < 2:
            digits.append(t.lstrip("$"))
            continue
        if low.startswith(("save $", "was $")) or low == "low price":
            badges.append(t)
            continue
        if low in _NAME_STOP:
            continue
        # long-ish text = part of the product name (may wrap across nodes)
        if len(t) >= 4:
            name_parts.append(t)
    flush()
    return tiles


def _unit_from_name(name: str) -> Optional[str]:
    m = re.search(r"(\d+(?:\.\d+)?)\s?(g|kg|ml|l|ea|pk|pack)\b", name.lower())
    if not m:
        return None
    qty, unit = m.group(1), m.group(2)
    unit = {"pack": "pk"}.get(unit, unit)
    return f"{qty}{unit}"


# ---------------------------------------------------------------------------
# Navigation
# ---------------------------------------------------------------------------

def ensure_app_foreground(app: str) -> None:
    pkg = APPS[app]["package"]
    # Verify the package OWNS the focused window — a monkey launch that
    # hasn't rendered yet leaves the previous app focused; without this
    # check the Sep 2026 PnS cold-start lost the race, and the next
    # tap_text("Shop") matched "Shopping List update" on the Woolworths
    # screen, derailing the whole walk into the wrong app.
    for attempt in range(3):
        cur = adb("shell", "dumpsys", "window", timeout=15)
        focused = [ln for ln in cur.splitlines() if "mCurrentFocus" in ln]
        if any(pkg in ln for ln in focused):
            return
        adb("shell", "monkey", "-p", pkg,
            "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(8)
    raise RuntimeError(f"could not foreground {pkg} after 3 launches")


def goto_products_tab(app: str) -> None:
    # Tap the Products/Shop tab directly whenever it is visible (the bottom
    # bar persists across most screens), backing out only while it is absent.
    # The old fixed home marker ("Categories" / "Search for groceries")
    # broke on app updates — Sep 2026: the Woolworths redesign dropped the
    # Categories rail, so backing out 6x from a PLP exited the app entirely
    # and the tab was never found.
    tab_label = "Products" if app == "woolworths" else "Shop"
    xml = ui_dump()
    for _ in range(6):
        if tap_text(tab_label, xml, settle=4.0):
            return
        adb("shell", "input", "keyevent", "4")
        time.sleep(2)
        xml = ui_dump()
    raise RuntimeError(f"{tab_label} tab not found")


def drill(path: list[str]) -> None:
    """Tap through category levels. Back-and-retry once if a tap misses
    (transient render lag) — the tree is the source of truth."""
    for label in path:
        xml = ui_dump()
        if not tap_text(label, xml, settle=5.0):
            time.sleep(3)
            xml = ui_dump()
            if not tap_text(label, xml, settle=5.0):
                raise RuntimeError(f"category not found on screen: {label!r}")


def collect_plp(max_screens: int = 80, app: str = "woolworths"
                ) -> list[Tile]:
    """Scroll a loaded PLP to exhaustion, parsing tiles from every screen.

    Woolworths: text-node stream + header state machine.
    PAK'nSAVE: content-desc stream, regex-matched tiles; end detection via
    the desc set because PnS has no product-count header to gate on.
    """
    title: Optional[str] = None
    seen: dict[tuple[str, int], Tile] = {}
    stale_rounds = 0
    for screen in range(max_screens):
        xml = ui_dump()
        texts = screen_texts(xml)
        if app == "paknsave":
            new = parse_pns_tiles(screen_descs(xml))
            before = len(seen)
            for t in new:
                seen.setdefault((t.name, t.price_cents), t)
            stale_rounds = 0 if len(seen) > before else stale_rounds + 1
            if stale_rounds >= 2:
                break
            swipe_up()
            continue
        if title is None and texts:
            for t in texts:
                if t not in ("0",) and t.lower() not in _NAME_STOP \
                        and not re.fullmatch(r"\d+ products", t):
                    title = t
                    break
        new = parse_tiles(texts, title)
        before = len(seen)
        for t in new:
            seen.setdefault((t.name, t.price_cents), t)
        grew = len(seen) > before
        stale_rounds = 0 if grew else stale_rounds + 1
        if stale_rounds >= 2:
            break
        swipe_up()
    return list(seen.values())


def scrape_category(path: list[str], app: str = "woolworths") -> list[Tile]:
    ensure_app_foreground(app)
    goto_products_tab(app)
    drill_path = path[:-1]
    if drill_path:
        drill(drill_path)
    # final level: shelf PLP — verify it loaded (a "products" count header)
    xml = ui_dump()
    if not tap_text(path[-1], xml, settle=6.0):
        raise RuntimeError(f"leaf category not found: {path[-1]!r}")
    return collect_plp(app=app)


# ---------------------------------------------------------------------------
# Ingest
# ---------------------------------------------------------------------------

def ensure_store(db: PriceDB, app: str) -> int:
    return db.upsert_store(APPS[app]["store"])


def ingest_tiles(db: PriceDB, tiles: list[Tile], shelf_label: str,
                 app: str = "woolworths") -> int:
    from store_discovery import slugify
    store_id = ensure_store(db, app)
    n = 0
    for t in tiles:
        slug = slugify(t.name)
        if not slug:
            continue
        db.upsert_price(Price(
            store_id=store_id,
            product_slug=slug,
            product_name=t.name,
            price_cents=t.price_cents,
            currency="NZD",
            unit=t.unit,
            unit_price=t.unit_price,
            page_url=f"{app}app://{slugify(shelf_label)}",
        ))
        n += 1
    return n


def scrape_query(db: PriceDB, query: str, app: str = "woolworths") -> dict:
    """Run every category path mapped to a tracked query; ingest + summarize."""
    paths = APPS[app]["query_categories"].get(query)
    if not paths:
        raise SystemExit(f"no category paths mapped for query {query!r} "
                         f"(app: {app}, known queries: "
                         f"{sorted(APPS[app]['query_categories'])})")
    report: dict[str, object] = {}
    total = 0
    for path in paths:
        tiles = scrape_category(path, app)
        n = ingest_tiles(db, tiles, shelf_label=path[-1], app=app)
        report[" > ".join(path)] = f"{len(tiles)} tiles, {n} ingested"
        total += n
        print(f"  {' > '.join(path)}: {len(tiles)} tiles, {n} ingested")
    report["total"] = total
    return report


# ---------------------------------------------------------------------------

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--query", help="tracked query (see --list-queries)")
    src.add_argument("--cat", nargs="+", metavar="LABEL",
                     help="explicit category path, e.g. --cat Drinks Tea")
    src.add_argument("--list-queries", action="store_true")
    ap.add_argument("--app", choices=sorted(APPS), default="woolworths",
                    help="which chain app to drive (default: woolworths)")
    ap.add_argument("--no-ingest", action="store_true",
                    help="parse only — print tiles, don't touch the DB")
    args = ap.parse_args()

    if args.list_queries:
        for q, paths in APPS[args.app]["query_categories"].items():
            print(f"{q}:")
            for p in paths:
                print("   " + " > ".join(p))
        return

    db = PriceDB()
    if args.cat:
        tiles = scrape_category(args.cat, args.app)
        print(f"{len(tiles)} tiles parsed from {' > '.join(args.cat)}")
        for t in tiles[:15]:
            print(f"  ${t.price_cents / 100:6.2f}  {t.name[:60]}"
                  f"  [{t.unit or '-'}] {t.unit_price or ''}")
        if not args.no_ingest:
            print(f"ingested: {ingest_tiles(db, tiles, args.cat[-1], args.app)}")
    else:
        scrape_query(db, args.query, args.app)


if __name__ == "__main__":
    main()