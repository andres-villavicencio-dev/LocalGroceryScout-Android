package com.localscout.app.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localscout.app.R
import com.localscout.app.domain.model.ParsedPrice
import com.localscout.app.domain.model.SearchResult

/**
 * Results area implementing M3 Expressive tactic #2 (contrast hierarchy) and
 * #3 (typography guides attention):
 *
 *  - The PRICE is the hero of each card: displaySmall, heavy weight.
 *  - The cheapest result breaks from the pack: tertiaryContainer fill +
 *    larger corner radius + "Best price" chip (shape-as-emphasis).
 *  - Metadata (unit, distance, confidence) demotes to small muted text so
 *    the eye lands on price → store → detail in that order.
 */
@Composable
fun SearchResultsArea(
    state: SearchUiState,
    onRetry: () -> Unit,
    onProductSelect: (String) -> Unit,
    onBackToPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when {
            state.isSearching -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
            ) {
                TrolleyLoader()
                Text(
                    text = ThinkingPhrases.statusText(state.thinkingPhase),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    text = "${state.elapsedSeconds} s elapsed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (state.modelName != null) {
                    Text(
                        text = state.modelName!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
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
            // ── Step 2a: product picker grid ────────────────────────────────
            state.result != null && state.selectedProduct == null ->
                ProductPickerGrid(
                    options = state.productOptions,
                    source = state.result!!.modelUsed,
                    onSelect = onProductSelect,
                )
            // ── Step 2b: chosen product → cheapest-across-stores list ────────
            state.result != null && state.selectedProduct != null -> {
                val selected = state.selectedProduct!!
                val rows = state.productRows
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Back-to-picker affordance
                    SuggestionChip(
                        onClick = onBackToPicker,
                        label = { Text("← all products (${state.productOptions.size})") },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SearchResultListFor(
                        productName = selected,
                        rows = rows,
                        source = state.result!!.modelUsed,
                    )
                }
            }
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
private fun ProductPickerGrid(
    options: List<ProductOption>,
    source: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "What did you mean?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${options.size} products · via $source — tap one for the cheapest store",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                options,
                key = { idx, it -> "opt-${it.productName}-$idx" },
            ) { idx, opt ->
                SpringEntrance(index = idx) {
                    ProductOptionCard(opt = opt, onClick = { onSelect(opt.productName) })
                }
            }
        }
    }
}

@Composable
private fun ProductOptionCard(opt: ProductOption, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = opt.productName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append("cheapest at ")
                        append(opt.bestStore)
                        append(" · ")
                        append(opt.storeCount)
                        append(if (opt.storeCount == 1) " store" else " stores")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$%.2f".format(opt.cheapestPrice),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (opt.isScouted) "SCOUTED" else "ESTIMATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (opt.isScouted) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The chosen product's price list: every store that sells it, cheapest first,
 * with the hero-price treatment for the winner.
 */
@Composable
private fun SearchResultListFor(
    productName: String,
    rows: List<ParsedPrice>,
    source: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = productName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "via $source · ${rows.size} stores",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                rows,
                key = { idx, it -> "${it.store}-${it.price}-${it.reasoning.hashCode()}-$idx" },
            ) { idx, price ->
                SpringEntrance(index = idx) {
                    PriceCard(
                        price = price,
                        isBestPrice = idx == 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceCard(
    price: ParsedPrice,
    isBestPrice: Boolean = false,
) {
    // Expressive tactic "break from the surrounding shape style": the best-
    // price card uses a larger corner radius + filled tertiary container so
    // the cheapest option is findable pre-attentively.
    val container = if (isBestPrice) MaterialTheme.colorScheme.tertiaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (isBestPrice) MaterialTheme.colorScheme.onTertiaryContainer
    else MaterialTheme.colorScheme.onSurface
    val shape = if (isBestPrice) RoundedCornerShape(20.dp) else RoundedCornerShape(12.dp)

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isBestPrice) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = price.store,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = onContainer,
                        )
                        if (isBestPrice) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Best price") },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    labelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    // Muted metadata line: unit + distance collapsed to one line
                    val meta = listOfNotNull(
                        price.unit,
                        price.distanceKm?.let { "%.1f km".format(it) },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = onContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                // THE HERO: price in displaySmall, bold, contrast color.
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$%.2f".format(price.price),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isBestPrice) MaterialTheme.colorScheme.primary
                        else onContainer,
                    )
                    // Provenance badge under the price
                    val isScraped = price.reasoning?.contains("scrap", ignoreCase = true) == true
                    Text(
                        text = if (isScraped) "SCOUTED" else "ESTIMATE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isScraped) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }

            price.reasoning?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("confidence %.0f%%".format(price.confidence * 100)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = onContainer.copy(alpha = 0.06f),
                        labelColor = onContainer,
                    ),
                )
            }
        }
    }
}