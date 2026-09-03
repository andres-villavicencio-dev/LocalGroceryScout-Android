package com.localscout.app.ui.screens.receipt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.localscout.app.data.remote.scraper.ReceiptItem
import com.localscout.app.data.remote.scraper.ReceiptScanResponse
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localscout.app.ui.screens.search.TrolleyLoader
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File

/**
 * Receipt-to-savings: photograph a receipt, see what every item costs at its
 * cheapest scouted store, and how much the whole basket could have saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScreen(
    onClose: () -> Unit,
    viewModel: ReceiptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Photo-picker (no permission needed on 13+; system picker on older too)
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.scanReceipt(context, it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt savings") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onClose()
                    }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
        when (val s = state) {
            ReceiptUiState.Idle -> CaptureStep(
                onCapture = { /* wired below via launcher */ },
                onPick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onCaptured = { uri -> uri?.let { viewModel.scanReceipt(context, it) } },
            )
            ReceiptUiState.Uploading,
            ReceiptUiState.Structuring,
            ReceiptUiState.Pricing,
            -> ProcessingStep(s)
            is ReceiptUiState.Result -> ResultStep(
                receipt = s.receipt,
                onScanAnother = { viewModel.reset() },
            )
            is ReceiptUiState.Error -> ErrorStep(
                message = s.message,
                onRetry = { viewModel.reset() },
                onPick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        }
        }
    }
}

@Composable
private fun CaptureStep(
    onCapture: () -> Unit,
    onCaptured: (android.net.Uri?) -> Unit,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> onCaptured(uri) }

    // Camera capture writes to a temp file in cache/receipts/, shared with
    // the camera app via FileProvider. (Uri.fromFile() crashes on modern
    // Android with FileUriExposedException — never expose file:// to apps.)
    val authority = "${context.packageName}.fileprovider"
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = pendingUri
        if (ok && uri != null) onCaptured(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.ReceiptLong, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Scan your receipt",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Photograph the whole receipt and we'll price every item at its cheapest store.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Card(
            onClick = {
                val dir = java.io.File(context.cacheDir, "receipts").apply { mkdirs() }
                val file = java.io.File(dir, "receipt_capture.jpg")
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, authority, file,
                )
                pendingUri = uri
                cameraLauncher.launch(uri)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                Column {
                    Text("Take a photo", style = MaterialTheme.typography.titleMedium)
                    Text("Use the camera", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Column {
                    Text("Choose from gallery", style = MaterialTheme.typography.titleMedium)
                    Text("Already have a photo?", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProcessingStep(state: ReceiptUiState) {
    val label = when (state) {
        ReceiptUiState.Uploading -> "Uploading receipt…"
        ReceiptUiState.Structuring -> "Reading the receipt…"
        ReceiptUiState.Pricing -> "Pricing every item…"
        else -> "Working…"
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrolleyLoader(modifier = Modifier.size(120.dp))
        Spacer(Modifier.height(24.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "OCR + price matching takes up to a minute",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorStep(message: String, onRetry: () -> Unit, onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🤷", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Card(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Try again", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Card(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text("Choose from gallery", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ResultStep(receipt: ReceiptScanResponse, onScanAnother: () -> Unit) {
    // Button lives in a fixed bottomBar — inside a LazyColumn it can end up
    // clipped below the gesture-nav area on tall result lists.
    Scaffold(
        bottomBar = {
            // edge-to-edge is enabled: without navigationBarsPadding the
            // button draws behind the system nav (back/home/recents).
            androidx.compose.material3.Button(
                onClick = onScanAnother,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text("Scan another receipt") }
        },
    ) { innerPadding ->
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            SavingsBanner(receipt)
        }
        item {
            receipt.store.raw?.let { storeName ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        storeName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!receipt.store.scouted) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "not scouted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        items(receipt.items) { item -> ReceiptItemRow(item) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Matched subtotal", style = MaterialTheme.typography.bodyMedium)
                        Text("$${"%.2f".format(receipt.subtotalMatched)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    receipt.receiptTotal?.let { total ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Receipt total", style = MaterialTheme.typography.titleMedium)
                            Text("$${"%.2f".format(total)}", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SavingsBanner(receipt: ReceiptScanResponse) {
    val savings = receipt.estimatedSavings
    val color = if (savings > 0) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (savings > 0) {
                Text(
                    "You could have saved",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "$${"%.2f".format(savings)}",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "${receipt.itemsPriced}/${receipt.itemsCount} items priced at their cheapest store",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Text(
                    "You're already paying the lowest prices we can find 👏",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ReceiptItemRow(item: ReceiptItem) {
    val match = item.match
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    // Prefer the matched product's real DB name — receipt
                    // printouts are truncated/garbled ("MCCOY FRUIT JUICE ORA").
                    val title = item.productName
                        ?: item.cleanName
                        ?: item.name
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    if (title != item.name) {
                        Text(
                            "on receipt: ${item.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text("$${"%.2f".format(item.lineTotal)}", style = MaterialTheme.typography.bodyMedium)
            }
            when {
                match != null && item.savings > 0 -> {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$${"%.2f".format(item.lineTotal)}",
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${match.storeChain} $${"%.2f".format(match.price)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                match != null -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Cheapest here: ${match.storeChain} $${"%.2f".format(match.price)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Unpriced — not in the database yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
