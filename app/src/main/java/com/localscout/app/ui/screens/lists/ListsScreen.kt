package com.localscout.app.ui.screens.lists

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localscout.app.data.remote.scraper.PlanResponse
import com.localscout.app.domain.model.ShoppingList
import com.localscout.app.domain.model.ShoppingListItem

/**
 * Lists tab — shopping lists persisted locally (JSON in app-private storage,
 * no backend). Lists render as expandable cards; items support inline
 * quantity steppers, best-price display, rename, and delete.
 */
@Composable
fun ListsScreen(
    paddingValues: PaddingValues,
    viewModel: ListsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renamingList by remember { mutableStateOf<ShoppingList?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Shopping lists",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.width(56.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New list")
            }
        }

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.lists.isEmpty() -> EmptyListsHint()

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.lists, key = { it.id }) { list ->
                    ShoppingListCard(
                        list = list,
                        expanded = state.expandedListId == list.id,
                        planState = state.plans[list.id],
                        onToggleExpanded = { viewModel.toggleExpanded(list.id) },
                        onRefreshPlan = { viewModel.refreshPlan(list.id) },
                        onRename = { renamingList = list },
                        onDelete = { viewModel.deleteList(list.id) },
                        onAddItem = { name, qty -> viewModel.addItem(list.id, name, qty) },
                        onQuantityChange = { itemId, qty -> viewModel.updateQuantity(list.id, itemId, qty) },
                        onRemoveItem = { itemId -> viewModel.removeItem(list.id, itemId) },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        NamePromptDialog(
            title = "New shopping list",
            label = "List name",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name, _ -> viewModel.createList(name) },
            onDismiss = { showCreateDialog = false },
        )
    }
    renamingList?.let { list ->
        NamePromptDialog(
            title = "Rename list",
            label = "List name",
            initial = list.name,
            confirmLabel = "Save",
            onConfirm = { name, _ -> viewModel.renameList(list.id, name) },
            onDismiss = { renamingList = null },
        )
    }
}

@Composable
private fun ShoppingListCard(
    list: ShoppingList,
    expanded: Boolean,
    planState: PlanUiState?,
    onToggleExpanded: () -> Unit,
    onRefreshPlan: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddItem: (name: String, quantity: Int) -> Unit,
    onQuantityChange: (itemId: String, quantity: Int) -> Unit,
    onRemoveItem: (itemId: String) -> Unit,
) {
    var showAddItem by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<ShoppingListItem?>(null) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = list.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = itemCountLabel(list),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename list")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete list")
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp
                    else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (list.items.isEmpty()) {
                        Text(
                            text = "No items yet — add the first one below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    } else {
                        list.items.forEach { item ->
                            ShoppingItemRow(
                                item = item,
                                onQuantityChange = { onQuantityChange(item.id, it) },
                                onRemove = { itemToDelete = item },
                            )
                        }
                        BasketPlanSection(
                            planState = planState,
                            itemCount = list.items.size,
                            onRetry = onRefreshPlan,
                        )
                    }
                    TextButton(onClick = { showAddItem = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add item")
                    }
                }
            }
        }
    }

    if (showAddItem) {
        NamePromptDialog(
            title = "Add item to \"${list.name}\"",
            label = "Item (e.g. milk)",
            initial = "",
            confirmLabel = "Add",
            showQuantity = true,
            onConfirm = { name, quantity -> onAddItem(name, quantity) },
            onDismiss = { showAddItem = false },
        )
    }
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Remove \"${item.name}\"?") },
            text = { Text("This removes the item from \"${list.name}\".") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveItem(item.id)
                    itemToDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ShoppingItemRow(
    item: ShoppingListItem,
    onQuantityChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            val price = item.bestPrice
            if (price != null) {
                // Legacy rows carry junk units ("100" from a mis-split "100pk");
                // only display units that actually say something (contain a letter).
                val cleanUnit = price.unit?.takeIf { u -> u.any { it.isLetter() } }
                Text(
                    text = "$%.2f%s%s".format(
                        price.price,
                        cleanUnit?.let { " / $it" } ?: "",
                        price.store?.let { "  ·  $it" } ?: "",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        QuantityStepper(
            quantity = item.quantity,
            onChange = onQuantityChange,
        )
        IconButton(onClick = onRemove, modifier = Modifier.width(32.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove ${item.name}",
                modifier = Modifier.width(18.dp),
            )
        }
    }
}

@Composable
private fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(quantity - 1) }, enabled = quantity > 0) {
            Icon(Icons.Outlined.Remove, contentDescription = "Decrease quantity")
        }
        Text(
            text = "$quantity",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(24.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange(quantity + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = "Increase quantity")
        }
    }
}

@Composable
private fun EmptyListsHint() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.ShoppingCart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            text = "No shopping lists yet",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Tap + to create your first list.\nLists are saved on this device.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Name (and optional quantity) prompt used for create-list, rename-list and
 * add-item. Blank confirmations are ignored; rename pre-fills the old name.
 */
@Composable
private fun NamePromptDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (name: String, quantity: Int) -> Unit,
    onDismiss: () -> Unit,
    showQuantity: Boolean = false,
) {
    var name by remember { mutableStateOf(initial) }
    var quantity by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (name.isNotBlank()) {
                                onConfirm(name.trim(), (quantity.toIntOrNull() ?: 1).coerceAtLeast(1))
                                onDismiss()
                            }
                        },
                    ),
                )
                if (showQuantity) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Quantity") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    // Blank/zero quantity falls back to 1 (field starts empty).
                    onConfirm(name.trim(), (quantity.toIntOrNull() ?: 1).coerceAtLeast(1))
                    onDismiss()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun itemCountLabel(list: ShoppingList): String {
    val n = list.items.size
    return when {
        n == 0 -> "Empty"
        n == 1 -> "1 item"
        else -> "$n items"
    }
}

/**
 * Basket comparison for the expanded list: totals the list at every chain
 * (via the scraper service's /plan endpoint) and flags the cheapest. Shows
 * loading while fetching, a retry row when the service is unreachable, and
 * per-chain totals with the recommended chain highlighted.
 */
@Composable
private fun BasketPlanSection(
    planState: PlanUiState?,
    itemCount: Int,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            planState == null || (!planState.loading && planState.plan == null) ->
                TextButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Compare stores for this basket",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

            planState.loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Pricing this basket at every chain…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> BasketPlanCard(plan = planState.plan!!, onRetry = onRetry)
        }
    }
}

@Composable
private fun BasketPlanCard(plan: PlanResponse, onRetry: () -> Unit) {
    val rec = plan.recommendation
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = "Basket comparison",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val unmatched = plan.items.count { !it.matched }
        if (unmatched > 0) {
            Text(
                text = "$unmatched item${if (unmatched == 1) "" else "s"} not found " +
                    "in the price database",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        plan.chains.take(4).forEach { chain ->
            val isBest = rec != null && chain.storeChain == rec.storeChain &&
                chain.missing == rec.missing && chain.total == rec.total
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        isBest && chain.missing == 0 -> "★ "
                        isBest -> "◦ "
                        else -> "  "
                    },
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = chain.storeChain,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isBest) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (chain.missing > 0) "(${chain.missing} missing)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$%.2f".format(chain.total),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isBest) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isBest) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (rec != null) {
            Text(
                text = if (rec.missing == 0)
                    "Shop at ${rec.storeChain} — cheapest full basket"
                else
                    "Best overall: ${rec.storeChain} ($rec.missing of ${plan.items.size} items unavailable there)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Re-check prices", style = MaterialTheme.typography.bodySmall)
        }
    }
}