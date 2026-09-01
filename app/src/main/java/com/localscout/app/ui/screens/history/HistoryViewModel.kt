package com.localscout.app.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localscout.app.data.remote.scraper.HistoryResponse
import com.localscout.app.data.remote.scraper.ScraperRepository
import com.localscout.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Price history over time, straight from the scraper's append-only
 * price_history table. Chart data flows in as one series per (store, product).
 */
data class HistoryUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val history: HistoryResponse? = null,
    val error: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val scraper: ScraperRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun load() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null, history = null)
            val cfg = settings.scraperSettings.first()
            scraper.priceHistory(cfg.host, query)
                .onSuccess { res ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        history = if (res.series.isEmpty()) null else res,
                        error = if (res.series.isEmpty())
                            "No price history for \"$query\" yet — search it once so the scout starts tracking!"
                        else null,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Scraper unreachable: ${e.message ?: "unknown"}",
                    )
                }
        }
    }
}