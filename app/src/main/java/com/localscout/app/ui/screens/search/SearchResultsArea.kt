package com.localscout.app.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localscout.app.R
import com.localscout.app.domain.model.ParsedPrice
import com.localscout.app.domain.model.SearchResult

@Composable
fun SearchResultsArea(
    state: SearchUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when {
            state.isSearching -> ThinkingIndicator(
                modelName = state.modelName,
                elapsedSeconds = state.elapsedSeconds,
                phase = state.thinkingPhase,
            )
            state.error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.search_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            state.result != null -> SearchResultList(state.result)
            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
            ) {
                Text(
                    text = "Tip: searches hit the scraper service first (real supermarket prices) and fall back to ollama estimates if it's down. Look for the SCOUTED badge on cards.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchResultList(result: SearchResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = result.productName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "via ${result.modelUsed} · ${result.results.size} results",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (result.summary.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = result.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                result.results,
                // Key must be unique per row. store+price can collide when the
                // same store sells two pack sizes of one product at the same
                // price (e.g. Milo 350g and 620g both $9.99). Fold in the
                // product name and a positional fallback so duplicates can
                // never crash the list.
                key = { idx, it -> "${it.store}-${it.price}-${it.reasoning.hashCode()}-$idx" },
            ) { _, price ->
                PriceCard(price)
            }
        }
    }
}

@Composable
private fun PriceCard(price: ParsedPrice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = price.store,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // Provenance badge: scraped prices are ground truth;
                // ollama prices are estimates. The reasoning string is the
                // tell — scraper rows say "scraped from ..." (live) or
                // "cached scrape" (cache). Match the stem "scrap" to
                // cover both.
                val isScraped = price.reasoning?.contains("scrap", ignoreCase = true) == true
                Text(
                    text = if (isScraped) "SCOUTED" else "ESTIMATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isScraped) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = "${price.currency} ${"%.2f".format(price.price)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            price.unit?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            price.distanceKm?.let {
                Text(
                    text = "%.1f km away".format(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("confidence %.0f%%".format(price.confidence * 100)) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
                price.reasoning?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
