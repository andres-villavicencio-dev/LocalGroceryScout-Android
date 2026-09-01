package com.localscout.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the web app's types.ts domain model, trimmed for the ollama path.
 * The web app uses Gemini + Google Search Grounding; we use ollama with realistic
 * estimates + a confidence score instead.
 */
@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class ParsedPrice(
    val store: String,
    val storeChain: String? = null,
    val price: Double,
    val currency: String,                 // "NZD" | "USD" | ...
    val unit: String? = null,             // "1L", "500g", "12ct", "each"
    val address: String? = null,
    val distanceKm: Double? = null,
    val confidence: Double,               // 0.0 .. 1.0
    val reasoning: String? = null,
)

@Serializable
data class SearchResult(
    val query: String,
    val productName: String,
    val results: List<ParsedPrice>,
    val summary: String,
    val modelUsed: String,                // which ollama model produced this
    val generatedAt: String,              // ISO-8601 timestamp
)

@Serializable
data class ShoppingListItem(
    val id: String,
    val name: String,
    val quantity: Int = 1,
    val addedAt: String,                  // ISO-8601
    val bestPrice: ParsedPrice? = null,
    val bestPriceSeenAt: String? = null,
)

@Serializable
data class ShoppingList(
    val id: String,
    val name: String,
    val items: List<ShoppingListItem> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PricePoint(
    val timestamp: String,                // ISO-8601
    val price: Double,
    val store: String,
    val currency: String,
)

@Serializable
data class ProductHistory(
    val productName: String,
    val history: List<PricePoint>,
)

@Serializable
data class User(
    val id: String,
    val email: String? = null,
    val displayName: String? = null,
    val isPro: Boolean = false,
    val subscriptionStatus: String? = null,
    val subscriptionEndDate: String? = null,
    val lastVerified: String? = null,
)

/** Application-level UI states. Mirrors the web app's AppState enum. */
enum class AppState {
    IDLE,
    LOCATING,
    READY,
    SEARCHING,
    RESULTS,
    ERROR,
    SCANNING,
    LISTS,
}
