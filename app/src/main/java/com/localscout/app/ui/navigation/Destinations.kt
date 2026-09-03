package com.localscout.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector
import com.localscout.app.R

/**
 * Top-level navigation destinations. Bottom nav drives these four; barcode
 * scanner and item detail are pushed onto the stack via NavHost routes.
 */
sealed class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
) {
    data object Search : TopLevelDestination(
        "search",
        R.string.nav_search,
        Icons.Filled.Search,
        Icons.Outlined.Search,
    )
    data object Lists : TopLevelDestination(
        "lists",
        R.string.nav_lists,
        Icons.Filled.ShoppingCart,
        Icons.Outlined.ShoppingCart,
    )
    data object History : TopLevelDestination(
        "history",
        R.string.nav_history,
        Icons.Filled.History,
        Icons.Outlined.History,
    )
    data object Account : TopLevelDestination(
        "account",
        R.string.nav_account,
        Icons.Filled.Person,
        Icons.Outlined.Person,
    )

    companion object {
        val all = listOf(Search, Lists, History, Account)
    }
}

object Routes {
    const val Scanner = "scanner"
    const val Settings = "settings"
    const val Receipt = "receipt"
}
