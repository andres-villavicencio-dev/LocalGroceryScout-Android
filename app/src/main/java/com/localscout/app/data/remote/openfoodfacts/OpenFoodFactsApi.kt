package com.localscout.app.data.remote.openfoodfacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

interface OpenFoodFactsApi {
    @GET("api/v0/product/{barcode}.json")
    suspend fun lookup(@Path("barcode") barcode: String): OpenFoodFactsResponse
}

@Serializable
data class OpenFoodFactsResponse(
    val status: Int = 0,
    val product: OpenFoodFactsProduct? = null,
)

@Serializable
data class OpenFoodFactsProduct(
    @SerialName("product_name") val productName: String? = null,
    val brands: String? = null,
)
