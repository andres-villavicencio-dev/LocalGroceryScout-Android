package com.localscout.app.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localscout.app.R

@Composable
fun SearchScreen(
    paddingValues: PaddingValues,
    onOpenScanner: () -> Unit,
    onOpenSettings: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onOpenSettings) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                )
            }
        }

        Text(
            text = "Local Grocery Scout",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            text = "Find the best prices for groceries near you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )

        // Detected area, e.g. "📍 Ponsonby, Auckland" — appears once the GPS
        // fix + reverse-geocode resolve; absent entirely if they fail.
        state.detectedAddress?.let { addr ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
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
                        viewModel.search()
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

        // Results / loading / error states are rendered below this point.
        SearchResultsArea(
            state = state,
            onRetry = viewModel::search,
            onProductSelect = viewModel::selectProduct,
            onBackToPicker = viewModel::backToPicker,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
