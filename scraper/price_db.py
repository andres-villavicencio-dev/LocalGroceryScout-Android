"""SQLite price database for the Grocery Scout scraper agent.

Schema:
  stores   — physical stores discovered via OSM/Overpass + their online-shop identity
  prices   — scraped product prices, keyed by (store, product slug), with freshness
  queries  — audit log of agent runs (what was searched, when, how long)

Design notes:
- Keep it dumb: one file, WAL mode, no ORM. The agent is the only writer.
- price is stored in cents (int) to dodge float rounding.
- fresh_until lets the API serve cached prices without re-scraping.
"""
from __future__ import annotations

import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator, Optional

DEFAULT_DB = Path(__file__).parent / "prices.db"

SCHEMA = """
PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS stores (
    id INTEGER PRIMARY KEY,
    osm_id TEXT UNIQUE,                -- OSM node/way id, e.g. 'n123456'
    name TEXT NOT NULL,                -- "New World Centre City"
    brand TEXT,                        -- "New World", "Pak'nSave", "Four Square", "Woolworths"
    lat REAL, lng REAL,
    address TEXT,
    online_slug TEXT,                  -- site-specific store slug for online shop, if known
    distance_km REAL,                  -- from the last search origin
    discovered_at REAL NOT NULL,
    updated_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS prices (
    id INTEGER PRIMARY KEY,
    store_id INTEGER NOT NULL REFERENCES stores(id),
    product_slug TEXT NOT NULL,        -- "value standard milk"
    product_name TEXT NOT NULL,        -- display name
    price_cents INTEGER NOT NULL,
    currency TEXT NOT NULL DEFAULT 'NZD',
    unit TEXT,                         -- "2L", "1L", "ea"
    unit_price TEXT,                    -- "$2.45/1L" raw per-unit string
    sku TEXT,                          -- site SKU, e.g. 5260709-EA-000
    page_url TEXT,
    scraped_at REAL NOT NULL,
    UNIQUE(store_id, product_slug, unit)
);

CREATE TABLE IF NOT EXISTS queries (
    id INTEGER PRIMARY KEY,
    query TEXT NOT NULL,
    lat REAL, lng REAL, region TEXT,
    stores_checked INTEGER,
    prices_found INTEGER,
    duration_s REAL,
    created_at REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_prices_store ON prices(store_id);
CREATE INDEX IF NOT EXISTS idx_prices_slug ON prices(product_slug);
CREATE INDEX IF NOT EXISTS idx_prices_fresh ON prices(scraped_at);
CREATE INDEX IF NOT EXISTS idx_stores_brand ON stores(brand);
"""


@dataclass
class Store:
    osm_id: str
    name: str
    brand: Optional[str]
    lat: float
    lng: float
    address: Optional[str] = None
    online_slug: Optional[str] = None
    distance_km: Optional[float] = None
    id: Optional[int] = None


@dataclass
class Price:
    store_id: int
    product_slug: str
    product_name: str
    price_cents: int
    currency: str = "NZD"
    unit: Optional[str] = None
    unit_price: Optional[str] = None
    sku: Optional[str] = None
    page_url: Optional[str] = None
    scraped_at: float = field(default_factory=time.time)
    id: Optional[int] = None


