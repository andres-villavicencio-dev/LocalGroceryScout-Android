# Spec & Plan: Receipt-to-Savings (📸 → 🧾 → 💰)

*Generated with spec-writer. Grounded in the codebase as of commit `dc6da9d`.*

**One-line purpose:** Snap a photo of a grocery receipt and see what every item would cost at its cheapest store across all scouted chains — with total estimated savings.

---

## SECTION 1: SPEC

**Users and use cases**
- As a shopper who just checked out, I want to photograph my receipt so that I can see which of my items are cheaper elsewhere next time.
- As a price-conscious user, I want a per-item savings estimate so that I know whether switching stores for my usual basket is worth the trip.
- As a user who scans receipts week after week, I want the store and date auto-detected so that I don't re-enter metadata for every receipt.

**Requirements**
1. The user can capture a receipt photo in-app (camera) or pick one from the gallery.
2. The system extracts the store name and the purchased line items (name, quantity, price) from the photo.
3. Each extracted item is matched against the price database to find the cheapest current price across chains.
4. Each item row shows: receipt name, receipt price, cheapest store, cheapest price, and savings (receipt price − cheapest price; hidden or shown as $0.00 when the receipt was already cheapest).
5. The screen shows a **receipt total** and an **estimated savings total**, with the savings figure as the visual hero.
6. Items the matcher cannot confidently price are still listed, marked unpriced, and excluded from the savings total. [ASSUMPTION: partial success is displayed honestly, not hidden]
7. Extraction runs server-side (scraper host), not on the phone. [ASSUMPTION]
8. The receipt photo itself is not stored after processing unless the user saves the session. [ASSUMPTION]
9. A processed receipt becomes a reviewable "receipt session" the user can dismiss or keep on the History tab. [ASSUMPTION: v1 keeps in-memory only, no persistence]

**Edge cases**
- Blurry / glare / crumpled receipt → extraction returns low-confidence or empty items; the app shows a retake prompt instead of inventing results. **Hard rule: no fabricated items, ever.**
- Receipt from a non-scouted chain (e.g. Countdown/Woolworths, Four Square, dairy) → store is still identified and displayed; items are priced against the 4 scouted chains with a note that the receipt store itself isn't scouted.
- Receipt item has no match in the DB (niche product, house-brand not yet catalogued) → unpriced row, excluded from savings.
- Quantity × unit-price receipts (per-kg produce) → quantity parsed and total used for matching; if unparseable, item marked unpriced. [ASSUMPTION: use the line total when both are present]
- Multi-page/long receipts cropped by photo → items visible in frame are extracted; user sees count and can retake.
- Same product appears twice on the receipt (two quantities scanned separately) → both rows processed independently.
- Scraper API unreachable → friendly offline state consistent with existing NoNetworkScreen behavior.
- Receipt photos with no receipt at all (photo of the cat) → extraction returns zero items + "no receipt detected" state, one free retry.

**Acceptance criteria**
```
Given the user is on the new Receipt tab
When they tap "Scan receipt" and photograph a New World receipt
Then within ~15s they see the store name, an item list where every confidently
     matched item shows receipt price + cheapest scouted price + savings,
     a savings total, and the receipt total
Given a receipt item is already at the cheapest scouted price
When the results render
Then that item shows no savings delta and is excluded from the savings total
Given a receipt item matches nothing in the DB
When the results render
Then it appears as "unpriced", greyed, and excluded from the savings total
Given the camera returns a blurry or non-receipt photo
When extraction completes with low confidence
Then the app shows a retake prompt, never fabricated items
Given the scraper is unreachable
When the user submits a receipt
Then the app shows the offline/retry state
```

---

## SECTION 2: PLAN

**Stack and architecture** — fits the existing two-piece architecture with one new scraper endpoint and one new Android pipeline. The heavy lifting (OCR + matching) lives on the scraper host where the GPU + DB already live:

```
Phone (CameraX still-capture / photo picker)
   │  JPEG (scaled ≤1600px, quality 82) — multipart or base64 JSON
   ▼
Scraper host:  POST /receipt/scan
   1. OCR via gemma3:latest (VISION_MODEL — already resident on the 3070,
      prompt-validated in vision_scraper.py Sep 2026)
   2. LLM structuring → {store, items[{name, qty, unit_price, line_total}], subtotal, total}
      (same resilience ladder as matching: gemma4:e4b → qwen3.5:2b fallback)
   3. Per-item pricing: existing search pipeline (exact-name → LLM matcher)
      against live prices; reuse /compare two-stage matching for renamed twins
   4. Savings math server-side → one response
   ▼
Phone renders: store header, item rows (receipt vs cheapest), savings banner
```

