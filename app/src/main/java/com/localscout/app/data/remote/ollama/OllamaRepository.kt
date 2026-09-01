package com.localscout.app.data.remote.ollama

import com.localscout.app.data.settings.SettingsRepository
import com.localscout.app.domain.model.GeoLocation
import com.localscout.app.domain.model.ParsedPrice
import com.localscout.app.domain.model.SearchResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OllamaRepository @Inject constructor(
    private val apiFactory: OllamaApiFactory,
    private val settings: SettingsRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Returns the list of models installed on the configured ollama host. */
    suspend fun listModels(): Result<List<OllamaModel>> = runCatching {
        val cfg = settings.ollamaSettings.first()
        val api = apiFactory.apiFor(cfg.host)
        api.tags().models
    }

    /**
     * Performs a single-item grocery price search.
     * Returns a [SearchResult] parsed from the model's strict-JSON output, or
     * a failure with the underlying IOException / HTTP error.
     */
    suspend fun searchPrices(
        query: String,
        location: GeoLocation,
        region: String,
    ): Result<SearchResult> = runCatching {
        val cfg = settings.ollamaSettings.first()
        val api = apiFactory.apiFor(cfg.host)
        val response = api.chat(
            ChatRequest(
                model = cfg.model,
                messages = listOf(
                    ChatMessage("system", OllamaPrompts.SYSTEM),
                    ChatMessage(
                        "user",
                        OllamaPrompts.userMessage(query, location.latitude, location.longitude, region),
                    ),
                ),
            ),
        )
        parseSearchResult(response.message.content, cfg.model)
    }.recoverCatching { e ->
        // Wrap IO errors with a friendlier hint that points users to Settings.
        throw IOException(
            "Could not reach ollama at '${settings.ollamaSettings.first().host}'. " +
                "Check the host URL in Settings.\n\n${e.message}",
            e,
        )
    }

    private fun parseSearchResult(raw: String, model: String): SearchResult {
        // The model's `message.content` should be a JSON object, but defensive
        // parsing handles three real-world variants we've seen:
        //   1. Clean JSON: {"query": "...", "results": [...]}
        //   2. Markdown-wrapped: ```json\n{...}\n```
        //   3. Prefixed with prose: "Here you go:\n{...}"
        // In all cases, find the first '{' and the matching '}' (last char),
        // then parse that substring. This sidesteps model verbosity.
        val cleaned = sanitizeModelJson(raw)
        val root = json.parseToJsonElement(cleaned).jsonObject
        val query = root.stringOrNull("query") ?: ""
        val productName = root.stringOrNull("productName") ?: query
        val resultsArr = root["results"]?.jsonArray ?: emptyList()
        val results = resultsArr.mapNotNull { it.parsePriceOrNull() }
            .sortedBy { it.price }
        val summary = root.stringOrNull("summary") ?: ""
        return SearchResult(
            query = query,
            productName = productName,
            results = results,
            summary = summary,
            modelUsed = model,
            generatedAt = Instant.now().toString(),
        )
    }

    /**
     * Strips a model's pre/postamble around its JSON output.
     *
     * Strategy:
     *  - Drop everything before the first '{' or '['.
     *  - Walk braces to find the matching closing brace (or bracket).
     *  - Strip trailing text after that.
     *
     * Why: gemma4:e4b sometimes wraps JSON in markdown code fences or prefixes
     * it with reasoning prose, even when `format: "json"` is set. This lets
     * us recover the JSON regardless.
     */
    private fun sanitizeModelJson(raw: String): String {
        val trimmed = raw.trim()
        // Find the first JSON opener — either '{' or '['.
        val firstOpen = trimmed.indexOfFirst { it == '{' || it == '[' }
        if (firstOpen < 0) return trimmed
        val openChar = trimmed[firstOpen]
        val closeChar = if (openChar == '{') '}' else ']'
        // Walk forward, tracking nested braces / strings, until balanced.
        var depth = 0
        var inString = false
        var escape = false
        for (i in firstOpen until trimmed.length) {
            val c = trimmed[i]
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == openChar -> depth += 1
                !inString && c == closeChar -> {
                    depth -= 1
                    if (depth == 0) return trimmed.substring(firstOpen, i + 1)
                }
            }
        }
        // Unbalanced — return as-is and let the parser complain.
        return trimmed.substring(firstOpen)
    }

    private fun JsonElement.parsePriceOrNull(): ParsedPrice? {
        val obj = this as? JsonObject ?: return null
        val store = obj.stringOrNull("store") ?: return null
        val price = (obj["price"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNullSafe()
            ?: return null
        return ParsedPrice(
            store = store,
            storeChain = obj.stringOrNull("storeChain"),
            price = price,
            currency = obj.stringOrNull("currency") ?: "NZD",
            unit = obj.stringOrNull("unit"),
            address = obj.stringOrNull("address"),
            distanceKm = (obj["distanceKm"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNullSafe(),
            confidence = (obj["confidence"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNullSafe()
                ?: 0.5,
            reasoning = obj.stringOrNull("reasoning"),
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    private fun kotlinx.serialization.json.JsonPrimitive.doubleOrNullSafe(): Double? =
        try {
            double
        } catch (e: IllegalArgumentException) {
            null
        }
}
