# Local Grocery Scout — Android Port: Debug Report

**Date:** 2026-09-01
**Scope:** ollama integration parse failures blocking search results
**Status:** ✅ Resolved — verified end-to-end on physical device (Samsung SM-N950F)

---

## Verdict

Every "Could not reach ollama" / "Unexpected JSON token" error traced to a single
root cause: **kotlinx-serialization's `encodeDefaults` defaults to `false`, so the
app never sent `"stream": false` (or `format` / `options`) to ollama.** Ollama
treats an absent `stream` field as `stream: true` and replies with an NDJSON
stream — a sequence of separate JSON objects — which the app's single-object
parser cannot read.

---

## Evidence

| # | Observation | Source | Meaning |
|---|-------------|--------|---------|
| 1 | Phone error: `Unexpected JSON token at offset 144 … had { instead at path: $` with input showing `…inking":"The"},"done":false} {"model":"gemma4:e4b",…` | App UI (screenshots) | Parser received **two concatenated JSON objects** |
| 2 | Host-side `curl` with explicit `"stream": false` → `Content-Type: application/json`, single object, parses clean | Direct API tests | Server behaves correctly when asked correctly — bug was client-side |
| 3 | `ChatRequest` declares `stream: Boolean = false`, `format: String = "json"`, `options = ChatOptions()` — all defaulted | `OllamaDtos.kt` | Fields with default values in kotlinx-serialization are **omitted from the wire** unless `encodeDefaults = true` |
| 4 | Logcat request body (old build): `{"model":"gemma4:e4b","messages":[…]}` — **no `stream`, no `format`, no `options`** | `HttpLoggingInterceptor` BODY-level logs | Confirms defaults were silently dropped |
| 5 | Logcat response (old build): `Content-Type: application/x-ndjson`, `Transfer-Encoding: chunked` | Same | Server honored its default: no `stream` → stream it |
| 6 | After fix — request body: `…,"stream":false,"format":"json","options":{"temperature":0.2,"num_ctx":8192}}` | New build logcat | All fields present on the wire |
| 7 | After fix — response: `Content-Type: application/json`, single 3338-byte object, `message.content` = clean JSON payload | New build logcat | Parse succeeds |
| 8 | UI renders 4 store cards: Four Square NZD 3.19 (85%), Countdown NZD 3.49 (90%), Pak'nSave NZD 3.69, + 1 below fold; summary chip; no error text | Device screenshot | Full end-to-end success |

---

## Fix list (ordered)

1. **`encodeDefaults = true`** in the `Json {}` config in `OllamaApiFactory.kt` —
   the actual root-cause fix. Without it, every defaulted field vanishes from
   the request.
2. **`@EncodeDefault(EncodeDefault.Mode.ALWAYS)`** on `ChatRequest.stream` and
   `ChatRequest.format` (`OllamaDtos.kt`) — belt-and-braces so these two
   survive any future Json-config regression.
3. **`sanitizeModelJson()`** in `OllamaRepository.kt` — defensive parser that
   extracts the first balanced JSON object from `message.content`, tolerating
   markdown fences (```json … ```) and reasoning prose around the payload.
4. **HTTP logging raised to BODY level** (`OllamaApiFactory.kt`) — this is what
   cracked the case; kept on for debug builds. Drop to BASIC for release.

---

## Why host tests passed while the phone failed

Python test scripts serialized `"stream": false` explicitly. The app relied on
kotlinx-serialization defaults, which don't serialize. Same endpoint, same
intent — different bytes, different server behavior. Any future integration
test must assert the **actual bytes on the wire**, not the data class shape.

---

## Reproduce / verify

```bash
# 1. Watch the wire (device attached)
adb logcat | grep -E "okhttp.OkHttpClient.*(POST|<--|Content-Type)"

# 2. Fire a search in the app ("milk")

# 3. Expect: request contains "stream":false; response Content-Type is
#    application/json; results render.
```

## Environment

- Device: Samsung SM-N950F (Note 8), Android 9, USB debugging over ADB
- Model server: ollama 0.30.6 at `192.168.1.72:11434`, model `gemma4:e4b`
- Build: `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL
- App id: `com.localscout.app.debug`