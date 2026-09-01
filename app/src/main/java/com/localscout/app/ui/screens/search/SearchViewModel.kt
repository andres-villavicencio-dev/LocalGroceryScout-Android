package com.localscout.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localscout.app.data.location.LocationRepository
import com.localscout.app.data.remote.ollama.OllamaRepository
import com.localscout.app.data.remote.scraper.ScraperRepository
import com.localscout.app.data.settings.SettingsRepository
import com.localscout.app.domain.model.GeoLocation
import com.localscout.app.domain.model.ParsedPrice
import com.localscout.app.domain.model.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Search screen.
 *
 * [isSearching] gates the thinking indicator; [elapsedSeconds] ticks up once per
 * second while we're waiting on the backend(s) so users can see the call is
 * alive (a live browser-agent scrape takes 30-90s). [thinkingPhase] cycles
 * 0..5 so we can swap the witty status text every few seconds.
 *
 * [dataSource] records how the current result was produced:
 *  "scraper"  — real prices scraped from the chains' online shops
 *  "ollama"   — LLM estimates (scraper disabled/unreachable/empty)
 *  "merged"   — both sources contributed
 */
data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val elapsedSeconds: Int = 0,
    val thinkingPhase: Int = 0,
    val result: SearchResult? = null,
    val error: String? = null,
    val modelName: String? = null,
    val dataSource: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val ollama: OllamaRepository,
    private val scraper: ScraperRepository,
    private val location: LocationRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var tickerJob: Job? = null

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return

        // Cancel any previous in-flight ticker before starting a new search.
        tickerJob?.cancel()

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isSearching = true,
                error = null,
                elapsedSeconds = 0,
                thinkingPhase = 0,
                result = null,
                dataSource = null,
            )

            // Ticker: elapsed time + rotating phase index.
            tickerJob = launch {
                var elapsed = 0
                while (isActive) {
                    delay(1000)
                    elapsed += 1
                    val newPhase = (elapsed / 5) % 6
                    _state.value = _state.value.copy(
                        elapsedSeconds = elapsed,
                        thinkingPhase = newPhase,
                    )
                }
            }

            val ollamaCfg = settings.ollamaSettings.first()
            val scraperCfg = settings.scraperSettings.first()
            val loc = location.currentLocation()
                ?: GeoLocation(latitude = -36.8485, longitude = 174.7633)  // Auckland fallback
            val region = "Auckland, New Zealand"

            // ---- Strategy: scraper first (real prices), ollama as fallback ----
            // Both can be slow; kick off ollama only if the scraper fails or
            // comes back empty. A cached scraper hit returns in <1s; a live
            // scrape drives real browsers for 30-90s.
            var finalResult: SearchResult? = null
            var source: String? = null
            var lastError: String? = null

            if (scraperCfg.enabled) {
                val scraped = scraper.searchPrices(scraperCfg.host, query, loc, region)
                scraped.onSuccess { res ->
                    if (res.results.isNotEmpty()) {
                        finalResult = res
                        source = "scraper"
                    }
                }
                scraped.onFailure { e ->
                    // Remember but don't surface yet — ollama may still deliver.
                    lastError = "scraper: ${e.message ?: "unreachable"}"
                }
            }

            if (finalResult == null) {
                // Scraper disabled/failed/empty → ollama estimates.
                val ollamaDeferred = async {
                    ollama.searchPrices(query, loc, region)
                }
                ollamaDeferred.await()
                    .onSuccess { res ->
                        finalResult = res
                        source = "ollama"
                    }
                    .onFailure { e ->
                        lastError = (if (lastError != null) "$lastError; " else "") +
                                "ollama: ${e.message ?: "unreachable"}"
                    }
            }

            finalResult?.let { res ->
                _state.value = _state.value.copy(
                    isSearching = false,
                    result = res,
                    // modelName shows the ollama model for estimates; the
                    // scraper's provenance rides in modelUsed ("scraper (live)").
                    modelName = res.modelUsed,
                    dataSource = source,
                )
            } ?: run {
                _state.value = _state.value.copy(
                    isSearching = false,
                    error = lastError ?: "No results from any source",
                )
            }

            // Stop the ticker regardless of outcome.
            tickerJob?.cancel()
            tickerJob = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
    }
}