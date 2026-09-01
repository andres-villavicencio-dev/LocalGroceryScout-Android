# Local Grocery Scout — Android (Native)

A native Android rewrite of [Local Grocery Scout](https://github.com/andres-villavicencio-dev/LocalGroceryScout)
(React/TypeScript web SPA), built with:

- **Kotlin 2.1.0** + **Jetpack Compose** + **Material 3** (dynamic color)
- **Hilt** for dependency injection
- **Retrofit + OkHttp + kotlinx.serialization** for ollama + Open Food Facts APIs
- **CameraX + ML Kit barcode scanning** (native)
- **Fused Location Provider** for geolocation
- **DataStore** for settings persistence
- **Vico** for price-history charts (TBD)

## Architecture

```
app/src/main/java/com/localscout/app/
├── MainActivity.kt          # Hosts the Compose tree + network-state gate
├── LocalGroceryScoutApp.kt  # @HiltAndroidApp Application
├── domain/model/            # Models.kt — ParsedPrice, SearchResult, ShoppingList…
├── data/
│   ├── settings/            # SettingsRepository (DataStore: ollama host/model)
│   ├── remote/
│   │   ├── ollama/          # OllamaApi, OllamaRepository, OllamaPrompts
│   │   └── openfoodfacts/   # Barcode lookup
│   └── location/            # Fused Location Provider wrapper
├── di/
│   ├── AppModule.kt         # SettingsRepository
│   └── NetworkModule.kt     # OkHttp, Json, OpenFoodFactsApi
└── ui/
    ├── theme/               # Theme.kt (Material 3 + dynamic color), Type.kt
    ├── navigation/          # Top-level destinations + bottom nav
    ├── connectivity/        # NetworkStateViewModel + NoNetworkScreen
    └── screens/
        ├── search/          # SearchScreen + SearchViewModel + results UI
        ├── lists/           # ListsScreen (Firebase stub)
        ├── history/         # HistoryScreen (chart placeholder)
        ├── account/         # AccountScreen (auth/Pro stubs)
        ├── settings/        # SettingsScreen + ViewModel (ollama host/model)
        └── scanner/         # BarcodeScannerScreen + BarcodeAnalyzer
```

## What works in this build

- ✅ Bottom-nav scaffold with 4 destinations (Search, Lists, History, Account)
- ✅ Settings screen for ollama host:port + model + connection test
- ✅ Real ollama calls via Retrofit with strict-JSON prompt
- ✅ Search → ollama → parsed results UI with confidence chips
- ✅ CameraX + ML Kit barcode scanner
- ✅ Fused Location (with graceful Auckland fallback if permission denied)
- ✅ Network-state check + friendly offline retry screen
- ✅ Open Food Facts barcode name lookup
- ✅ Material 3 dynamic color (Android 12+)

## What's stubbed (waiting on keys)

- 🚧 Firebase Auth — UI shows "Sign in with Google" but does nothing
- 🚧 Firestore — Lists/History screens are empty placeholders
- � Stripe / Pro membership — Account shows "Pro coming soon"
- 🚧 Vico chart — not yet wired up in HistoryScreen

## Building

```bash
# From this directory:
./gradlew assembleDebug

# APK lands at:
# app/build/outputs/apk/debug/app-debug.apk

# Install + run on a connected device or emulator:
./gradlew installDebug
adb shell am start -n com.localscout.app.debug/com.localscout.app.MainActivity
```

### Verifying in the local emulator

If you want to smoke-test in the bundled `Pixel_8_API_34_2` AVD:

```bash
# The agent's `andus` user needs to be in the `kvm` group (one-time setup):
sudo gpasswd -a $USER kvm && newgrp kvm

# Boot with KVM acceleration + 3 GB RAM (default 2 GB is too tight):
sg kvm -c "~/Android/Sdk/emulator/emulator \
  -avd Pixel_8_API_34_2 -no-window -no-audio -no-boot-anim \
  -no-snapshot-load -gpu swiftshader_indirect -memory 3072 -cores 4"

# Once booted, enable Wi-Fi (auto-connects to AndroidWifi SSID):
adb shell svc wifi enable
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.localscout.app.debug/com.localscout.app.MainActivity
```

⚠️ The headless software-GL emulator is **brutally slow** for Compose UIs
(30–60 s first frame, lots of `Skipped frames` warnings). It works for
"does the screen look right" smoke tests, but for end-to-end interactive
verification (tapping, typing, ollama search roundtrip), **use a real USB
phone** — `adb install` + manual tap is faster than fighting emulator
input latency.

## ollama setup

This app does **not** ship with an LLM. It talks to your laptop/RPi's ollama
instance over the local network. Default endpoint is `http://192.168.1.72:11434`
(change in Settings → Ollama host).

Recommended models (in order of preference for this app):

| Model | Size | Notes |
|-------|------|-------|
| `gemma4:e4b` | 9.6 GB | **Default** — tested winner (see `workspace/prompts/RESULTS.md`) |
| `gemma4:26b` | 17 GB | Best quality, slow on integrated GPU |
| `qwen3:30b-a3b` | 18 GB | Strong alternative, MoE |

See `workspace/prompts/price-search-system.md` for the system prompt and
`workspace/prompts/test_prompt.py` for a comparison harness.

## Security notes

- Cleartext HTTP is whitelisted **only** for private LAN ranges
  (10.0.0.0/8, 192.168.0.0/16, localhost, 10.0.2.2 emulator host loopback).
- `INTERNET` and `ACCESS_NETWORK_STATE` permissions required.
- `CAMERA` and `ACCESS_*_LOCATION` requested at runtime only when needed.
- No telemetry, no analytics, no third-party SDKs beyond what Firebase/Stripe
  will add later.

## License

MIT (same as the upstream web app).
