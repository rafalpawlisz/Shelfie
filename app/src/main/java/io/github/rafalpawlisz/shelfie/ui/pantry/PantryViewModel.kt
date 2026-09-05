package io.github.rafalpawlisz.shelfie.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.ShelfieApplication
import io.github.rafalpawlisz.shelfie.data.BarcodeRepository
import io.github.rafalpawlisz.shelfie.data.ProductRepository
import io.github.rafalpawlisz.shelfie.data.ShoppingListRepository
import io.github.rafalpawlisz.shelfie.data.UiPreferences
import io.github.rafalpawlisz.shelfie.model.ItemSlot
import io.github.rafalpawlisz.shelfie.model.OneOffSuggestion
import io.github.rafalpawlisz.shelfie.model.Product
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.model.ShoppingList
import io.github.rafalpawlisz.shelfie.model.ShoppingListItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A restock hint after a use-up left the stock below the product's minimum:
 * offer to put the product on a shopping list. [suggestedAmount] tops the
 * stock back up to the minimum.
 */
data class LowStockSuggestion(
    val productId: String,
    val productName: String,
    val suggestedAmount: Int,
)

/** One-shot outcome of a use-up (tap or scan) on the Use up tab. */
sealed interface UseUpScanResult {
    // A use-up may carry a restock suggestion so the UI can show ONE snackbar
    // with both messages; [productId] lets the plain variant offer Undo.
    data class Used(
        val productId: String,
        val productName: String,
        // How much was taken off, so Undo can put exactly that back.
        val amount: Int = 1,
        val suggestion: LowStockSuggestion? = null,
    ) : UseUpScanResult

    data class OutOfStock(val productName: String) : UseUpScanResult
    data class UnknownCode(val code: String) : UseUpScanResult
}

/**
 * Snapshot of a shopping-list item that was just removed, carrying everything
 * Undo needs to put it back ([ShoppingListItem] itself has no listId — the
 * removal always happens on the then-selected list).
 */
data class RemovedShoppingItem(
    val listId: String,
    // null = a one-off item; undo re-adds it by its name.
    val productId: String?,
    val productName: String,
    val amount: Int?,
    // A one-off's own unit ("g", "opakowania"); null for product-backed rows,
    // which read their unit off the product when they come back.
    val unit: String?,
    // A one-off's hand-picked section, so undo brings back the answer too and
    // not just the line. Null where nobody picked one, which undo restores as
    // faithfully as it restores a pick.
    val sectionEmoji: String?,
    val note: String?,
)

/**
 * A row the picker just wrote to [listId] (a product, merged into its row when
 * it was already there; or a fresh one-off line). The UI scrolls it into view
 * once Room hands the list back — the sort may park the row anywhere, and an
 * add answered by nothing looks lost.
 *
 * [sentAtMillis] lets the screen drop replays: the event channel keeps events
 * sent while the list was not on screen, and revealing one of those on return
 * would scroll to a row the user added long ago.
 */
data class AddedShoppingItem(
    val listId: String,
    val itemId: String,
    val sentAtMillis: Long,
)

