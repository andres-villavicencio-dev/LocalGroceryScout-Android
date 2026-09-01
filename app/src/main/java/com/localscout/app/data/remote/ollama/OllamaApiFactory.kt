package com.localscout.app.data.remote.ollama

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a per-host Retrofit instance. We can't use a single shared baseUrl
 * because the user can change the ollama endpoint in Settings at runtime.
 */
@Singleton
class OllamaApiFactory @Inject constructor() {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        // CRITICAL: without this, kotlinx-serialization silently omits every
        // field whose value equals its default (stream=false, format="json",
        // options). Ollama then treats the missing `stream` as stream=true and
        // replies with NDJSON — a stream of JSON objects the app can't parse.
        // Root cause of the "Unexpected JSON token at offset 144" errors.
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)        // local models can be slow
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(
            // BODY level: logs full request + response bodies to logcat.
            // Essential for debugging the ollama JSON parse issue; the response
            // is only a few KB so logcat's 4KB-per-line limit is fine.
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        )
        .build()

    fun apiFor(host: String): OllamaApi {
        val base = host.trim().trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OllamaApi::class.java)
    }
}
