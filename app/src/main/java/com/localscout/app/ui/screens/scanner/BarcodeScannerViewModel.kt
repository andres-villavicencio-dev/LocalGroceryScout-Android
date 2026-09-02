package com.localscout.app.ui.screens.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localscout.app.data.remote.openfoodfacts.OpenFoodFactsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Scanner state machine — the fix for "scan closes and nothing happens".
 *
 * States:
 *  SCANNING   camera live, waiting for a barcode
 *  LOOKING_UP  barcode captured, querying Open Food Facts for the product name
 *  CONFIRMED  product found — [productName] is ready; the UI shows a confirm
 *             sheet, and on acceptance the app searches prices for it
 *  UNKNOWN    barcode not in OFF's DB — surface the raw code so the user can
 *             type the name instead
 */
sealed interface ScannerUiState {
    data object Scanning : ScannerUiState
    data class LookingUp(val barcode: String) : ScannerUiState
    data class Confirmed(val barcode: String, val productName: String) : ScannerUiState
    data class Unknown(val barcode: String) : ScannerUiState
}

@HiltViewModel
class BarcodeScannerViewModel @Inject constructor(
    private val openFoodFacts: OpenFoodFactsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ScannerUiState>(ScannerUiState.Scanning)
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private var lastCode: String? = null   // debounce: one lookup per code

    /** A barcode was detected by the camera analyzer. */
    fun onBarcode(code: String) {
        if (code.isBlank() || code == lastCode) return
        if (_state.value != ScannerUiState.Scanning) return
        lastCode = code
        _state.value = ScannerUiState.LookingUp(code)
        viewModelScope.launch {
            val name = openFoodFacts.lookup(code)
            _state.value = if (name != null) {
                ScannerUiState.Confirmed(barcode = code, productName = name)
            } else {
                ScannerUiState.Unknown(barcode = code)
            }
        }
    }

    /** Dismiss the unknown-sheet: back to scanning (allow a different code). */
    fun retryScanning() {
        lastCode = null
        _state.value = ScannerUiState.Scanning
    }
}