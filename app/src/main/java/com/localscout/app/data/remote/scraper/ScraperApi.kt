package com.localscout.app.data.remote.scraper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Wire format for our own scraper service (scraper/api.py).
 *
 * POST /search with the query + coordinates; the service either serves
 * cached prices (fast) or runs the browser agent live (30-90s).
 */
@Serializable
data class ScraperSearchRequest(
    val query: String,
    val lat: Double,
    val lng: Double,
    val region: String = "NZ",
    val radius_m: Int = 6000,
    val force_refresh: Boolean = false,
)

@Serializable
data class ScraperResultItem(
    val store: String,
    @SerialName("storeChain") val storeChain: String? = null,
    val price: Double,
    val currency: String,
    val unit: String? = null,
    val address: String? = null,
    @SerialName("distanceKm") val distanceKm: Double? = null,
    val confidence: Double = 1.0,
    val reasoning: String? = null,
    val productName: String? = null,
    val url: String? = null,
    @SerialName("imageUrl") val imageUrl: String? = null,
)

@Serializable
data class ScraperSearchResponse(
    val query: String,
    val productName: String = "",
    val results: List<ScraperResultItem> = emptyList(),
    val summary: String = "",
    val source: String = "live-scrape",     // "cache" | "live-scrape"
    @SerialName("generatedAt") val generatedAt: String = "",
    val duration_s: Double = 0.0,
    val modelUsed: String? = null,          // absent from scraper; ollama only
)

interface ScraperApi {
    @POST("search")
    suspend fun search(@Body req: ScraperSearchRequest): ScraperSearchResponse

    @GET("health")
    suspend fun health(): ScraperHealth

    @POST("history")
    suspend fun history(@Body req: HistoryRequest): HistoryResponse
}

@Serializable
data class HistoryRequest(
    val query: String,
    val days: Int = 90,
    val store: String? = null,
)

@Serializable
data class ScraperHealth(
    val ok: Boolean = false,
    val stats: Map<String, Double> = emptyMap(),
    val fresh_window_s: Double = 0.0,
)

@Serializable
data class HistoryPoint(
    val t: Double,                       // unix seconds — the scrape date
    val date: String = "",               // "2026-09-01" for axis labels
    val price: Double,
    val currency: String = "NZD",
    val unit: String? = null,
    val unit_price: String? = null,
)

@Serializable
data class HistorySeries(
    val store: String,
    val product: String,
    val points: List<HistoryPoint> = emptyList(),
    val min: Double = 0.0,
    val max: Double = 0.0,
    val latest: Double = 0.0,
)

@Serializable
data class HistoryResponse(
    val query: String = "",
    val days: Int = 90,
    val series: List<HistorySeries> = emptyList(),
    val generatedAt: String = "",
)