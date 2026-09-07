package com.localscout.app.data.shoppinglist

import android.content.Context
import com.localscout.app.domain.model.ShoppingList
import com.localscout.app.domain.model.ShoppingListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local shopping lists, persisted as one JSON file in app-private storage.
 * No backend, no account — lists live and die with the app's data.
 *
 * Storage model: <filesDir>/data/shopping_lists.json holds a JSON array of
 * ShoppingList. Loaded once at construction into an in-memory StateFlow;
 * every mutation rewrites the file atomically (write-then-rename) inside a
 * mutex, so a crash mid-write can never corrupt the previous snapshot.
 */
@Singleton
class ShoppingListRepository @Inject constructor(
    private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lists = MutableStateFlow<List<ShoppingList>>(emptyList())
    val lists: Flow<List<ShoppingList>> = _lists.asStateFlow()

    private val file: File
        get() = File(context.filesDir, "data/shopping_lists.json")

    init {
        scope.launch { load() }
    }

    private suspend fun load() = mutex.withLock {
        val loaded = if (file.exists()) {
            runCatching {
                json.decodeFromString(
                    ListSerializer(ShoppingList.serializer()),
                    file.readText(),
                )
            }.getOrDefault(emptyList())
        } else emptyList()
        _lists.value = loaded
    }

    /** Creates a list and returns its id. */
    suspend fun createList(name: String): String = mutex.withLock {
        val id = UUID.randomUUID().toString()
        val now = nowIso()
        _lists.value = _lists.value + ShoppingList(
            id = id,
            name = name.trim().ifEmpty { defaultListName() },
            items = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        persistLocked()
        id
    }

    suspend fun renameList(listId: String, name: String) = mutateListLocked(listId) {
        it.copy(name = name.trim(), updatedAt = nowIso())
    }

    suspend fun deleteList(listId: String) = mutex.withLock {
        _lists.value = _lists.value.filterNot { it.id == listId }
        persistLocked()
    }

    /**
     * Adds an item, merging by (name, unit) — adding "milk" twice bumps the
     * quantity rather than stacking duplicate rows. When [price] is supplied
     * (the search flow's best price) it becomes the item's bestPrice and the
     * seen-timestamp refreshes; a re-add without a price keeps the old one.
     */
    suspend fun addItem(listId: String, name: String, quantity: Int = 1, price: com.localscout.app.domain.model.ParsedPrice? = null) =
        mutex.withLock {
            val list = _lists.value.firstOrNull { it.id == listId } ?: return@withLock
            val trimmed = name.trim()
            val existing = list.items.firstOrNull {
                it.name.equals(trimmed, ignoreCase = true) &&
                    (it.bestPrice?.unit ?: "") == (price?.unit ?: "")
            }
            val updatedItems = if (existing != null) {
                list.items.map { item ->
                    if (item.id == existing.id) item.copy(
                        quantity = item.quantity + quantity,
                        // Keep the freshest price: overwrite only when a new one arrived.
                        bestPrice = price ?: item.bestPrice,
                        bestPriceSeenAt = if (price != null) nowIso() else item.bestPriceSeenAt,
                    ) else item
                }
            } else {
                list.items + ShoppingListItem(
                    id = UUID.randomUUID().toString(),
                    name = trimmed,
                    quantity = quantity,
                    addedAt = nowIso(),
                    bestPrice = price,
                    bestPriceSeenAt = price?.let { nowIso() },
                )
            }
            _lists.value = _lists.value.map {
                if (it.id == listId) it.copy(items = updatedItems, updatedAt = nowIso()) else it
            }
            persistLocked()
        }

    suspend fun updateItemQuantity(listId: String, itemId: String, quantity: Int) = mutateListLocked(listId) { list ->
        list.copy(items = list.items.map { item ->
            if (item.id == itemId) item.copy(quantity = quantity.coerceAtLeast(0)) else item
        }, updatedAt = nowIso())
    }

    suspend fun removeItem(listId: String, itemId: String) = mutateListLocked(listId) { list ->
        list.copy(items = list.items.filterNot { it.id == itemId }, updatedAt = nowIso())
    }

    private suspend fun mutateListLocked(
        listId: String,
        transform: (ShoppingList) -> ShoppingList,
    ) = mutex.withLock {
        _lists.value = _lists.value.map { if (it.id == listId) transform(it) else it }
        persistLocked()
    }

    /** Caller must hold [mutex] — writes happen only under it. */
    private fun persistLocked() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(
            json.encodeToString(
                ListSerializer(ShoppingList.serializer()),
                _lists.value,
            )
        )
        if (!tmp.renameTo(file)) {
            // renameTo fails across some FS quirks; fall back to a plain copy.
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun nowIso(): String = OffsetDateTime.now().toString()

    private fun defaultListName(): String = "Shopping list"
}