package com.localscout.app.ui.screens.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localscout.app.data.remote.scraper.ReceiptScanResponse
import com.localscout.app.data.remote.scraper.ScraperRepository
import com.localscout.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** Lifecycle of a receipt scan. */
sealed interface ReceiptUiState {
    data object Idle : ReceiptUiState
    data object Uploading : ReceiptUiState
    data object Structuring : ReceiptUiState     // OCR + LLM structuring
    data object Pricing : ReceiptUiState         // per-item cheapest pricing
    data class Result(val receipt: ReceiptScanResponse) : ReceiptUiState
    data class Error(val message: String, val canRetry: Boolean = true) : ReceiptUiState
}

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    private val scraper: ScraperRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ReceiptUiState>(ReceiptUiState.Idle)
    val state: StateFlow<ReceiptUiState> = _state.asStateFlow()

    fun reset() {
        _state.value = ReceiptUiState.Idle
    }

    /**
     * Compress the chosen photo to what the OCR needs (long edge <=1600px,
     * JPEG q82 — thermal receipts carry no more detail) and upload.
     */
    fun scanReceipt(context: Context, uri: Uri) {
        _state.value = ReceiptUiState.Uploading
        viewModelScope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) {
                    compressForOcr(context, uri)
                } ?: run {
                    _state.value = ReceiptUiState.Error("Couldn't read that image")
                    return@launch
                }
                _state.value = ReceiptUiState.Structuring
                val host = settings.scraperSettings.first().host
                val response = withContext(Dispatchers.IO) {
                    scraper.scanReceipt(host, jpeg)
                }
                _state.value = ReceiptUiState.Pricing
                delay(400)  // let the pricing frame paint before results swap in
                if (response.items.isEmpty()) {
                    _state.value = ReceiptUiState.Error(
                        "No receipt detected in that photo. Try again with the whole receipt in frame, good light, laid flat.",
                    )
                } else {
                    _state.value = ReceiptUiState.Result(response)
                }
            } catch (e: Exception) {
                _state.value = ReceiptUiState.Error(
                    e.message ?: "Receipt scan failed",
                )
            }
        }
    }
}

/**
 * Downscale + re-encode; null when the URI can't be decoded.
 *
 * Two-step decode with subsampling: camera originals are 12-48MP and a naive
 * decode allocates a 50-200MB bitmap — instant OOM on older phones (and
 * OutOfMemoryError is an Error, not an Exception, so it bypasses naive
 * catch blocks and kills the app).
 */
fun compressForOcr(context: Context, uri: Uri): ByteArray? {
    return try {
    val input = context.contentResolver.openInputStream(uri) ?: return null
    val raw = input.use { it.readBytes() }

    // Pass 1: bounds only (cheap)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // Pass 2: subsample so the decoded bitmap is <= ~1600px long edge
    val sample = maxOf(
        1,
        minOf(bounds.outWidth, bounds.outHeight) / 1600,
    ).coerceAtLeast(1)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565   // half the RAM of ARGB_8888
    }
    var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
    val longEdge = maxOf(bmp.width, bmp.height)
    if (longEdge > 1600) {
        val scale = 1600f / longEdge
        bmp = Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true,
        )
    }
    ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        out.toByteArray()
    }
    } catch (t: Throwable) {
        // Throwable on purpose: OutOfMemoryError from a huge photo must not
        // kill the app — degrade to "couldn't read image" instead.
        null
    }
}
