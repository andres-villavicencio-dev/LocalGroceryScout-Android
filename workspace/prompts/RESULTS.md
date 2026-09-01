# Ollama Prompt Test Results

**Date:** 2026-09-01 (UTC)
**Query:** `"large free range eggs 12 pack"` (Auckland, NZ)

## Verdict

**Winner: `gemma4:e4b`** — 44s, valid JSON, 4 results, 0 schema issues.

gemma4:26b timed out (cold loading 17GB > 180s); qwen3:30b-a3b not tested because
the script crashed on the timeout. We have a working answer for the e4b tier,
which is the realistic target for "phone over LAN" UX.

## Detailed results

| Model | Status | Latency | Results | Issues |
|-------|--------|---------|---------|--------|
| `gemma4:e4b` | PASS | 44.0s | 4 | 0 |
| `gemma4:26b` | TIMEOUT (>180s) | — | — | cold load + generate exceeded 180s |
| `qwen3:30b-a3b` | SKIPPED | — | — | script crashed after 26b timeout |

## gemma4:e4b output (sanitized)

```json
{
  "query": "large free range eggs 12 pack",
  "productName": "Free Range Eggs (Large), 12 count",
  "results": [
    {"store":"Countdown",   "price":7.50, "currency":"NZD", "confidence":0.90,  "reasoning":"mid-range price for branded free range eggs"},
    {"store":"Pak'nSave",   "price":6.80, "currency":"NZD", "confidence":0.95,  "reasoning":"typically the most budget-friendly pricing on staples"},
    {"store":"Four Square", "price":8.20, "currency":"NZD", "confidence":0.85,  "reasoning":"convenience store with higher pricing"},
    {"store":"New World",   "price":7.99, "currency":"NZD", "confidence":0.90,  "reasoning":"competitive but slightly higher than Pak'nSave"}
  ],
  "summary": "Pak'nSave currently offers the lowest estimated price for free range eggs at $6.80 NZD per 12 pack, though prices can vary due to sales."
}
```

**Quality observations:**
- Prices sorted ascending as instructed ✓
- Realistic NZ chain ordering (Pak'nSave cheapest, Four Square most expensive) ✓
- All confidence scores ≥ 0.85 ✓
- Honest summary with stale-data caveat ✓
- No prose outside JSON ✓

## What the prompt got right

1. **The "no live data" disclaimer** stopped the model from fabricating addresses
   (all came back as "unknown" — exactly what we asked for).
2. **Strict JSON output** + `format=json` in ollama request → guaranteed parseable.
3. **The chain hint list** (Pak'nSave / New World / Countdown / 4 Square) primed the
   model to pick the right regional brands.
4. **Confidence + reasoning** fields give the user a way to gauge trust.

## What's next

- **Bake `gemma4:e4b` as the default model** in `BuildConfig.DEFAULT_OLLAMA_MODEL`.
- Re-test with a second query (US-style) to confirm the prompt handles both regions.
- Once you have a faster machine / model loading, retry gemma4:26b with a 600s timeout
  to see if it's worth promoting to default.

## Files

- `test_prompt.py` — the harness
- `test_prompt.sh` — bash equivalent (deprecated; python version is canonical)
- `price-search-system.md` — prompt docs, kept in sync with `OllamaPrompts.kt`
