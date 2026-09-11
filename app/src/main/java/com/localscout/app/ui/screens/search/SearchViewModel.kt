package com.localscout.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localscout.app.data.location.LocationRepository
import com.localscout.app.data.remote.ollama.OllamaRepository
import com.localscout.app.data.remote.scraper.LatestItem
import com.localscout.app.data.remote.scraper.ScraperRepository
import com.localscout.app.data.settings.SettingsRepository
import com.localscout.app.data.shoppinglist.ShoppingListRepository
import com.localscout.app.domain.model.GeoLocation
import com.localscout.app.domain.model.ParsedPrice
import com.localscout.app.domain.model.SearchResult
import com.localscout.app.domain.model.ShoppingList
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
    /** Scraper failed/empty → honest no-data state with an explicit
     *  "show rough estimates" action. Estimates are NEVER shown silently. */
    val estimatesAvailable: Boolean = false,
    /** One row per distinct product: name, cheapest price, store count. */
    val productOptions: List<ProductOption> = emptyList(),
    /** The product the user picked; null while the picker is showing. */
    val selectedProduct: String? = null,
    /** Cross-chain "also available at" matches for the selected product. */
    val compareMatches: List<com.localscout.app.domain.model.ParsedPrice> = emptyList(),
    /** True while the /compare call is in flight for the selected product. */
    val isComparing: Boolean = false,
    /** Reverse-geocoded area label, e.g. "Ponsonby, Auckland" (null = hidden). */
    val detectedAddress: String? = null,
    /** Local lists for the add-to-list picker (names + ids only). */
    val listChoices: List<ShoppingList> = emptyList(),
    /** Product awaiting a list choice; non-null shows the add-to-list dialog. */
    val pendingAddToList: ProductOption? = null,
    /** Home-screen 'fresh finds' — newest scouted products, cheapest price each. */
    val latestFinds: List<LatestItem> = emptyList(),
    /** True while the /latest feed loads; null-handled silently on failure. */
    val isLoadingFinds: Boolean = false,
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
    /** Pack size ("1L", "100pk", "500g") — null when unknown. */
    val unit: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val ollama: OllamaRepository,
    private val scraper: ScraperRepository,
    private val location: LocationRepository,
    private val settings: SettingsRepository,
    private val shoppingLists: ShoppingListRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var tickerJob: Job? = null

    init {
        // Resolve the user's area once at startup: GPS fix → reverse-geocode →
        // "Ponsonby, Auckland" under the title. Silent on any failure.
        viewModelScope.launch {
            val loc = location.currentLocation() ?: return@launch
            // Geocoder does disk/network I/O — keep it off the main thread.
            val label = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                location.addressLabel(loc)
            }
            if (!label.isNullOrBlank()) {
                _state.value = _state.value.copy(detectedAddress = label)
            }
        }
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    init {
        // Local lists for the add-to-list picker; refreshes on every change.
        viewModelScope.launch {
            shoppingLists.lists.collect { lists ->
                _state.value = _state.value.copy(listChoices = lists)
            }
        }
    }

    init {
        // Home-screen 'fresh finds': newest scouted products at their cheapest
        // current price. Passive value — loads once at startup, never blocks
        // search, fails silent (feed just doesn't show).
        viewModelScope.launch {
            val cfg = settings.scraperSettings.first()
            if (!cfg.enabled) return@launch
            _state.value = _state.value.copy(isLoadingFinds = true)
            val finds = scraper.latest(cfg.host)
            _state.value = _state.value.copy(
                latestFinds = finds?.items.orEmpty(),
                isLoadingFinds = false,
            )
        }
    }

    /** Card tapped on the feed: pre-fill the query and scout that product. */
    fun onFindTapped(item: LatestItem) {
        _state.value = _state.value.copy(query = item.productName)
        search()
    }

    /** User tapped "add to list" on a picker card — stash it, show the dialog. */
    fun requestAddToList(option: ProductOption) {
        _state.value = _state.value.copy(pendingAddToList = option)
    }

    fun dismissAddToList() {
        _state.value = _state.value.copy(pendingAddToList = null)
    }

    /** Create a new list, then add the pending product to it. */
    fun createListAndAdd(name: String) {
        viewModelScope.launch {
            val listId = shoppingLists.createList(name)
            confirmAddToList(listId)
        }
    }

    /**
     * Adds the pending product to the chosen list, carrying the product's
     * cheapest SCOUTED row as bestPrice (estimate rows are excluded — a list
     * price must be real). Falls back to the query text when the option has
     * no concrete product name (single anonymous ollama results).
     */
    fun confirmAddToList(listId: String) {
        val pending = _state.value.pendingAddToList ?: return
        viewModelScope.launch {
            // Which scouted row? Re-derive from the current result: the option
            // carries only the cheapest price, not the full row set.
            val rows = _state.value.result?.results
                ?.filter { (it.productName ?: _state.value.query) == pending.productName }
                .orEmpty()
            val cheapestScouted = rows
                .filter { it.reasoning?.contains("scrap", ignoreCase = true) == true }
                .minByOrNull { it.price }
            shoppingLists.addItem(
                listId = listId,
                name = pending.productName,
                quantity = 1,
                price = cheapestScouted,
            )
            _state.value = _state.value.copy(pendingAddToList = null)
        }
    }

    fun search() = launchSearch(forceEstimates = false)

    /** Explicit opt-in from the honest no-data card: ollama-guessed prices. */
    fun showEstimates() = launchSearch(forceEstimates = true)

    private fun launchSearch(forceEstimates: Boolean) {
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
                estimatesAvailable = false,
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

            // ---- Strategy: scraper first; estimates are OPT-IN, never silent ----
            // Old behavior: scraper failed → ollama quietly invented prices and
            // they rendered like any other result. A price tool must never pass
            // guesses off as data. Now: scraper fails/empty → honest no-data
            // state with an explicit "Show rough estimates" action. Estimates
            // also run directly when the scraper is disabled in Settings —
            // that IS the user's chosen mode. A cached scraper hit returns in
            // <1s; a live scrape drives real browsers for 30-90s.
            var finalResult: SearchResult? = null
            var source: String? = null
            var lastError: String? = null

            if (scraperCfg.enabled && !forceEstimates) {
                val scraped = scraper.searchPrices(scraperCfg.host, query, loc, region)
                scraped.onSuccess { res ->
                    if (res.results.isNotEmpty()) {
                        finalResult = res
                        source = "scraper"
                    }
                }
                scraped.onFailure { e ->
                    lastError = "scraper: ${e.message ?: "unreachable"}"
                }
            }

            // Estimates run ONLY on explicit request, or in estimate mode
            // (scraper off in Settings — the user's own configuration).
            val wantEstimates = forceEstimates || !scraperCfg.enabled
            if (finalResult == null && wantEstimates) {
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

            if (finalResult == null) {
                // Honest no-data state — stop here, render nothing guessed.
                tickerJob?.cancel()
                tickerJob = null
                _state.value = _state.value.copy(
                    isSearching = false,
                    error = if (forceEstimates)
                        "Estimates unavailable — ${lastError ?: "ollama unreachable"}"
                    else
                        "No live price data (${lastError ?: "scraper had no prices for this"}). " +
                            "Nothing is shown rather than guessed.",
                    estimatesAvailable = !forceEstimates,
                )
                return@launch
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
                            // Pack size from the cheapest row (rows in a group
                            // share it); blank when the scraper had none.
                            unit = rows.firstNotNullOfOrNull { it.unit }?.takeIf { it.isNotBlank() },
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