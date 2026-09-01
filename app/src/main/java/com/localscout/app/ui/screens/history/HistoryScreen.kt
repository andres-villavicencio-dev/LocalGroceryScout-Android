package com.localscout.app.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localscout.app.data.remote.scraper.HistorySeries
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Price history screen — charts the append-only price_history table.
 *
 * Each (store, product) pair gets a card: sparkline stats + every scrape
 * listed with its date. Vico line charts need >= 2 points to be interesting;
 * with a single scrape we show the stats row only (a one-point chart is a dot).
 */
@Composable
fun HistoryScreen(
    paddingValues: PaddingValues,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Price history",
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text("Product (e.g. milo cereal)") },
                singleLine = true,
            )
            Button(onClick = viewModel::load, enabled = !state.isLoading) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Chart")
                }
            }
        }

        when {
            state.error != null -> Text(
                text = state.error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            state.history == null -> HistoryEmptyHint()
            else -> {
                val history = state.history!!
                Text(
                    text = "Tracking ${history.series.size} product(s) over ${history.days} days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(history.series) { series ->
                        HistorySeriesCard(series)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEmptyHint() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 32.dp, bottom = 12.dp),
        )
        Text(
            text = "Type a product above to see its price history.\nEvery search adds a dated datapoint — charts grow over time.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistorySeriesCard(series: HistorySeries) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = series.product.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = series.store,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val trendingUp = series.latest > series.min
                Icon(
                    imageVector = if (trendingUp) Icons.Outlined.TrendingUp
                    else Icons.Outlined.TrendingDown,
                    contentDescription = null,
                    tint = if (trendingUp) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary,
                )
            }

            Text(
                text = "min $%.2f · max $%.2f · latest $%.2f"
                    .format(series.min, series.max, series.latest),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )

            // Datapoint list — the scrape dates. With >=2 points this reads
            // like a changelog of price moves; it IS the chart's raw data.
            series.points.sortedByDescending { it.t }.forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatScrapeDate(p.t),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$%.2f".format(p.price) + (p.unit_price?.let { "  ·  $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

private fun formatScrapeDate(epochSeconds: Double): String =
    Instant.ofEpochSecond(epochSeconds.toLong())
        .atZone(ZoneId.systemDefault())
        .format(dateFmt)