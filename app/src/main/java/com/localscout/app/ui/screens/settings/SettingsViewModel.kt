package com.localscout.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localscout.app.data.remote.ollama.OllamaApiFactory
import com.localscout.app.data.remote.ollama.OllamaRepository
import com.localscout.app.data.settings.OllamaSettings
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val ollamaFactory: OllamaApiFactory,
) : ViewModel() {

    val settings: StateFlow<OllamaSettings> = MutableStateFlow(
        OllamaSettings(host = "", model = "")
    ).also { flow ->
        viewModelScope.launch {
            settingsRepo.ollamaSettings.collect { flow.value = it }
        }
    }.asStateFlow()

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    suspend fun save(host: String, model: String) {
        settingsRepo.setOllamaHost(host)
        settingsRepo.setOllamaModel(model)
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
}