class PriceDB:
    def __init__(self, path: Path = DEFAULT_DB):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.tx() as cur:
            cur.executescript(SCHEMA)

    @contextmanager
    def tx(self) -> Iterator[sqlite3.Cursor]:
        conn = sqlite3.connect(self.path, timeout=30)
        conn.row_factory = sqlite3.Row
        try:
            cur = conn.cursor()
            yield cur
            conn.commit()
        finally:
            conn.close()

    # ---- stores ----
    def upsert_store(self, store: Store) -> int:
        with self.tx() as cur:
            now = time.time()
            cur.execute(
                """INSERT INTO stores (osm_id, name, brand, lat, lng, address, online_slug, distance_km, discovered_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(osm_id) DO UPDATE SET
                     name=excluded.name, brand=excluded.brand, lat=excluded.lat,
                     lng=excluded.lng, address=excluded.address,
                     online_slug=COALESCE(excluded.online_slug, stores.online_slug),
                     distance_km=excluded.distance_km, updated_at=excluded.updated_at
                   RETURNING id""",
                (store.osm_id, store.name, store.brand, store.lat, store.lng,
                 store.address, store.online_slug, store.distance_km, now, now),
            )
            row = cur.fetchone()
            # RETURNING inside ON CONFLICT needs sqlite >= 3.35; fallback:
            if row is None:
                cur.execute("SELECT id FROM stores WHERE osm_id=?", (store.osm_id,))
                row = cur.fetchone()
            return row["id"]

    def stores_by_brand(self, brand: str) -> list[Store]:
        with self.tx() as cur:
            cur.execute("SELECT * FROM stores WHERE brand LIKE ? ORDER BY distance_km", (brand,))
            return [Store(**{k: r[k] for k in ("id", "osm_id", "name", "brand", "lat", "lng",
                                               "address", "online_slug", "distance_km")})
                    for r in cur.fetchall()]

    def all_stores(self) -> list[Store]:
        with self.tx() as cur:
            cur.execute("SELECT * FROM stores ORDER BY distance_km")
            return [Store(**{k: r[k] for k in ("id", "osm_id", "name", "brand", "lat", "lng",
                                               "address", "online_slug", "distance_km")})
                    for r in cur.fetchall()]

    # ---- prices ----
    def upsert_price(self, price: Price) -> int:
        with self.tx() as cur:
            cur.execute(
                """INSERT INTO prices (store_id, product_slug, product_name, price_cents,
                                       currency, unit, unit_price, sku, page_url, scraped_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(store_id, product_slug, unit) DO UPDATE SET
                     price_cents=excluded.price_cents, product_name=excluded.product_name,
                     unit_price=excluded.unit_price, sku=excluded.sku,
                     page_url=excluded.page_url, scraped_at=excluded.scraped_at
                   RETURNING id""",
                (price.store_id, price.product_slug, price.product_name, price.price_cents,
                 price.currency, price.unit, price.unit_price, price.sku, price.page_url,
                 price.scraped_at),
            )
            row = cur.fetchone()
            if row is None:
                cur.execute(
                    "SELECT id FROM prices WHERE store_id=? AND product_slug=? AND unit IS ?",
                    (price.store_id, price.product_slug, price.unit))
                row = cur.fetchone()
            return row["id"] if row else -1

    def fresh_prices_for_query(self, query_slug: str, max_age_s: float) -> list[dict]:
        """Return recent prices matching the query slug.

        Matching is word-wise: every word of the query slug (minus stopwords)
        must appear in the product slug. "milo cereal" matches
        "nestle milo breakfast cereal" (both words present) even though the
        exact phrase never appears contiguously. Words shorter than 3 chars
        are ignored as noise.
        """
        import re as _re
        STOP = {"the", "and", "for", "with", "from"}
        words = [w for w in _re.split(r"[^a-z0-9]+", query_slug.lower())
                 if len(w) >= 3 and w not in STOP]
        if not words:
            return []
        like_clauses = " AND ".join(
            f"p.product_slug LIKE '%' || ? || '%'" for _ in words)
        with self.tx() as cur:
            # Dedupe: repeated live scrapes of the same item can insert twice
            # (SQLite UNIQUE treats NULL units as distinct). Group on the
            # identity that matters and keep the most recent scrape.
            cur.execute(
                f"""SELECT p.*, s.name AS store_name, s.brand AS store_brand, s.distance_km,
                       MAX(p.scraped_at) AS scraped_at
                    FROM prices p JOIN stores s ON s.id = p.store_id
                    WHERE ({like_clauses}) AND p.scraped_at > ?
                    GROUP BY p.store_id, p.product_slug, p.price_cents, COALESCE(p.unit, '')
                    ORDER BY s.distance_km, p.price_cents""",
                (*words, time.time() - max_age_s))
            return [dict(r) for r in cur.fetchall()]

    # ---- audit ----
    def log_query(self, query: str, lat: float, lng: float, region: str,
                  stores_checked: int, prices_found: int, duration_s: float) -> int:
        with self.tx() as cur:
            cur.execute(
                """INSERT INTO queries (query, lat, lng, region, stores_checked,
                                        prices_found, duration_s, created_at)
                   VALUES (?,?,?,?,?,?,?,?)""",
                (query, lat, lng, region, stores_checked, prices_found, duration_s, time.time()))
            cur.execute("SELECT last_insert_rowid() AS id")
            return cur.fetchone()["id"]

    def stats(self) -> dict:
        with self.tx() as cur:
            out = {}
            for table in ("stores", "prices", "queries"):
                cur.execute(f"SELECT COUNT(*) AS n FROM {table}")
                out[table] = cur.fetchone()["n"]
            cur.execute("SELECT MAX(scraped_at) AS t FROM prices")
            r = cur.fetchone()
            out["last_scrape"] = r["t"]
            return out