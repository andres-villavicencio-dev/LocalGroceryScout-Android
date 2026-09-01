#!/usr/bin/env python3
"""Test grocery price-search prompt against multiple ollama models.

Outputs raw JSON response per model to /tmp/lgs-prompt-test/<model>.json and prints
a quick comparison. Models are evaluated on:
- Valid JSON parse
- Schema conformance (required fields, types)
- Latency
- Response length

Usage: python3 test_prompt.py [query] [lat] [lng] [region]
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

QUERY = sys.argv[1] if len(sys.argv) > 1 else "large free range eggs 12 pack"
LAT = sys.argv[2] if len(sys.argv) > 2 else -36.8485
LNG = sys.argv[3] if len(sys.argv) > 3 else 174.7633
REGION = sys.argv[4] if len(sys.argv) > 4 else "Auckland, New Zealand"

SYSTEM = """You are "Local Grocery Scout", a price-comparison assistant. You help users find realistic recent prices for grocery items at stores near their location.

IMPORTANT: You do NOT have access to live price data, store inventory, or the internet. All prices you return must be realistic estimates based on your training knowledge of typical grocery prices in the user's region (New Zealand by default unless the query suggests otherwise).

You MUST respond with valid JSON only. No prose, no markdown fences, no explanations outside the JSON object. The response must match this schema exactly:

{
  "query": "<echo of the user's search query>",
  "productName": "<the canonical product name you matched>",
  "results": [
    {
      "store": "<store name>",
      "storeChain": "<e.g. 'Pak\\'nSave', 'New World', 'Countdown', 'Walmart', 'Safeway', or null if independent>",
      "price": <number, NZD or USD as stated in context>,
      "currency": "NZD" | "USD",
      "unit": "<e.g. '1L', '500g', '12ct', 'each'>",
      "address": "<best known street address, or 'unknown'>",
      "distanceKm": <number or null if unknown>,
      "confidence": <0.0 to 1.0>,
      "reasoning": "<one short sentence explaining why this confidence>"
    }
  ],
  "summary": "<one sentence telling the user which store has the lowest price and any caveats about staleness>"
}

RULES:
- Return 3 to 8 results, sorted from lowest to highest price.
- If you don't know a store's exact address, use 'unknown' — do NOT fabricate a specific street number.
- Confidence 0.85+ means you're fairly confident in the price; 0.5 means a rough estimate.
- For NZ queries, default chains are Pak'nSave, New World, Countdown (Woolworths NZ), and 4 Square.
- For US queries, default chains are Walmart, Kroger, Safeway, Trader Joe's, Whole Foods, Target.
- Do not include any prose outside the JSON object."""

USER = f"""Search for: "{QUERY}"
User location: lat={LAT}, lng={LNG}
Region hint: {REGION}

Find realistic recent prices for this item at 3-8 grocery stores near the user.
Respond with strict JSON only."""

# User prefers gemma4 family. We have gemma4:26b (17GB) and gemma4:e4b (9.6GB).
# Test both, and qwen3:30b-a3b as a non-gemma reference. Drop gemma3/qwen3:latest.
MODELS = ["gemma4:e4b", "gemma4:26b", "qwen3:30b-a3b"]
OUT_DIR = Path("/tmp/lgs-prompt-test")
OUT_DIR.mkdir(parents=True, exist_ok=True)


def grade(raw_text: str) -> dict:
    """Parse + schema-check the response."""
    issues = []
    try:
        data = json.loads(raw_text)
    except json.JSONDecodeError as e:
        return {"valid_json": False, "issues": [f"JSON parse error: {e}"], "n_results": 0}
    if not isinstance(data, dict):
        return {"valid_json": True, "issues": ["top-level not an object"], "n_results": 0}
    for key in ("query", "productName", "results", "summary"):
        if key not in data:
            issues.append(f"missing top-level key: {key}")
    results = data.get("results", [])
    if not isinstance(results, list):
        issues.append("results is not a list")
        results = []
    if len(results) < 3 or len(results) > 8:
        issues.append(f"results count {len(results)} out of range [3,8]")
    for i, r in enumerate(results):
        if not isinstance(r, dict):
            issues.append(f"results[{i}] not an object")
            continue
        for k in ("store", "price", "currency", "confidence"):
            if k not in r:
                issues.append(f"results[{i}] missing {k}")
        if "price" in r and not isinstance(r["price"], (int, float)):
            issues.append(f"results[{i}].price not numeric")
        if "confidence" in r and not isinstance(r["confidence"], (int, float)):
            issues.append(f"results[{i}].confidence not numeric")
    return {"valid_json": True, "issues": issues, "n_results": len(results)}


def query_model(model: str) -> dict:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": USER},
        ],
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.2},
    }
    req = urllib.request.Request(
        "http://localhost:11434/api/chat",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            data = json.loads(r.read())
            elapsed = time.time() - start
            return {
                "ok": True,
                "elapsed_s": round(elapsed, 2),
                "content": data.get("message", {}).get("content", ""),
                "eval_count": data.get("eval_count"),
                "prompt_eval_count": data.get("prompt_eval_count"),
            }
    except (urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError) as e:
        return {"ok": False, "error": str(e), "elapsed_s": round(time.time() - start, 2)}


print(f"Query:    {QUERY}")
print(f"Location: lat={LAT}, lng={LNG}, region={REGION}")
print(f"Models:   {MODELS}\n")

results = []
for model in MODELS:
    print(f"=== {model} ===")
    out_path = OUT_DIR / f"{model.replace(':', '_').replace('/', '_')}.json"
    r = query_model(model)
    if not r["ok"]:
        print(f"  FAIL: {r.get('error')}")
        out_path.write_text(json.dumps({"model": model, "error": r.get("error")}))
        results.append({"model": model, "ok": False})
        continue
    raw = r["content"]
    out_path.write_text(json.dumps({
        "model": model,
        "elapsed_s": r["elapsed_s"],
        "eval_count": r["eval_count"],
        "prompt_eval_count": r["prompt_eval_count"],
        "raw": raw,
    }, indent=2))
    g = grade(raw)
    print(f"  {r['elapsed_s']}s | {len(raw)} chars | valid_json={g['valid_json']} | n_results={g['n_results']} | issues={len(g['issues'])}")
    if g["issues"]:
        for issue in g["issues"]:
            print(f"    - {issue}")
    print(f"  raw preview: {raw[:300]}{'...' if len(raw) > 300 else ''}")
    results.append({"model": model, "ok": True, "elapsed": r["elapsed_s"], "grade": g})
    print()

print("\n=== SUMMARY ===")
for r in results:
    if not r["ok"]:
        print(f"  {r['model']}: FAILED")
        continue
    g = r["grade"]
    status = "PASS" if (g["valid_json"] and not g["issues"]) else "ISSUES"
    print(f"  {r['model']:<20} {r['elapsed']:>6.1f}s | {g['n_results']} results | {status} ({len(g['issues'])} issues)")
