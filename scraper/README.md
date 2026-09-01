# Grocery Scout Scraper Agent

A browser-agent that scrapes **real** grocery prices from NZ supermarket
online shops, maintaining a local price database with freshness-aware
caching.

## Why

The Android app's LLM mode (gemma4:e4b via ollama) generates *plausible*
prices from training memory — useful for coverage, but not real. This agent
gets actual shelf prices from the chains' online shops.

## What it does

1. **Store discovery** — queries OSM/Overpass for supermarkets within a
   radius of the user's coordinates, keeps supported brands, persists them
   with haversine distances.
2. **Browser scraping** — headless Chromium (Playwright) opens each brand's
   search page, extracts product tiles via stable DOM handles
   (`data-testid="product-<SKU>"`, `itemprop="price"` microdata,
   `non-promo-unit-price`), with light anti-bot fingerprinting.
3. **LLM matching** — a local ollama model (gemma4:e4b) picks which tiles
   match the user's query ("milk" → fresh 2L standard milk, not milk
   powder), returns canonical names + confidence + derived pack units.
4. **Price DB** — SQLite (WAL), prices in cents, keyed by
   (store, product, unit), with scrape timestamps for freshness.
5. **API** — FastAPI on :8300 with a 3-day freshness window: cached prices
   serve in ~0s; cache misses trigger a live agent run (~30-90s).

## Supported chains (NZ)

| Chain | Status | Notes |
|-------|--------|-------|
| New World | ✅ working | Foodstuffs platform, schema.org microdata |
| Pak'nSave | ✅ working | Foodstuffs platform, SKU card handles |
| Four Square | ❌ no e-commerce | site has no online shop |
| Woolworths | ❌ Akamai-blocked | headless browsers get ERR_HTTP2_PROTOCOL_ERROR |

OSM brand tags are normalized (`PAK'nSAVE` ≡ `Pak'nSave` ≡ `paknsave`).

## Run

```bash
pip install playwright beautifulsoup4 fastapi uvicorn
python -m playwright install chromium   # headless shell for your pw version

# API server
python -m uvicorn api:app --host 0.0.0.0 --port 8300

# direct agent use
python3 -c "
from price_db import PriceDB; from price_agent import PriceAgent
agent = PriceAgent(PriceDB())
print(agent.run('milk', -36.905, 174.905, 'NZ'))"
```

## Endpoints

- `GET /health` — liveness + DB stats
- `GET /prices?store=Pak%27nSave` — cached prices
- `POST /search` — `{"query":"milk","lat":-36.9,"lng":174.9,"region":"NZ",
  "radius_m":6000,"force_refresh":false}` → app-shaped SearchResult JSON
  with `source: "cache" | "live-scrape"`

## Freshness policy

Prices scraped within 3 days serve from cache. Supermarket prices move on a
weekly promo cycle, so 3 days balances staleness against scraping cost
(30-90s + bot-detection risk per run).

## Known limitations

- **Woolworths blocked** — Akamai bot detection kills headless requests.
  Revisit with residential proxies or their mobile app's API.
- **Store selection is IP-based** — the Foodstuffs sites pick the branch
  nearest the *scraper's* IP, not the user's coordinates. Fine when the
  scraper runs on the user's LAN; note it for cloud deployments.
- **Unit derivation is LLM-guessed** — pack sizes come from per-unit prices
  ("$2.45/1L" → 2L) and can mis-read (bread "100g" vs loaf).
- **Rate-limiting** — no backoff yet; hammering the sites risks IP bans.

## Files

- `price_db.py` — SQLite schema + CRUD
- `store_discovery.py` — Overpass + brand normalization
- `price_agent.py` — Playwright agent + ollama matcher
- `api.py` — FastAPI service
- `prices.db` — the DB itself (gitignored)