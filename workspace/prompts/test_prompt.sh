#!/usr/bin/env bash
# Test grocery price-search prompt against multiple ollama models.
# Outputs JSON from each model to /tmp/<model>.json for grading.
set -u

QUERY="${1:-large free range eggs}"
LAT="${2:--36.8485}"
LNG="${3:-174.7633}"
REGION="${4:-Auckland, New Zealand}"

SYSTEM=$(cat <<'EOF'
You are "Local Grocery Scout", a price-comparison assistant. You help users find
realistic recent prices for grocery items at stores near their location.

IMPORTANT: You do NOT have access to live price data, store inventory, or the
internet. All prices you return must be realistic estimates based on your
training knowledge of typical grocery prices in the user's region (New Zealand
by default unless the query suggests otherwise).

You MUST respond with valid JSON only. No prose, no markdown fences, no
explanations outside the JSON object. The response must match this schema
exactly:

{
  "query": "<echo of the user's search query>",
  "productName": "<the canonical product name you matched>",
  "results": [
    {
      "store": "<store name>",
      "storeChain": "<e.g. 'Pak'nSave', 'New World', 'Countdown', 'Walmart', 'Safeway', or null if independent>",
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
- Do not include any prose outside the JSON object.
EOF
)

USER=$(cat <<EOF
Search for: "${QUERY}"
User location: lat=${LAT}, lng=${LNG}
Region hint: ${REGION}

Find realistic recent prices for this item at 3-8 grocery stores near the user.
Respond with strict JSON only.
EOF
)

MODELS=("qwen3:30b-a3b" "gemma4:26b" "gemma3:latest" "qwen3:latest")

mkdir -p /tmp/lgs-prompt-test
for MODEL in "${MODELS[@]}"; do
  echo "=== Testing $MODEL ==="
  OUT="/tmp/lgs-prompt-test/${MODEL//[:\/]/_}.json"
  python3 <<PYEOF
import json, urllib.request, sys, time

model = "${MODEL}"
system = ${SYSTEM@Q}
user = ${USER@Q}

start = time.time()
req = urllib.request.Request(
    "http://localhost:11434/api/chat",
    data=json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.2},
    }).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)

try:
    with urllib.request.urlopen(req, timeout=120) as r:
        data = json.loads(r.read())
        elapsed = time.time() - start
        content = data.get("message", {}).get("content", "")
        with open("${OUT}", "w") as f:
            json.dump({"model": model, "elapsed_s": round(elapsed, 2), "raw": content}, f, indent=2)
        print(f"  OK ({elapsed:.1f}s, {len(content)} chars)")
except Exception as e:
    print(f"  FAIL: {e}", file=sys.stderr)
    with open("${OUT}.error", "w") as f:
        f.write(str(e))
PYEOF
done

echo
echo "=== Raw outputs ==="
for MODEL in "${MODELS[@]}"; do
  OUT="/tmp/lgs-prompt-test/${MODEL//[:\/]/_}.json"
  echo "--- $MODEL ---"
  if [ -f "$OUT" ]; then
    cat "$OUT" | python3 -c "import json,sys; d=json.load(sys.stdin); print('elapsed:', d['elapsed_s'], 's'); print('chars:', len(d['raw'])); print('---raw---'); print(d['raw'][:1500])"
  else
    echo "(no output file)"
  fi
  echo
done