data class PantryUiState(
    val products: List<Product> = emptyList(),
    val archivedProducts: List<Product> = emptyList(),
    // Derived "low stock" list: active products below their minimum that are not
    // yet planned on any active shopping list.
    val lowStockProducts: List<Product> = emptyList(),
    // productId -> ids of the active lists that already plan it; guards moves.
    val plannedByProduct: Map<String, Set<String>> = emptyMap(),
    // Products referenced by any list, archived lists included. An archived
    // product outside this set is the only kind that may be deleted for good.
    val referencedProductIds: Set<String> = emptySet(),
    val lists: List<ShoppingList> = emptyList(),
    val archivedLists: List<ShoppingList> = emptyList(),
    // Active lists the user sees as empty (no visible items); the UI can hide
    // them from the chips row without touching the data.
    val emptyListIds: Set<String> = emptySet(),
    val selectedListId: String? = null,
    val shoppingList: List<ShoppingListItem> = emptyList(),
    // Names bought once before, newest first — offered by the picker so the
    // same word need not be typed twice.
    val oneOffSuggestions: List<OneOffSuggestion> = emptyList(),
    val barcodesByProduct: Map<String, List<String>> = emptyMap(),
    val isLoading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PantryViewModel(
    private val repository: ProductRepository,
    private val shoppingListRepository: ShoppingListRepository,
    private val barcodeRepository: BarcodeRepository,
    private val uiPreferences: UiPreferences,
) : ViewModel() {

    // Which list the Shopping tab shows. Kept valid by the init reconciler
    // below; null only when there are no lists.
    private val selectedListId = MutableStateFlow<String?>(null)

    private val shoppingItems =
        selectedListId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else shoppingListRepository.observeItems(id)
        }

    // The list the app-level picker adds to, captured when it opens. The dialog
    // is a full-screen window that stays up across sync pulls; a reselection
    // behind it (the list was archived on the other device and the reconciler
    // moved on) must not redirect the confirm to a list the user was not
    // looking at — its "already on this list" rows and pre-fills would belong
    // to one list and the merge would clobber another's.
    private val pickerListId = MutableStateFlow<String?>(null)
    val pickerItems: StateFlow<List<ShoppingListItem>> =
        pickerListId.flatMapLatest { id ->
            if (id == null) {
                flowOf(emptyList())
            } else {
                // Dormant rows included: picking an archived product pre-fills
                // from the row the list screen hides, so confirming round-trips
                // its stored amount and note instead of wiping them.
                shoppingListRepository.observeItemsIncludingDormant(id)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** The Shopping tab's FAB: pin the picker to the currently selected list. */
    fun openShoppingListPicker() {
        if (pickerListId.value == null) pickerListId.value = selectedListId.value
    }

    fun closeShoppingListPicker() {
        pickerListId.value = null
    }

    // One-shot feedback for use-ups (tap or scan) on the Use up tab.
    private val useUpChannel = Channel<UseUpScanResult>(Channel.BUFFERED)
    val useUpEvents = useUpChannel.receiveAsFlow()

    // A product whose unit makes "one tap = one unit" meaningless (500 g of
    // carrots): the UI asks how much was used before subtracting anything.
    private val pendingUseUpFlow = MutableStateFlow<Product?>(null)
    val pendingUseUp = pendingUseUpFlow.asStateFlow()

    // One-shot "item removed" events so the UI can offer Undo.
    private val itemRemovedChannel = Channel<RemovedShoppingItem>(Channel.BUFFERED)
    val itemRemovedEvents = itemRemovedChannel.receiveAsFlow()

    // One-shot "item added from the picker" events so the UI can reveal the row.
    private val itemAddedChannel = Channel<AddedShoppingItem>(Channel.BUFFERED)
    val itemAddedEvents = itemAddedChannel.receiveAsFlow()

    // One-shot plain messages (string resource ids) for things the user should
    // hear about but cannot act on.
    private val messageChannel = Channel<Int>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private data class ProductsBundle(
        val active: List<Product>,
        val archived: List<Product>,
        val barcodes: List<io.github.rafalpawlisz.shelfie.model.ProductBarcode>,
        val planned: List<io.github.rafalpawlisz.shelfie.model.PlannedEntry>,
        val referenced: List<String>,
    )

    val uiState: StateFlow<PantryUiState> =
        combine(
            combine(
                repository.observeProducts(),
                repository.observeArchivedProducts(),
                barcodeRepository.observeBarcodes(),
                shoppingListRepository.observePlannedEntries(),
                shoppingListRepository.observeReferencedProductIds(),
            ) { active, archived, barcodes, planned, referenced ->
                ProductsBundle(active, archived, barcodes, planned, referenced)
            },
            shoppingListRepository.observeLists(),
            shoppingListRepository.observeArchivedLists(),
            selectedListId,
            // Paired rather than given their own slot: combine's typed overload
            // stops at five flows, and these are read by the same screen.
            combine(
                shoppingItems,
                shoppingListRepository.observeOneOffSuggestions(),
                shoppingListRepository.observeListItemCounts(),
            ) { items, suggestions, counts -> Triple(items, suggestions, counts) },
        ) { (active, archived, barcodes, planned, referenced),
            lists,
            archivedLists,
            selected,
            (items, oneOffSuggestions, itemCounts),
            ->
            val plannedByProduct = planned
                .groupBy({ it.productId }, { it.listId })
                .mapValues { (_, listIds) -> listIds.toSet() }
            PantryUiState(
                products = active,
                archivedProducts = archived,
                lowStockProducts = active.filter { product ->
                    val min = product.minQuantity
                    min != null && product.quantity < min && product.id !in plannedByProduct
                },
                plannedByProduct = plannedByProduct,
                referencedProductIds = referenced.toSet(),
                lists = lists,
                archivedLists = archivedLists,
                emptyListIds = lists
                    .filter { (itemCounts[it.id] ?: 0) == 0 }
                    .map { it.id }
                    .toSet(),
                selectedListId = selected,
                shoppingList = items,
                oneOffSuggestions = oneOffSuggestions,
                barcodesByProduct = barcodes.groupBy({ it.productId }, { it.barcode }),
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PantryUiState(isLoading = true),
        )

    init {
        // Keep the selection valid as lists appear/disappear.
        viewModelScope.launch {
            shoppingListRepository.observeLists().collect { lists ->
                val current = selectedListId.value
                selectedListId.value = when {
                    lists.isEmpty() -> null
                    lists.any { it.id == current } -> current
                    else -> lists.first().id
                }
            }
        }
    }

    fun addProduct(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int? = null,
        notes: String? = null,
        emoji: String? = null,
        expiresOn: String? = null,
        barcodes: List<String> = emptyList(),
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            resolveProduct(trimmed, quantity, unit, minQuantity, notes, emoji, expiresOn, barcodes)
        }
    }

    fun updateProduct(
        id: String,
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int? = null,
        notes: String? = null,
        emoji: String? = null,
        expiresOn: String? = null,
        addedBarcodes: List<String> = emptyList(),
        removedBarcodes: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            repository.updateProduct(
                id, name, quantity, unit, minQuantity, notes, emoji, expiresOn,
            )
            // The form reports what the user changed, so a barcode that the
            // household added while the form was open is left alone instead of
            // looking like a removal. Removals are scoped to this product.
            addedBarcodes.forEach { barcodeRepository.addBarcode(id, it) }
            removedBarcodes.forEach { barcodeRepository.removeBarcode(id, it) }
        }
    }

    fun decrement(id: String) {
        viewModelScope.launch {
            val product = repository.getActiveProduct(id) ?: return@launch
            if (product.quantity <= 0) return@launch
            if (product.unit != null) {
                pendingUseUpFlow.value = product
            } else {
                useUpChannel.send(
                    UseUpScanResult.Used(
                        productId = product.id,
                        productName = product.name,
                        suggestion = useUpProduct(product, amount = 1),
                    ),
                )
            }
        }
    }

    fun useUpByBarcode(code: String) {
        viewModelScope.launch {
            val productId = barcodeRepository.findProductId(code)
            // Fetch straight from the repository, not uiState: returning from the
            // scanner's own activity can leave the WhileSubscribed uiState stale.
            // Only active products can be used up; archived/unknown → UnknownCode.
            val product = productId?.let { repository.getActiveProduct(it) }
            when {
                product == null -> useUpChannel.send(UseUpScanResult.UnknownCode(code))
                product.quantity <= 0 ->
                    useUpChannel.send(UseUpScanResult.OutOfStock(product.name))
                // A unit makes the amount the user's call, same as a tap.
                product.unit != null -> pendingUseUpFlow.value = product
                else -> useUpChannel.send(
                    UseUpScanResult.Used(
                        productId = product.id,
                        productName = product.name,
                        suggestion = useUpProduct(product, amount = 1),
                    ),
                )
            }
        }
    }

    /** The amount dialog's OK: subtract the typed amount and clear the prompt. */
    fun confirmUseUp(amount: Int) {
        val product = pendingUseUpFlow.value ?: return
        pendingUseUpFlow.value = null
        if (amount <= 0) return
        viewModelScope.launch {
            // The dialog refuses more than is in stock; this keeps a stale or
            // out-of-band amount from driving the stock negative.
            val used = minOf(amount, product.quantity)
            if (used <= 0) return@launch
            useUpChannel.send(
                UseUpScanResult.Used(
                    productId = product.id,
                    productName = product.name,
                    amount = used,
                    suggestion = useUpProduct(product, amount = used),
                ),
            )
        }
    }

    fun cancelUseUp() {
        pendingUseUpFlow.value = null
    }

    /** Undo of the last use-up snackbar: put the used amount back. */
    fun undoUseUp(productId: String, amount: Int = 1) {
        viewModelScope.launch { repository.adjustQuantity(productId, delta = +amount) }
    }

    /**
     * Consume [amount] units and return a restock suggestion when the stock
     * lands below the product's minimum. Fires on every such use-up (the amount
     * grows as the stock shrinks); the "already on a list" check is what keeps
     * it from nagging. Whether the UI offers an Add action (there may be no
     * lists) is the UI's call.
     */
    private suspend fun useUpProduct(product: Product, amount: Int): LowStockSuggestion? {
        repository.adjustQuantity(product.id, delta = -amount)
        val min = product.minQuantity ?: return null
        val newQuantity = product.quantity - amount
        if (newQuantity >= min) return null
        if (shoppingListRepository.isOnAnyList(product.id)) return null
        return LowStockSuggestion(
            productId = product.id,
            productName = product.name,
            suggestedAmount = maxOf(1, min - newQuantity),
        )
    }

    fun selectList(id: String) {
        selectedListId.value = id
    }

    fun createList(name: String) {
        viewModelScope.launch {
            // Newly created list becomes the selected one.
            selectedListId.value = shoppingListRepository.createList(name)
        }
    }

    fun renameList(id: String, name: String) {
        viewModelScope.launch { shoppingListRepository.renameList(id, name) }
    }

    /** The aisle order for one shop; shops are laid out differently. */
    fun setSectionOrder(listId: String, order: List<ProductCategory>) {
        viewModelScope.launch { shoppingListRepository.setSectionOrder(listId, order) }
    }

    /**
     * Persist a manual reorder of the lists: place [draggedId] right before
     * [beforeId] (null = to the end). Id-based rather than index-based, because
     * the chips row may show a subset (empty lists hidden), so the drag target
     * arrives as "the chip now following the dragged one". Only the moved
     * list's position changes — the midpoint between its new neighbours
     * (fractional indexing); the neighbours' ids are what pins that spot.
     */
    fun moveList(draggedId: String, beforeId: String?) {
        val lists = uiState.value.lists
        val moved = lists.firstOrNull { it.id == draggedId } ?: return
        val without = lists.filterNot { it.id == draggedId }
        // null = "to the end" (reinsert after the last list); -1 is a target
        // that vanished mid-drag, which leaves the order untouched.
        val beforeIndex = if (beforeId == null) without.size
        else without.indexOfFirst { it.id == beforeId }
        if (beforeIndex == -1) return
        val prev = without.getOrNull(beforeIndex - 1)?.position
        val next = without.getOrNull(beforeIndex)?.position
        val newPosition = when {
            prev == null && next == null -> moved.position
            prev == null -> next!! - 1.0
            next == null -> prev + 1.0
            else -> (prev + next) / 2.0
        }
        viewModelScope.launch {
            shoppingListRepository.setListPosition(moved.id, newPosition)
        }
    }

    fun archiveList(id: String) {
        // Reversible soft delete; the reconciler reselects another active list
        // (or null). Items and manual order stay put for a later restore.
        viewModelScope.launch { shoppingListRepository.archiveList(id) }
    }

    fun restoreList(id: String) {
        viewModelScope.launch { shoppingListRepository.restoreList(id) }
    }

    fun deleteList(id: String) {
        // Permanent (only reachable from the archive view); items cascade.
        viewModelScope.launch { shoppingListRepository.deleteList(id) }
    }

    fun addToShoppingList(productId: String, amount: Int?, note: String? = null) {
        // The picker's captured list wins over the live selection: the dialog
        // shows and writes against one list. Outside the picker (no capture)
        // this falls back to the selected list, as always.
        val listId = pickerListId.value ?: selectedListId.value ?: return
        viewModelScope.launch {
            val itemId = planOnList(listId, productId, amount, note)
            itemAddedChannel.send(
                AddedShoppingItem(listId, itemId, System.currentTimeMillis()),
            )
        }
    }

    /** Add to an explicitly chosen list (restock dialog) and remember the choice. */
    fun addToList(listId: String, productId: String, amount: Int?) {
        uiPreferences.lastRestockListId = listId
        // No added-item event: the restock dialog names its own list, which may
        // not be the one on screen — a reveal there would scroll a list nobody
        // is looking at.
        viewModelScope.launch { planOnList(listId, productId, amount, note = null) }
    }

    /**
     * Put a product on a list, bringing it back from the archive if that is
     * where it was.
     *
     * The restore is not a courtesy: observeItems joins products with
     * `archivedAt IS NULL`, so an item pointing at an archived product is
     * invisible — planning one would add a row nobody can see. Wanting to buy
     * something is also the plainest possible statement that it belongs in the
     * pantry again.
     *
     * Returns the row the item landed on (see [ShoppingListRepository.addItem]).
     */
    private suspend fun planOnList(
        listId: String,
        productId: String,
        amount: Int?,
        note: String?,
    ): String {
        if (repository.getActiveProduct(productId) == null) {
            repository.restoreProduct(productId)
        }
        return shoppingListRepository.addItem(listId, productId, amount, note)
    }

    /**
     * The product just created from the shopping-list picker, so the picker can
     * carry on with it. Null once the UI has taken it, which
     * [clearProductForList] says explicitly rather than leaving a stale id to
     * reopen the dialog on the next recomposition.
     */
    private val _productForList = MutableStateFlow<String?>(null)
    val productForList: StateFlow<String?> = _productForList

    fun clearProductForList() {
        _productForList.value = null
    }

    /**
     * Create a product from inside the list picker, through the same full form
     * the Products tab uses — one meaning for "add a product", not two.
     * Publishes the id through [productForList] so the picker can continue to
     * the amount step with it.
     */
    fun addProductForList(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int? = null,
        notes: String? = null,
        emoji: String? = null,
        expiresOn: String? = null,
        barcodes: List<String> = emptyList(),
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _productForList.value = resolveProduct(
                trimmed, quantity, unit, minQuantity, notes, emoji, expiresOn, barcodes,
            )
        }
    }

    /**
     * Reach the product of that name, creating one only when the pantry has
     * none: two "Mleko" split the barcode a scan finds, the low-stock list and
     * what the other phone sees. Both forms come through here.
     *
     * An existing product's stored details are LEFT ALONE. The forms warn about
     * a taken name while it is typed, so this is the race guard — the other
     * device created or archived that name while the form was open — and in a
     * race the established product is worth more than a form the user filled
     * believing it was blank. Overwriting here once emptied an archived
     * product's unit, minimum and notes, because that form is blank by design.
     * A product coming back from the archive says so, rather than leaving the
     * restore to be discovered.
     *
     * Barcodes are the exception, being additive: a code scanned into the form
     * is new information about the product, not a replacement for it.
     */
    private suspend fun resolveProduct(
        name: String,
        quantity: Int,
        unit: String?,
        minQuantity: Int?,
        notes: String?,
        emoji: String?,
        expiresOn: String?,
        barcodes: List<String>,
    ): String {
        val existing = repository.findByName(name)
        val id = if (existing == null) {
            repository.addProduct(name, quantity, unit, minQuantity, notes, emoji, expiresOn)
        } else {
            // Whether it is archived is asked of the database rather than of
            // uiState, which reports nothing when the screen is not collecting.
            if (repository.getActiveProduct(existing.id) == null) {
                repository.restoreProduct(existing.id)
                messageChannel.send(R.string.product_back_from_archive)
            }
            existing.id
        }
        barcodes.forEach { barcodeRepository.addBarcode(id, it) }
        return id
    }

    /**
     * "Add all" on the derived low-stock list: put every below-minimum product
     * on the chosen list without an amount (it's asked for at check-off).
     */
    fun addLowStockToList(listId: String) {
        val products = uiState.value.lowStockProducts
        if (products.isEmpty()) return
        uiPreferences.lastRestockListId = listId
        viewModelScope.launch {
            products.forEach { shoppingListRepository.addItem(listId, it.id, amount = null) }
        }
    }

    /** Which list the restock dialog should preselect: last used, else selected, else first. */
    fun defaultRestockListId(): String? {
        val lists = uiState.value.lists
        val remembered = uiPreferences.lastRestockListId
        return when {
            lists.any { it.id == remembered } -> remembered
            selectedListId.value != null -> selectedListId.value
            else -> lists.firstOrNull()?.id
        }
    }

    fun setShoppingItemChecked(id: String, checked: Boolean) {
        viewModelScope.launch { shoppingListRepository.setChecked(id, checked) }
    }

    /**
     * Row-tap edit: amount and the one-off note saved together; a different
     * [targetListId] additionally moves the item to that list.
     */
    fun updateShoppingItem(
        id: String,
        amount: Int?,
        unit: String? = null,
        note: String?,
        targetListId: String? = null,
        sectionEmoji: String? = null,
    ) {
        if (amount != null && amount <= 0) return
        viewModelScope.launch {
            shoppingListRepository.setItemDetails(id, amount, unit, note, sectionEmoji)
            if (targetListId != null) shoppingListRepository.moveItem(id, targetListId)
        }
    }

    /**
     * Check off an item that had no amount: the check-off dialog just asked how
     * many were bought. One coroutine so the amount lands before the check.
     */
    fun checkWithAmount(id: String, amount: Int) {
        if (amount <= 0) return
        viewModelScope.launch {
            shoppingListRepository.setItemAmount(id, amount)
            shoppingListRepository.setChecked(id, checked = true)
        }
    }

    fun removeShoppingItem(id: String) {
        // Snapshot before deleting so the snackbar can offer Undo.
        val item = uiState.value.shoppingList.firstOrNull { it.id == id }
        val listId = selectedListId.value
        viewModelScope.launch {
            shoppingListRepository.removeItem(id)
            if (item != null && listId != null) {
                itemRemovedChannel.send(
                    RemovedShoppingItem(
                        listId = listId,
                        productId = item.productId,
                        productName = item.productName,
                        amount = item.amount,
                        // Only a one-off's own unit is worth carrying: a product
                        // row reads its unit off the product on the way back.
                        unit = item.productUnit.takeIf { item.productId == null },
                        sectionEmoji = item.sectionEmoji,
                        note = item.note,
                    ),
                )
            }
        }
    }

    /**
     * Undo of a remove: put the item back on its list with the same amount and
     * note. The manual position survives removal (product_list_order is kept),
     * so the item returns to its old slot; it comes back unchecked.
     */
    fun undoRemoveItem(removed: RemovedShoppingItem) {
        viewModelScope.launch {
            // The snackbar outlives its list: ten seconds is enough to archive
            // the list (fine — the item returns to a dormant list) or delete it
            // for good, and inserting into a list that is gone trips the
            // foreign key and takes the app down.
            if (!shoppingListRepository.listExists(removed.listId)) {
                messageChannel.send(R.string.undo_list_gone)
                return@launch
            }
            if (removed.productId == null) {
                // A one-off comes back as a fresh line under the same name —
                // there is no product row for it to return to.
                shoppingListRepository.addOneOffItem(
                    removed.listId,
                    removed.productName,
                    removed.amount,
                    removed.unit,
                    removed.note,
                    removed.sectionEmoji,
                )
            } else {
                shoppingListRepository.addItem(
                    removed.listId,
                    removed.productId,
                    removed.amount,
                    removed.note,
                )
            }
        }
    }

    fun finishShopping() {
        val listId = selectedListId.value ?: return
        viewModelScope.launch { shoppingListRepository.finishShopping(listId) }
    }

    /**
     * Persist a manual reorder: move the item at [fromIndex] to [toIndex] within
     * the current sorted shopping list.
     *
     * The whole aisle is renumbered 1, 2, 3… in its new order rather than the
     * moved row alone taking the midpoint between its neighbours. Midpoints were
     * cheaper — one row written — but they assume every row in a section already
     * carries a comparable slot, and two things break that: a product's slot
     * lives in product_list_order while a one-off's lives on its own row, and an
     * undragged one-off has no slot at all (it sorts by creation time, a number
     * in the trillions). Borrowing across that gap wrote a timestamp into a
     * product's slot, which outlives the row and stranded the product at the end
     * of its aisle for good. Renumbering has no gap to cross, cannot tie, and
     * repairs any slot an earlier drag mangled. A section is a handful of rows.
     *
     * Returns whether a write was actually dispatched. The caller's drag mirror
     * holds the dropped order until Room echoes it back — but a declined move
     * (indices gone stale mid-drag under a household emission, a drop outside
     * the aisle) echoes nothing, and a mirror waiting for that echo would show
     * the unpersisted order until some unrelated change happened to land. All
     * the validation is synchronous, so the caller learns before the write.
     */
    fun moveShoppingItem(fromIndex: Int, toIndex: Int): Boolean {
        val listId = selectedListId.value ?: return false
        val items = uiState.value.shoppingList
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) {
            return false
        }
        val moved = items[fromIndex]
        // Only unchecked items carry a manual position; checked items are parked at
        // the bottom by check time and aren't repositioned by dragging.
        if (moved.isChecked) return false
        val movedSection = sectionOf(moved)
        // Rows the moved one can be ordered against: same aisle, still to buy.
        fun ShoppingListItem.sameAisle(): Boolean =
            !isChecked && sectionOf(this) == movedSection
        val without = items.toMutableList().apply { removeAt(fromIndex) }
        // A drag never crosses a section: the section is the product's, not the
        // row's, so dropping into another aisle could not stick — the sort would
        // put the row straight back. With nothing of this aisle on either side of
        // the drop, the drag left it entirely; the resync snaps the row back.
        val landsInAisle = without.getOrNull(toIndex - 1)?.sameAisle() == true ||
            without.getOrNull(toIndex)?.sameAisle() == true
        if (!landsInAisle) return false
        val reordered = without.apply { add(toIndex, moved) }
        val slots = reordered.filter { it.sameAisle() }
            .mapIndexed { index, item ->
                ItemSlot(
                    itemId = item.id,
                    productId = item.productId,
                    position = index + 1.0,
                )
            }
        viewModelScope.launch { shoppingListRepository.setItemPositions(listId, slots) }
        return true
    }

    // null covers one-offs, pre-section emoji and "no section" alike — they all
    // share the trailing sectionless group.
    private fun sectionOf(item: ShoppingListItem): ProductCategory? =
        ProductCategory.fromEmoji(item.productEmoji)

    /** A one-off onto the picker's captured list (or the selection); see [ShoppingListRepository.addOneOffItem]. */
    fun addOneOffToShoppingList(
        name: String,
        amount: Int?,
        unit: String? = null,
        note: String? = null,
        sectionEmoji: String? = null,
    ) {
        val listId = pickerListId.value ?: selectedListId.value ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val itemId = shoppingListRepository.addOneOffItem(
                listId, name, amount, unit, note, sectionEmoji,
            )
            if (itemId.isNotEmpty()) {
                itemAddedChannel.send(
                    AddedShoppingItem(listId, itemId, System.currentTimeMillis()),
                )
            }
        }
    }

    /** Drops a remembered one-off name from the picker's suggestions. */
    fun forgetOneOffSuggestion(name: String) {
        viewModelScope.launch { shoppingListRepository.forgetOneOffSuggestion(name) }
    }

    fun archive(id: String) {
        viewModelScope.launch { repository.archiveProduct(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restoreProduct(id) }
    }

    /**
     * Delete an archived product for good. The UI only offers this for a
     * product no list refers to, but the repository checks again and can
     * refuse: the other device may have put it on a list in the meantime, and
     * deleting it would take that item down with it.
     */
    fun deleteArchived(id: String) {
        viewModelScope.launch {
            if (!repository.deleteArchivedProduct(id)) {
                messageChannel.send(R.string.delete_product_in_use)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as ShelfieApplication
                PantryViewModel(
                    app.container.productRepository,
                    app.container.shoppingListRepository,
                    app.container.barcodeRepository,
                    app.container.uiPreferences,
                )
            }
        }
    }
}
