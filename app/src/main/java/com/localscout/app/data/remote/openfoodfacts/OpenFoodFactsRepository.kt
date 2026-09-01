package com.localscout.app.data.remote.openfoodfacts

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort product-name lookup for scanned barcodes.
 * Open Food Facts is free, no key required, returns the canonical name + brand.
 * Returns null if the product is unknown.
 */
@Singleton
class OpenFoodFactsRepository @Inject constructor(
    private val api: OpenFoodFactsApi,
) {
    suspend fun lookup(barcode: String): String? {
        return runCatching {
            val r = api.lookup(barcode)
            if (r.status != 1) return@runCatching null
            val p = r.product ?: return@runCatching null
            val brand = p.brands?.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""
            val name = p.productName?.takeIf { it.isNotBlank() } ?: return@runCatching null
            (brand + name).trim()
        }.getOrNull()
    }
}