Key decisions:
- **OCR = gemma3:latest** — it's already on the box, proven on shelf-label photos, and saves ~2GB vs a dedicated OCR model. Receipts are printed text; a 4B VLM handles them. If accuracy disappoints on thermal-paper tests, upgrade path is a dedicated OCR pass. [ASSUMPTION: good enough — validate in Task 2]
- **All vision/LLM on the scraper host**, phone stays thin — consistent with the existing architecture (the phone never runs models).
- **Rebrand-aware store detection**: extraction returns both the raw store name and a canonical brand mapped against the 4 scouted chains + known unscouted NZ chains (Countdown/Woolworths, Four Square, SuperValue) so the UI can badge "not scouted".
- **Savings math**: `savings = Σ max(0, line_total − cheapest_price)`. Receipt items are matched to DB products; the cheapest *live* price wins. Items priced at their receipt store use that store's own price (delta $0.00, honest).
- **Latency budget**: OCR ~5-10s (gemma3), matching 10 items × ~1-2s each (exact-name hits are instant; only misses hit the LLM) → target ≤45s end-to-end, streamed progress states in the UI.

**Data model changes** (scraper, SQLite)
- v1: **none required** — receipt sessions are ephemeral. v2 (post-MVP): `receipts` table (id, store_brand, total_cents, scanned_at, image_hash) + `receipt_items` (receipt_id, raw_name, line_total_cents, matched_product_slug, matched_store_id, savings_cents) to power "your savings history". [ASSUMPTION: MVP skips persistence]

**API contracts**
- `POST /receipt/scan` — multipart file (or JSON base64 fallback)
  - Input: `image` (JPEG ≤5MB), optional `lat`/`lng` for distance sorting
  - Pipeline: OCR → structure → price-match each item
  - Output: `{store: {raw, canonical, scouted: bool}, items: [{raw_name, qty, line_total, match: {status: exact|llm|none, product_name, store_chain, price, unit_price, confidence}, savings}], receipt_total, subtotal_matched, estimated_savings, processing_ms}`
  - Errors: 422 no-receipt-detected; 422 image unreadable; 502 OCR/LLM ladder exhausted; 413 image too large
- `GET /receipt/health` — reports OCR + pricing model availability (UI gates the feature). [ASSUMPTION: feature-flag via health, consistent with existing /health pattern]

**Patterns to follow**
- Android: new `ReceiptApi` methods in the existing `ScraperApi` Retrofit interface, new `ReceiptViewModel` mirroring `SearchViewModel` state patterns, a new top-level tab (or entry point from Search) following the existing bottom-nav destinations. Multipart upload via the existing OkHttp stack.
- Scraper: new `receipt_scan.py` module alongside `price_agent.py`/`vision_scraper.py`; FastAPI router in `api.py`; fallback ladder reused from the matching code.
- Progress UX: reuse the trolley-loader pattern for the processing state.

