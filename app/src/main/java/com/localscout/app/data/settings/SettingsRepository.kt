package com.localscout.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localscout.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "lgs_settings")

data class OllamaSettings(
    val host: String,
    val model: String,
)

data class ScraperSettings(
    val host: String,
    /** When true, the search flow hits the scraper service first and only
     *  falls back to ollama estimates if it fails/returns nothing. */
    val enabled: Boolean,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context,
) {
    private object Keys {
        val OllamaHost = stringPreferencesKey("ollama_host")
        val OllamaModel = stringPreferencesKey("ollama_model")
        val ScraperHost = stringPreferencesKey("scraper_host")
        val ScraperEnabled = booleanPreferencesKey("scraper_enabled")
    }

    val ollamaSettings: Flow<OllamaSettings> = context.dataStore.data.map { prefs ->
        OllamaSettings(
            host = prefs[Keys.OllamaHost] ?: BuildConfig.DEFAULT_OLLAMA_HOST,
            model = prefs[Keys.OllamaModel] ?: BuildConfig.DEFAULT_OLLAMA_MODEL,
        )
    }

    val scraperSettings: Flow<ScraperSettings> = context.dataStore.data.map { prefs ->
        ScraperSettings(
            host = prefs[Keys.ScraperHost] ?: BuildConfig.DEFAULT_SCRAPER_HOST,
            enabled = prefs[Keys.ScraperEnabled] ?: true,
        )
    }

    suspend fun setScraperHost(host: String) {
        context.dataStore.edit { it[Keys.ScraperHost] = host.trim() }
    }

    suspend fun setScraperEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ScraperEnabled] = enabled }
    }

    suspend fun setOllamaHost(host: String) {
        context.dataStore.edit { it[Keys.OllamaHost] = host.trim() }
    }

    suspend fun setOllamaModel(model: String) {
        context.dataStore.edit { it[Keys.OllamaModel] = model.trim() }
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit {
            it.remove(Keys.OllamaHost)
            it.remove(Keys.OllamaModel)
        }
    }
}
