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
 * Two-step search flow: after a search resolves, [productOptions] groups the
 * result rows by concrete product (e.g. "Anchor Blue Milk 2L" vs "Milk Powder").
 * The picker UI shows these options; [selectedProduct] is set when the user
 * taps one, and [result] is filtered to that product's price rows sorted
 * cheapest-first (the "cheapest option" view). Clearing the selection returns
 * to the picker.
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
    /** One row per distinct product: name, cheapest price, store count. */
    val productOptions: List<ProductOption> = emptyList(),
    /** The product the user picked; null while the picker is showing. */
    val selectedProduct: String? = null,
    /** Cross-chain "also available at" matches for the selected product. */
    val compareMatches: List<com.localscout.app.domain.model.ParsedPrice> = emptyList(),
    /** True while the /compare call is in flight for the selected product. */
    val isComparing: Boolean = false,
) {
    /** Rows for the selected product, sorted cheapest first. */
    val productRows: List<com.localscout.app.domain.model.ParsedPrice>
        get() = result?.results
            ?.filter { it.productName == selectedProduct }
            ?.sortedBy { it.price }
            ?: emptyList()

    /**
     * Other shops selling the SAME product, EXCLUDING chains already shown in
     * [productRows], cheapest first. This is the "also available at (costs more)"
     * list — merged from the /compare endpoint's cross-chain matches.
     */
    val otherShops: List<com.localscout.app.domain.model.ParsedPrice>
        get() {
            val shown = productRows.mapNotNull { it.storeChain ?: it.store }.toSet()
            return compareMatches
                .filter { (it.storeChain ?: it.store) !in shown }
                .distinctBy { it.storeChain ?: it.store }
                .sortedBy { it.price }
        }
}

/** A distinct product among the search results, for the disambiguation grid. */
data class ProductOption(
    val productName: String,
    val cheapestPrice: Double,
    val currency: String,
    val storeCount: Int,
    val bestStore: String,
    val isScouted: Boolean,
    /** Product thumbnail URL (Foodstuffs CDN), shared across the group's rows. */
    val imageUrl: String? = null,
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
                // Group rows by concrete product → picker options, cheapest first.
                val options = res.results
                    .groupBy { it.productName ?: res.productName.ifBlank { it.store } }
                    .map { (name, rows) ->
                        val cheapest = rows.minByOrNull { it.price } ?: rows.first()
                        ProductOption(
                            productName = name,
                            cheapestPrice = cheapest.price,
                            currency = cheapest.currency,
                            storeCount = rows.distinctBy { it.store }.size,
                            bestStore = cheapest.store,
                            isScouted = rows.any { r ->
                                r.reasoning?.contains("scrap", ignoreCase = true) == true
                            },
                            // First row with an image wins; Foodstuffs CDN thumbs
                            imageUrl = rows.firstNotNullOfOrNull { it.imageUrl },
                        )
                    }
                    .sortedBy { it.cheapestPrice }

                _state.value = _state.value.copy(
                    isSearching = false,
                    result = res,
                    // modelName shows the ollama model for estimates; the
                    // scraper's provenance rides in modelUsed ("scraper (live)").
                    modelName = res.modelUsed,
                    dataSource = source,
                    productOptions = options,
                    // Auto-select when the search unambiguously matched ONE
                    // product — the user typed "milo cereal", they meant Milo.
                    selectedProduct = if (options.size == 1) options.first().productName else null,
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

    /** User tapped a product option — show its cheapest-across-stores rows. */
    fun selectProduct(name: String) {
        _state.value = _state.value.copy(
            selectedProduct = name,
            compareMatches = emptyList(),
            isComparing = true,
        )
        // Fetch cross-chain "also available at" matches in the background.
        viewModelScope.launch {
            val scraperCfg = settings.scraperSettings.first()
            if (!scraperCfg.enabled) {
                _state.value = _state.value.copy(isComparing = false)
                return@launch
            }
            val matches = scraper.compareProduct(scraperCfg.host, name)
            // Guard against a race: user may have gone back / picked another.
            if (_state.value.selectedProduct == name) {
                _state.value = _state.value.copy(
                    compareMatches = matches,
                    isComparing = false,
                )
            }
        }
    }

    /** Back from the product view to the picker grid. */
    fun backToPicker() {
        _state.value = _state.value.copy(
            selectedProduct = null,
            compareMatches = emptyList(),
            isComparing = false,
        )
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
    }
}