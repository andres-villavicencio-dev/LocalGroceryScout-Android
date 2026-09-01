package com.localscout.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localscout.app.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var host by remember(settings.host) { mutableStateOf(settings.host) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Ollama connection",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Local Grocery Scout talks to your local ollama instance over the network. The phone just calls a URL — no API keys, no cloud.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_ollama_host)) },
                placeholder = { Text(stringResource(R.string.settings_ollama_host_hint)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_ollama_model)) },
                placeholder = { Text(stringResource(R.string.settings_ollama_model_hint)) },
                singleLine = true,
                supportingText = { Text("Default: gemma4:e4b (tested winner — see workspace/prompts/RESULTS.md)") },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.save(host, model)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_save))
                }
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.testConnection(host)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = testState !is TestState.Testing,
                ) {
                    if (testState is TestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.settings_test_connection))
                }
            }

            when (val s = testState) {
                is TestState.Success -> Text(
                    text = "${stringResource(R.string.settings_connection_ok)} — found ${s.modelCount} model(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                is TestState.Failure -> Text(
                    text = "${stringResource(R.string.settings_connection_failed)}: ${s.error}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }

            // ── Scraper service ─────────────────────────────────────────────
            Text(
                text = "Price scraper service",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "When enabled, searches hit this service first for REAL prices scraped from supermarket online shops (New World, Pak'nSave). If it's unreachable, the app falls back to ollama estimates.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val scraperCfg by viewModel.scraperSettings.collectAsStateWithLifecycle()
            var scraperHost by remember(scraperCfg.host) { mutableStateOf(scraperCfg.host) }
            var scraperEnabled by remember(scraperCfg.enabled) { mutableStateOf(scraperCfg.enabled) }
            val scraperTestState by viewModel.scraperTestState.collectAsStateWithLifecycle()

            OutlinedTextField(
                value = scraperHost,
                onValueChange = { scraperHost = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Scraper service URL") },
                placeholder = { Text("http://192.168.1.72:8300") },
                singleLine = true,
            )
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.Switch(
                    checked = scraperEnabled,
                    onCheckedChange = { scraperEnabled = it },
                )
                Text(
                    text = if (scraperEnabled) "Scraper-first (real prices)" else "Ollama estimates only",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.saveScraper(scraperHost, scraperEnabled)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_save))
                }
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.testScraper(scraperHost)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = scraperTestState !is ScraperTestState.Testing,
                ) {
                    if (scraperTestState is ScraperTestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.settings_test_connection))
                }
            }

            when (val s = scraperTestState) {
                is ScraperTestState.Success -> Text(
                    text = s.stats,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                is ScraperTestState.Failure -> Text(
                    text = "Scraper unreachable: ${s.error}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
    }
}
