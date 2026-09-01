package com.localscout.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localscout.app.data.location.LocationRepository
import com.localscout.app.data.remote.ollama.OllamaRepository
import com.localscout.app.data.settings.SettingsRepository
import com.localscout.app.domain.model.GeoLocation
import com.localscout.app.domain.model.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
 * second while we're waiting on ollama so users can see the call is alive (an
 * ollama chat round-trip on a laptop GPU takes 30-120s). [thinkingPhase] cycles
 * 0..5 so we can swap the witty status text every few seconds without each
 * composable doing its own timer.
 */
data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val elapsedSeconds: Int = 0,
    val thinkingPhase: Int = 0,
    val result: SearchResult? = null,
    val error: String? = null,
    val modelName: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val ollama: OllamaRepository,
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
            )

            // Ticker: elapsed time + rotating phase index.
            // Two timers running side-by-side so elapsed updates every second
            // but the phase message rotates every ~5s (less distracting).
            tickerJob = launch {
                var elapsed = 0
                while (isActive) {
                    delay(1000)
                    elapsed += 1
                    // Phase advances every 5s; cycle through 6 messages.
                    val newPhase = (elapsed / 5) % 6
                    _state.value = _state.value.copy(
                        elapsedSeconds = elapsed,
                        thinkingPhase = newPhase,
                    )
                }
            }

            val cfg = settings.ollamaSettings.first()
            val loc = location.currentLocation()
                ?: GeoLocation(latitude = -36.8485, longitude = 174.7633)  // Auckland fallback
            val region = "Auckland, New Zealand"

            ollama.searchPrices(query, loc, region)
                .onSuccess { res ->
                    _state.value = _state.value.copy(
                        isSearching = false,
                        result = res,
                        modelName = cfg.model,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isSearching = false,
                        error = e.message ?: "Unknown error",
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
