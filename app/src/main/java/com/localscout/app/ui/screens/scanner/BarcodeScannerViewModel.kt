package com.localscout.app.ui.screens.scanner

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BarcodeScannerViewModel @Inject constructor() : ViewModel() {
    fun onBarcode(code: String, onResult: (String) -> Unit) {
        onResult(code)
    }
}
