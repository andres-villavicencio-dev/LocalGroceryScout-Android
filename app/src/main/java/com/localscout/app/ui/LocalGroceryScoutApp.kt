package com.localscout.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localscout.app.ui.navigation.Routes
import com.localscout.app.ui.navigation.NavTransitions
import com.localscout.app.ui.navigation.TopLevelDestination
import com.localscout.app.ui.screens.account.AccountScreen
import com.localscout.app.ui.screens.history.HistoryScreen
import com.localscout.app.ui.screens.lists.ListsScreen
import com.localscout.app.ui.screens.scanner.BarcodeScannerScreen
import com.localscout.app.ui.screens.receipt.ReceiptScreen
import com.localscout.app.ui.screens.search.SearchScreen
import com.localscout.app.ui.screens.settings.SettingsScreen

@Composable
fun LocalGroceryScoutAppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Scanned-product handoff: the scanner navigates back to Search and drops
    // the product name into this state; SearchScreen consumes it once.
    var pendingScannedProduct by remember { mutableStateOf<String?>(null) }

    Scaffold(
        bottomBar = {
            // Hide bottom bar on full-screen routes like the scanner and settings
            if (currentRoute in TopLevelDestination.all.map { it.route }) {
                BottomBar(
                    currentRoute = currentRoute,
                    onSelect = { dest ->
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Search.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(
                TopLevelDestination.Search.route,
                enterTransition = { NavTransitions.tabEnter() },
                exitTransition = { NavTransitions.tabExit() },
                popEnterTransition = { NavTransitions.tabEnter() },
                popExitTransition = { NavTransitions.tabExit() },
            ) {
                SearchScreen(
                    paddingValues = padding,
                    onOpenScanner = { navController.navigate(Routes.Scanner) },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    onOpenReceipt = { navController.navigate(Routes.Receipt) },
                    scannedProduct = pendingScannedProduct,
                    onScannedProductConsumed = { pendingScannedProduct = null }
                )
            }
            composable(
                TopLevelDestination.Lists.route,
                enterTransition = { NavTransitions.tabEnter() },
                exitTransition = { NavTransitions.tabExit() },
                popEnterTransition = { NavTransitions.tabEnter() },
                popExitTransition = { NavTransitions.tabExit() },
            ) {
                ListsScreen(paddingValues = padding)
            }
            composable(
                TopLevelDestination.History.route,
                enterTransition = { NavTransitions.tabEnter() },
                exitTransition = { NavTransitions.tabExit() },
                popEnterTransition = { NavTransitions.tabEnter() },
                popExitTransition = { NavTransitions.tabExit() },
            ) {
                HistoryScreen(paddingValues = padding)
            }
            composable(
                TopLevelDestination.Account.route,
                enterTransition = { NavTransitions.tabEnter() },
                exitTransition = { NavTransitions.tabExit() },
                popEnterTransition = { NavTransitions.tabEnter() },
                popExitTransition = { NavTransitions.tabExit() },
            ) {
                AccountScreen(
                    paddingValues = padding,
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                )
            }
            composable(
                Routes.Scanner,
                enterTransition = { NavTransitions.pushEnter() },
                exitTransition = { NavTransitions.pushExit() },
                popEnterTransition = { NavTransitions.popEnter() },
                popExitTransition = { NavTransitions.popExit() },
            ) {
                BarcodeScannerScreen(
                    onSearchProduct = { productName ->
                        // Full post-scan flow: hand the OFF-identified name to
                        // Search (which auto-runs the price scout), then pop
                        // the scanner off the stack.
                        pendingScannedProduct = productName
                        navController.popBackStack()
                    },
                    onClose = { navController.popBackStack() },
                )
            }
            composable(
                Routes.Receipt,
                enterTransition = { NavTransitions.pushEnter() },
                exitTransition = { NavTransitions.pushExit() },
                popEnterTransition = { NavTransitions.popEnter() },
                popExitTransition = { NavTransitions.popExit() },
            ) {
                ReceiptScreen(onClose = { navController.popBackStack() })
            }
            composable(
                Routes.Settings,
                enterTransition = { NavTransitions.pushEnter() },
                exitTransition = { NavTransitions.pushExit() },
                popEnterTransition = { NavTransitions.popEnter() },
                popExitTransition = { NavTransitions.popExit() },
            ) {
                SettingsScreen(paddingValues = padding, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun BottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar {
        TopLevelDestination.all.forEach { dest ->
            val selected = currentRoute == dest.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(dest) },
                icon = {
                    Icon(
                        imageVector = if (selected) dest.iconSelected else dest.iconUnselected,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(dest.labelRes)) },
            )
        }
    }
}
