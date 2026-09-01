package com.localscout.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localscout.app.data.remote.ollama.OllamaApiFactory
import com.localscout.app.data.remote.ollama.OllamaRepository
import com.localscout.app.data.settings.OllamaSettings
import com.localscout.app.data.settings.ScraperSettings
import com.localscout.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Success(val modelCount: Int) : TestState
    data class Failure(val error: String) : TestState
}

sealed interface ScraperTestState {
    data object Idle : ScraperTestState
    data object Testing : ScraperTestState
    data class Success(val stats: String) : ScraperTestState
    data class Failure(val error: String) : ScraperTestState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val ollamaFactory: OllamaApiFactory,
    private val scraperRepository: com.localscout.app.data.remote.scraper.ScraperRepository,
) : ViewModel() {

    val settings: StateFlow<OllamaSettings> = MutableStateFlow(
        OllamaSettings(host = "", model = "")
    ).also { flow ->
        viewModelScope.launch {
            settingsRepo.ollamaSettings.collect { flow.value = it }
        }
    }.asStateFlow()

    val scraperSettings: StateFlow<ScraperSettings> = MutableStateFlow(
        ScraperSettings(host = "", enabled = true)
    ).also { flow ->
        viewModelScope.launch {
            settingsRepo.scraperSettings.collect { flow.value = it }
        }
    }.asStateFlow()

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    private val _scraperTestState = MutableStateFlow<ScraperTestState>(ScraperTestState.Idle)
    val scraperTestState: StateFlow<ScraperTestState> = _scraperTestState.asStateFlow()

    suspend fun save(host: String, model: String) {
        settingsRepo.setOllamaHost(host)
        settingsRepo.setOllamaModel(model)
    }

    suspend fun saveScraper(host: String, enabled: Boolean) {
        settingsRepo.setScraperHost(host)
        settingsRepo.setScraperEnabled(enabled)
    }

    fun testConnection(host: String) {
        viewModelScope.launch {
            _testState.value = TestState.Testing
            runCatching {
                ollamaFactory.apiFor(host).tags().models.size
            }.onSuccess { count ->
                _testState.value = TestState.Success(count)
            }.onFailure { e ->
                _testState.value = TestState.Failure(e.message ?: "unknown error")
            }
        }
    }

    /** Pings the scraper service /health and reports DB stats. */
    fun testScraper(host: String) {
        viewModelScope.launch {
            _scraperTestState.value = ScraperTestState.Testing
            runCatching {
                scraperRepository.health(host)
            }.onSuccess { stats ->
                _scraperTestState.value = ScraperTestState.Success(stats)
            }.onFailure { e ->
                _scraperTestState.value = ScraperTestState.Failure(e.message ?: "unknown error")
            }
        }
    }
}