**Testing strategy**
- Scraper unit: receipt-structuring LLM output schema validation; savings math incl. edge cases (all-cheapest, all-unpriced, negative-deltas clamped); store canonicalization mapping.
- Scraper integration: 3-5 real receipt photos per chain (New World, PAK'nSAVE, Woolworths unscouted) as fixtures — assert item counts and totals within tolerance. This is where OCR accuracy is proven before any UI work ships.
- Android: ViewModel state-machine tests (idle → capturing → uploading → processing → results/error); Compose UI test for the savings banner and unpriced rows.
- E2E: on-device photo of a real receipt → savings list (the demo that matters).

**Security and performance**
- Receipt images stay on LAN (phone → your scraper); never persisted to disk by default; memory-only processing. [ASSUMPTION: single-user home deployment, no auth needed — consistent with existing endpoints]
- Image size cap 5MB, dimension cap 1600px long edge (thermal receipts don't need more).
- Concurrency: per-item pricing requests to the DB are local; LLM calls serialized to protect the GPU queue; endpoint timeout 120s.
- No new phone permissions: camera already granted for the barcode scanner.

---

## SECTION 3: TASKS

## Task 1: Receipt image intake endpoint (scraper)
**What to build:** `POST /receipt/scan` in api.py accepting a JPEG/PNG (multipart preferred, base64 JSON fallback), validating size/dimensions, storing nothing. Stub the pipeline: returns `{store: null, items: [], estimated_savings: 0, processing_ms}` with 200, and 422 for non-images.
**Files:** `scraper/api.py`, `scraper/receipt_scan.py` (new)
**Acceptance:** curl with a 500KB JPEG returns 200 + valid skeleton JSON in <2s; a text file upload returns 422.
**Dependencies:** none

## Task 2: Receipt OCR + structuring (the make-or-break task)
**What to build:** In `receipt_scan.py`: resize/crop-to-content preprocessing (thermal receipts are narrow and tall — pad to the aspect ratio VLMs handle), then gemma3:latest OCR → strict-JSON structure `{store, items[{name, qty, unit_price, line_total}], subtotal, total}`. Prompt engineering pass with 5+ real receipt photos of varying quality (crumpled, low-light, glare). Test the LLM fallback ladder on this path. If gemma3 extraction accuracy on real receipts is below ~90% line-item recall, STOP and report — escalate OCR approach before proceeding.
**Files:** `scraper/receipt_scan.py`
**Acceptance criteria:** 3+ real receipt photos (at least one New World, one PAK'nSAVE) extract with correct store and ≥90% of line items with prices; malformed output triggers the fallback ladder and is caught; a cat photo returns 422 no-receipt.
**Dependencies:** Task 1

## Task 3: Per-item pricing + savings engine
**What to build:** For each structured item: exact-slug lookup first, then LLM matcher (existing `llm_match_tiles` ladder), then cross-chain cheapest via the existing compare two-stage logic. Compute per-item cheapest (store, price) and clamp savings ≥0. Unit-test savings math with fixtures: all-cheapest (savings 0), all-unpriced, mixed, receipt-store-cheapest.
**Files:** `scraper/receipt_scan.py`, reuses `price_agent.py` + `price_db.py`
**Acceptance criteria:** given 10 structured items, returns 10 match results with correct cheapest-store selection in <30s; savings total = Σ max(0, deltas); items with no DB hit have `match.status == "none"` and contribute 0 savings.
**Dependencies:** Task 2

## Task 4: Android — ReceiptViewModel + camera/picker capture flow
**What to build:** New "Receipt" entry point (bottom-nav tab or Search-screen action — either works; [ASSUMPTION: bottom-nav 5th tab]). CameraX still-capture (reuse the scanner's camera binding pattern, add `ImageCapture` use case) + photo-picker fallback. Compress to ≤1600px/82% JPEG, upload to `/receipt/scan`, expose a state machine: `idle → capturing → uploading → processing(progress text) → result | error`.
**Files:** `ui/screens/receipt/` (new: `ReceiptScreen.kt`, `ReceiptViewModel.kt`, `ReceiptResult.kt`), `ScraperApi.kt`, navigation.
**Acceptance criteria:** from cold start, user can reach capture → gallery pick works when camera denied → upload fires and progress states render in order.
**Dependencies:** Task 1 (endpoint exists to upload to)

## Task 5: Android — results UI
**What to build:** Results screen: store header (name + "not scouted" badge when applicable), savings banner ("You could have saved **$4.32**"), item rows (receipt name, receipt price struck-through when cheaper elsewhere, cheapest store + price, match-confidence chip), unpriced rows greyed with an "unpriced" tag, receipt total + matched subtotal footer, "Scan another" action. Trolley-loader processing state with receipt-specific progress lines.
**Files:** `ReceiptScreen.kt` / `ReceiptResult.kt`
**Acceptance criteria:** rendered with a 10-item fixture: savings banner equals Σ positive deltas from the fixture; unpriced items visibly excluded; screenshot review on-device.
**Dependencies:** Task 4, Task 3

## Task 6: E2E polish + ship
**What to build:** On-device test with 3 real receipts (incl. one Woolworths receipt as the unscouted-chain case). Fix the top 3 UI/extraction warts found. Update README feature list + add a receipt screenshot. Ship.
**Files:** README.md, minor fixes across the above.
**Acceptance criteria:** real-receipt demo produces sensible savings on-device; README updated; pushed.
**Dependencies:** Task 5

**Review checkpoint:** Before Task 2's prompt work, collect 5+ real receipts from your kitchen drawer (different chains, one crumpled) — extraction quality on real thermal paper is the go/no-go for the whole feature, and it's the one thing we can't know until we test.

---

## Assumptions to review

1. **OCR + all extraction runs on the scraper host using gemma3 (already resident), not on-device or a cloud vision API** — Impact: HIGH. Correct this if you'd rather use Google ML Kit text recognition on the phone (offline, free, but then structuring still needs an LLM pass server-side anyway).
2. **MVP is ephemeral: no receipt/image persistence, no savings-history DB** — Impact: HIGH. Correct this if you want "track my savings over weeks" from day one (adds the receipt tables + a History integration).
3. **Bottom-nav 5th tab for the entry point** — Impact: MEDIUM. Correct this if you'd rather have it as an action inside Search (home screen button) to keep the nav bar at 4.
4. **Only the 4 scouted chains are used for cheapest-price math; unscouted receipt stores (Woolworths) are identified but only their receipt prices are known** — Impact: MEDIUM. Correct this if Countdown should be a scraping-chain priority first so receipts from it can be fully priced.
5. **Savings = Σ max(0, receipt price − cheapest scouted price), quantities collapsed per line, per-kg produce uses line total** — Impact: MEDIUM. Correct this if you want different math (e.g. excluding specials).
6. **No auth on the receipt endpoint** — Impact: LOW (matches existing scraper endpoints, LAN-only).
7. **~45s end-to-end is acceptable UX with streaming progress** — Impact: LOW. Correct this if you want aggressive parallel pricing (more GPU pressure).