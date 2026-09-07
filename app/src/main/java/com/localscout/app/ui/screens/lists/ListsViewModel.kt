package com.localscout.app.ui.screens.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localscout.app.data.remote.scraper.PlanResponse
import com.localscout.app.data.remote.scraper.ScraperRepository
import com.localscout.app.data.settings.SettingsRepository
import com.localscout.app.data.shoppinglist.ShoppingListRepository
import com.localscout.app.domain.model.ShoppingList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists tab — local persistence via [ShoppingListRepository]. The whole
 * collection flows as one list; counts are tiny, so there's nothing to gain
 * from per-list queries.
 *
 * The basket plan ("cheapest chain for this list") is fetched on demand from
 * the scraper service when the user expands a list — it totals every item at
 * every chain and recommends where to shop.
 */
data class ListsUiState(
    val lists: List<ShoppingList> = emptyList(),
    /** Id of the list currently expanded in place, null = all collapsed. */
    val expandedListId: String? = null,
    /** True until the first emission (even an empty one) arrives from disk. */
    val isLoading: Boolean = true,
    /** Basket comparison for the expanded list, keyed by list id. */
    val plans: Map<String, PlanUiState> = emptyMap(),
)

/** Basket-plan fetch state for one list. */
data class PlanUiState(
    val loading: Boolean = false,
    val plan: PlanResponse? = null,     // null + !loading = unavailable/offline
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val repo: ShoppingListRepository,
    private val scraper: ScraperRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** Pure UI state: which list is expanded in place. */
    private val expandedListId = MutableStateFlow<String?>(null)
    private val plans = MutableStateFlow<Map<String, PlanUiState>>(emptyMap())

    val state: StateFlow<ListsUiState> =
        combine(repo.lists, expandedListId, plans) { lists, expanded, planMap ->
            ListsUiState(
                lists = lists,
                expandedListId = expanded,
                isLoading = false,
                plans = planMap,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ListsUiState(isLoading = true),
        )

    fun createList(name: String) = viewModelScope.launch { repo.createList(name) }
    fun renameList(listId: String, name: String) = viewModelScope.launch { repo.renameList(listId, name) }
    fun deleteList(listId: String) = viewModelScope.launch { repo.deleteList(listId) }
    fun addItem(listId: String, name: String, quantity: Int = 1) =
        viewModelScope.launch { repo.addItem(listId, name, quantity) }
    fun updateQuantity(listId: String, itemId: String, quantity: Int) =
        viewModelScope.launch { repo.updateItemQuantity(listId, itemId, quantity) }
    fun removeItem(listId: String, itemId: String) =
        viewModelScope.launch { repo.removeItem(listId, itemId) }

    fun toggleExpanded(listId: String) {
        expandedListId.value = if (expandedListId.value == listId) null else listId
        if (expandedListId.value != null) loadPlan(listId)
    }

    /** Re-run the basket plan for a list (pull-to-refresh on the section). */
    fun refreshPlan(listId: String) = loadPlan(listId, force = true)

    private fun loadPlan(listId: String, force: Boolean = false) {
        viewModelScope.launch {
            val list = repo.lists.first().firstOrNull { it.id == listId }
                ?: return@launch
            val names = list.items.map { it.name }
            if (names.isEmpty()) return@launch
            val existing = plans.value[listId]
            if (!force && existing?.plan != null) return@launch   // cached
            plans.value = plans.value + (listId to PlanUiState(loading = true))
            val host = settings.scraperSettings.first().host
            val plan = if (host.isBlank()) null
                       else scraper.planBasket(host, names)
            plans.value = plans.value + (listId to PlanUiState(loading = false, plan = plan))
        }
    }
}