#!/usr/bin/env python3
"""One-shot health probe for the scraper's ollama matcher (cron pre-flight)."""
import json
import urllib.request

URL_TAGS = "http://192.168.1.72:11434/api/tags"
URL_CHAT = "http://192.168.1.72:11434/api/chat"


def post(url, payload, timeout):
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


try:
    with urllib.request.urlopen(URL_TAGS, timeout=10) as r:
        tags = json.loads(r.read().decode())
    names = sorted(m["name"] for m in tags.get("models", []))
    print("models:", names)
except Exception as ex:
    print(f"tags FAILED: {type(ex).__name__}: {ex}")

for model, opts in [("gemma4:e4b", {}), ("qwen3.5:2b", {"think": False})]:
    payload = {"model": model,
               "messages": [{"role": "user",
                             "content": 'Query: "milk"\n\nTiles:\n[{"sku": "1001", "name": "anchor blue milk 1l", "per_unit": "$2.20/1L"}]\nRespond with strict JSON: {"matches": [{"sku": "1001", "confidence": 0.9}]}'}],
               "stream": False,
               "format": "json",
               "options": {"temperature": 0.1, "num_ctx": 8192}}
    payload.update(opts)
    try:
        body = post(URL_CHAT, payload, 60)
        content = (body.get("message") or {}).get("content", "")
        print(f"{model}: done_reason={body.get('done_reason')} "
              f"eval_count={body.get('eval_count')} content={content[:60]!r}")
    except Exception as ex:
        print(f"{model}: FAILED {type(ex).__name__}: {str(ex)[:120]}")