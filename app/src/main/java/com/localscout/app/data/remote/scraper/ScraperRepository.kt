package com.localscout.app.data.remote.scraper

import com.localscout.app.domain.model.GeoLocation
import com.localscout.app.domain.model.ParsedPrice
import com.localscout.app.domain.model.SearchResult
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the browser-agent scraper service (scraper/api.py).
 *
 * Unlike the ollama path, these prices are REAL — scraped from the chains'
 * online shops. The service handles its own caching (3-day freshness window),
 * so a repeat search is ~0s.
 *
 * Callers get the same Result<SearchResult> shape as OllamaRepository so the
 * ViewModel can merge the two sources interchangeably.
 */
@Singleton
class ScraperRepository @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true     // we SEND requests too; keep defaults on the wire
    }

    // Longer read timeout than ollama: a live agent run drives a real browser
    // through two supermarket sites and can take 90s+.
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)     // fail fast if the host is down
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private var cachedApi: Pair<String, ScraperApi>? = null

    private fun apiFor(host: String): ScraperApi {
        cachedApi?.let { (h, api) -> if (h == host) return api }
        val base = host.trim().trimEnd('/') + "/"
        val api = Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ScraperApi::class.java)
        cachedApi = host to api
        return api
    }

    /**
     * Quick health probe: returns "ok — stores X · prices Y · fresh Zs" or throws.
     * Used by the Settings screen test button.
     */
    suspend fun health(host: String): String {
        val h = apiFor(host).health()
        val s = h.stats
        val stores = s["stores"]?.toInt() ?: 0
        val prices = s["prices"]?.toInt() ?: 0
        val freshDays = (h.fresh_window_s / 86400.0).toInt()
        return "ok — $stores stores · $prices prices cached · fresh for ${freshDays}d"
    }

    /**
     * Price history time series for charting. Returns every scrape of every
     * matching product, grouped per store chain. Fails fast (8s connect
     * timeout) — the History screen shows an offline message on failure.
     */
    suspend fun priceHistory(
        host: String,
        query: String,
        days: Int = 90,
    ): Result<HistoryResponse> = runCatching {
        apiFor(host).history(HistoryRequest(query = query, days = days))
    }

    /**
     * Cross-chain "also available at" comparison. Given an exact product name
     * (the one the user picked / is viewing), returns the SAME product sold at
     * OTHER chains — exact-name twins plus LLM-verified reworded matches — each
     * with its price, so the UI can show "also at Store X for $Y (+$Z)".
     * Fails soft: an empty list on any error (the section just hides).
     */
    suspend fun compareProduct(
        host: String,
        productName: String,
    ): List<ParsedPrice> = runCatching {
        apiFor(host).compare(CompareRequest(productName = productName)).matches.map {
            ParsedPrice(
                store = it.store,
                storeChain = it.storeChain,
                price = it.price,
                currency = it.currency,
                unit = it.unit,
                address = it.address,
                distanceKm = it.distanceKm,
                confidence = it.confidence,
                reasoning = it.reasoning,
                productName = it.productName,
                imageUrl = it.imageUrl,
            )
        }.sortedBy { it.price }
    }.getOrDefault(emptyList())

    /**
     * Upload a receipt photo for OCR + per-item cheapest pricing.
     * Separate client: uploads take a while (60-90s server pipeline) and the
     * payload is a few hundred KB.
     */
    suspend fun scanReceipt(host: String, jpeg: ByteArray): ReceiptScanResponse {
        val base = host.trim().trimEnd('/') + "/"
        val uploadClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        val api = Retrofit.Builder()
            .baseUrl(base)
            .client(uploadClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ScraperApi::class.java)
        val part = MultipartBody.Part.createFormData(
            "file", "receipt.jpg",
            jpeg.toRequestBody("image/jpeg".toMediaType()),
        )
        return api.scanReceipt(part)
    }

    /**
     * Query the scraper service. Returns real prices when the service is
     * reachable and has coverage for the query; a failure Result otherwise
     * (the ViewModel falls back to ollama estimates).
     *
     * The returned SearchResult.modelUsed records the provenance:
     * "scraper (cache)" or "scraper (live)".
     */
    suspend fun searchPrices(
        host: String,
        query: String,
        location: GeoLocation,
        region: String,
    ): Result<SearchResult> = runCatching {
        val api = apiFor(host)
        val resp = api.search(
            ScraperSearchRequest(
                query = query,
                lat = location.latitude,
                lng = location.longitude,
                region = if (region.contains("New Zealand", ignoreCase = true) || region.contains("NZ", true)) "NZ" else region,
            )
        )
        val results = resp.results.map {
            // Provenance marker: every row from this service is a real scraped
            // price (the UI's SCOUTED badge keys off "scrap" in reasoning).
            // The API's own reasoning may be the LLM matcher's note or
            // "cached scrape" — neither reliably contains the marker, so we
            // stamp it here at the repository boundary.
            val provenance = "scraped from ${it.storeChain ?: it.store}'s online shop"
            ParsedPrice(
                store = it.store,
                storeChain = it.storeChain,
                price = it.price,
                currency = it.currency,
                unit = it.unit,
                address = it.address,
                distanceKm = it.distanceKm,
                confidence = it.confidence,
                reasoning = if (it.reasoning.isNullOrBlank()) provenance
                           else "${it.reasoning} · $provenance",
                productName = it.productName,
                imageUrl = it.imageUrl,
            )
        }.sortedBy { it.price }
        SearchResult(
            query = resp.query,
            productName = resp.productName.ifBlank { query },
            results = results,
            summary = resp.summary,
            modelUsed = if (resp.source == "cache") "scraper (cached)" else "scraper (live)",
            generatedAt = resp.generatedAt,
        )
    }
}