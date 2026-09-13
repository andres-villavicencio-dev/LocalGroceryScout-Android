package com.localscout.app.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localscout.app.R
import com.localscout.app.data.remote.scraper.LatestItem
import com.localscout.app.domain.model.ShoppingList

@Composable
fun SearchScreen(
    paddingValues: PaddingValues,
    onOpenScanner: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReceipt: () -> Unit = {},
    scannedProduct: String? = null,
    onScannedProductConsumed: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    // Scanned-barcode handoff: the scanner resolved a product name via Open
    // Food Facts; seed the query with it and auto-run the search once.
    LaunchedEffect(scannedProduct) {
        if (!scannedProduct.isNullOrBlank() && scannedProduct != state.query) {
            viewModel.onQueryChange(scannedProduct)
            viewModel.search()
            onScannedProductConsumed()
        }
    }

    // Material scrolling conventions, state-aware:
    //  - IDLE home → one LazyColumn IS the screen. Header, search row and
    //    the fresh-finds cards are all list items — the whole screen scrolls
    //    together, with lazy composition, stable keys and contentPadding
    //    insets. (Never verticalScroll around a list — that defeats lazy
    //    reuse and crashes with nested lazy containers.)
    //  - SEARCHING / RESULTS / ERROR → the header pins: those states own
    //    internal LazyColumns (illegal to nest in another scrollable), and
    //    a mid-comparison user needs the search bar one tap away.
    val isIdle = state.result == null && !state.isSearching && state.error == null

    if (isIdle) {
        IdleHomeScreen(
            state = state,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::search,
            onOpenScanner = onOpenScanner,
            onOpenSettings = onOpenSettings,
            onOpenReceipt = onOpenReceipt,
            onFindTapped = viewModel::onFindTapped,
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
        ) {
            ScoutSettingsRow(onOpenSettings = onOpenSettings)
            ScoutTitleBlock(detectedAddress = state.detectedAddress)
            SearchRow(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = viewModel::search,
                onOpenScanner = onOpenScanner,
            )
            ReceiptScanButton(onOpenReceipt = onOpenReceipt)
            SearchResultsArea(
                state = state,
                onRetry = viewModel::search,
                onProductSelect = viewModel::selectProduct,
                onBackToPicker = viewModel::backToPicker,
                onAddToList = viewModel::requestAddToList,
                onShowEstimates = viewModel::showEstimates,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    // Add-to-list dialog: pick which local list gets the product.
    state.pendingAddToList?.let { pending ->
        AddToListDialog(
            pending = pending,
            lists = state.listChoices,
            onConfirmExisting = viewModel::confirmAddToList,
            onCreateAndAdd = viewModel::createListAndAdd,
            onDismiss = viewModel::dismissAddToList,
        )
    }
}

/**
 * The idle home: a single LazyColumn that contains the entire screen —
 * settings row, title, location, search, receipt entry and the fresh-finds
 * feed — so everything scrolls as one surface. Empty feed (scraper down
 * or still loading) degrades to a progress bar / tip text item instead.
 */
@Composable
private fun IdleHomeScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReceipt: () -> Unit,
    onFindTapped: (LatestItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "settings-row") {
            ScoutSettingsRow(onOpenSettings = onOpenSettings)
        }
        item(key = "title-block") {
            ScoutTitleBlock(detectedAddress = state.detectedAddress)
        }
        item(key = "search-row") {
            SearchRow(
                query = state.query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onOpenScanner = onOpenScanner,
            )
        }
        item(key = "receipt-button") {
            ReceiptScanButton(onOpenReceipt = onOpenReceipt)
        }

        if (state.latestFinds.isEmpty()) {
            // Feed not available yet: loading bar while fetching, otherwise
            // the orientation tip. Never a dead section.
            item(key = "feed-fallback") {
                if (state.isLoadingFinds) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                } else {
                    Text(
                        text = "Tip: searches hit your price scraper for real supermarket prices " +
                            "(look for the SCOUTED badge). If it's down, you'll get an honest " +
                            "no-data message — rough estimates are opt-in and always labelled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        } else {
            item(key = "feed-header") { FreshFindsHeader() }
            itemsIndexed(
                state.latestFinds,
                key = { idx, it -> "find-${it.productSlug ?: it.productName}-$idx" },
            ) { _, find ->
                // M3 list physics: animateItem gives the feed the same
                // placement/appearance springs as the results lists.
                Box(modifier = Modifier.animateItem()) {
                    LatestFindCard(find = find, onClick = { onFindTapped(find) })
                }
            }
        }
    }
}

// ── shared header pieces (used by both the idle list and the pinned
//    header — single source of truth for each block) ────────────────────────

@Composable
private fun ScoutSettingsRow(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(onClick = onOpenSettings) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.settings_title),
            )
        }
    }
}

@Composable
private fun ScoutTitleBlock(detectedAddress: String?) {
    Column {
        Text(
            text = "Local Grocery Scout",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            text = "Find the best prices for groceries near you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        detectedAddress?.let { addr ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = addr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenScanner: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    onSearch()
                },
            ),
        )
        FilledIconButton(
            onClick = onOpenScanner,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(56.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = "Scan barcode",
            )
        }
    }
}

@Composable
private fun ReceiptScanButton(onOpenReceipt: () -> Unit) {
    OutlinedButton(
        onClick = onOpenReceipt,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.ReceiptLong,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Scan a receipt — see what you could have saved")
    }
}

@Composable
private fun FreshFindsHeader() {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "Fresh finds",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Just scouted · cheapest current price per product",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Choose-a-list dialog for "add to list" from search results. With no lists
 * yet it offers inline creation; the item lands in the new list immediately.
 */
@Composable
private fun AddToListDialog(
    pending: ProductOption,
    lists: List<ShoppingList>,
    onConfirmExisting: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newListName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = pending.productName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (lists.isEmpty()) {
                    Text(
                        text = "No lists yet — name one to create:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    lists.forEach { list ->
                        TextButton(onClick = { onConfirmExisting(list.id) }) {
                            Text(
                                text = list.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    Text(
                        text = "…or create a new list:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("New list name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = newListName.isNotBlank(),
                onClick = { onCreateAndAdd(newListName.trim()) },
            ) { Text("Create & add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}