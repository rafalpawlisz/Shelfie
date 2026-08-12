package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.model.ProductCategory
import io.github.rafalpawlisz.shelfie.ui.pantry.LowStockSuggestion
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
import io.github.rafalpawlisz.shelfie.ui.pantry.RemovedShoppingItem
import io.github.rafalpawlisz.shelfie.ui.pantry.UseUpScanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun makeViewModel(
        repository: FakeProductRepository,
        uiPreferences: FakeUiPreferences = FakeUiPreferences(),
    ) = PantryViewModel(
        repository,
        FakeShoppingListRepository(repository),
        FakeBarcodeRepository(),
        uiPreferences,
    )

    // Keeps the WhileSubscribed uiState hot so reads see the latest state.
    private fun TestScope.observe(viewModel: PantryViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    // Restock hints ride inside Used events on the unified use-up channel.
    private fun TestScope.collectLowStock(viewModel: PantryViewModel): List<LowStockSuggestion> {
        val events = mutableListOf<LowStockSuggestion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.useUpEvents.collect { result ->
                (result as? UseUpScanResult.Used)?.suggestion?.let { events.add(it) }
            }
        }
        return events
    }

    // --- Selection & list management ---

    @Test
    fun `creating a list selects it and starts empty`() = runTest {
        val viewModel = makeViewModel(FakeProductRepository())
        observe(viewModel)

        viewModel.createList("Lidl")

        val state = viewModel.uiState.value
        assertEquals(1, state.lists.size)
        assertEquals(state.lists.single().id, state.selectedListId)
        assertTrue(state.shoppingList.isEmpty())
    }

    @Test
    fun `creating a second list makes the newest selected`() = runTest {
        val viewModel = makeViewModel(FakeProductRepository())
        observe(viewModel)

        viewModel.createList("Lidl")
        viewModel.createList("Auchan")

        val state = viewModel.uiState.value
        assertEquals(2, state.lists.size)
        assertEquals("Auchan", state.lists.first { it.id == state.selectedListId }.name)
    }

    @Test
    fun `selectList switches the visible items`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val milk = viewModel.uiState.value.products.first { it.name == "Milk" }.id
        val bread = viewModel.uiState.value.products.first { it.name == "Bread" }.id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(milk, 1)
        viewModel.createList("Auchan")
        val auchan = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(bread, 1)

        viewModel.selectList(lidl)
        assertEquals(listOf("Milk"), viewModel.uiState.value.shoppingList.map { it.productName })

        viewModel.selectList(auchan)
        assertEquals(listOf("Bread"), viewModel.uiState.value.shoppingList.map { it.productName })
    }

    @Test
    fun `deleting the selected list reselects the first remaining and drops its items`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Auchan")
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(productId, 1)

        viewModel.deleteList(lidl)

        val state = viewModel.uiState.value
        assertEquals(listOf("Auchan"), state.lists.map { it.name })
        assertEquals(state.lists.single().id, state.selectedListId)
        assertTrue(state.shoppingList.isEmpty())
    }

    @Test
    fun `deleting the last list clears selection and empties items`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(productId, 1)

        viewModel.deleteList(lidl)

        val state = viewModel.uiState.value
        assertTrue(state.lists.isEmpty())
        assertNull(state.selectedListId)
        assertTrue(state.shoppingList.isEmpty())
    }

    @Test
    fun `addToShoppingList is a no-op when no list is selected`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id

        viewModel.addToShoppingList(productId, 1)

        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
        assertTrue(viewModel.uiState.value.lists.isEmpty())
    }

    // --- Archiving lists ---

    @Test
    fun `archiving the selected list hides it, reselects, and moves it to the archive`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Auchan")
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(productId, 1)

        viewModel.archiveList(lidl)

        val state = viewModel.uiState.value
        assertEquals(listOf("Auchan"), state.lists.map { it.name })
        assertEquals(listOf("Lidl"), state.archivedLists.map { it.name })
        assertEquals(state.lists.single().id, state.selectedListId) // reselected the survivor
    }

    @Test
    fun `restoring a list brings it back with its items and manual order`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        listOf("Apples", "Bread", "Cheese").forEach { viewModel.addToShoppingList(pid(it), 1) }
        viewModel.moveShoppingItem(fromIndex = 2, toIndex = 0) // [Cheese, Apples, Bread]

        viewModel.archiveList(lidl)
        assertTrue(viewModel.uiState.value.lists.isEmpty())
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())

        viewModel.restoreList(lidl)

        assertEquals(listOf("Lidl"), viewModel.uiState.value.lists.map { it.name })
        assertTrue(viewModel.uiState.value.archivedLists.isEmpty())
        // Items and the custom order survived the round trip.
        assertEquals(
            listOf("Cheese", "Apples", "Bread"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `permanently deleting an archived list removes it from the archive`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(productId, 1)
        viewModel.archiveList(lidl)
        assertEquals(listOf("Lidl"), viewModel.uiState.value.archivedLists.map { it.name })

        viewModel.deleteList(lidl)

        assertTrue(viewModel.uiState.value.lists.isEmpty())
        assertTrue(viewModel.uiState.value.archivedLists.isEmpty())
    }

    // --- Reordering lists ---

    @Test
    fun `moveList reorders the lists`() = runTest {
        val viewModel = makeViewModel(FakeProductRepository())
        observe(viewModel)
        // Non-alphabetical creation order proves the order is manual, not by name.
        viewModel.createList("Lidl")
        viewModel.createList("Auchan")
        viewModel.createList("Biedronka")
        assertEquals(
            listOf("Lidl", "Auchan", "Biedronka"),
            viewModel.uiState.value.lists.map { it.name },
        )

        // Drag Biedronka (index 2) to the front.
        viewModel.moveList(fromIndex = 2, toIndex = 0)

        assertEquals(
            listOf("Biedronka", "Lidl", "Auchan"),
            viewModel.uiState.value.lists.map { it.name },
        )
    }

    @Test
    fun `a new list is appended at the end`() = runTest {
        val viewModel = makeViewModel(FakeProductRepository())
        observe(viewModel)
        viewModel.createList("Biedronka")
        viewModel.createList("Auchan")

        // "Aldi" sorts first alphabetically but must land last (append, not by name).
        viewModel.createList("Aldi")

        assertEquals(
            listOf("Biedronka", "Auchan", "Aldi"),
            viewModel.uiState.value.lists.map { it.name },
        )
    }

    @Test
    fun `list order survives archive and restore`() = runTest {
        val viewModel = makeViewModel(FakeProductRepository())
        observe(viewModel)
        viewModel.createList("Lidl")
        viewModel.createList("Auchan")
        viewModel.createList("Biedronka")
        viewModel.moveList(fromIndex = 2, toIndex = 0) // [Biedronka, Lidl, Auchan]
        val lidl = viewModel.uiState.value.lists.first { it.name == "Lidl" }.id

        viewModel.archiveList(lidl)
        assertEquals(
            listOf("Biedronka", "Auchan"),
            viewModel.uiState.value.lists.map { it.name },
        )

        viewModel.restoreList(lidl)

        // Lidl returns to its own slot, not the end.
        assertEquals(
            listOf("Biedronka", "Lidl", "Auchan"),
            viewModel.uiState.value.lists.map { it.name },
        )
    }

    // --- Derived low-stock ("Braki") list ---

    @Test
    fun `lowStockProducts lists unplanned products below their minimum`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 2, unit = "l", minQuantity = 4) // low
        repository.addProduct(name = "Bread", quantity = 5, unit = null, minQuantity = 2) // fine
        repository.addProduct(name = "Cheese", quantity = 0, unit = null) // no minimum
        val viewModel = makeViewModel(repository)
        observe(viewModel)

        assertEquals(listOf("Milk"), viewModel.uiState.value.lowStockProducts.map { it.name })
    }

    @Test
    fun `a planned product drops off the low-stock list reactively`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l", minQuantity = 3)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        assertEquals(1, viewModel.uiState.value.lowStockProducts.size)

        viewModel.addToShoppingList(productId, amount = null)
        assertTrue(viewModel.uiState.value.lowStockProducts.isEmpty())

        // Removing it from the list makes it "unplanned" (and low) again.
        viewModel.removeShoppingItem(viewModel.uiState.value.shoppingList.single().id)
        assertEquals(1, viewModel.uiState.value.lowStockProducts.size)
    }

    @Test
    fun `an archived product never shows as low stock`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l", minQuantity = 3)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id

        viewModel.archive(productId)

        assertTrue(viewModel.uiState.value.lowStockProducts.isEmpty())
    }

    @Test
    fun `addLowStockToList puts every shortage on the chosen list without amounts`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l", minQuantity = 3)
        repository.addProduct(name = "Bread", quantity = 0, unit = null, minQuantity = 1)
        val prefs = FakeUiPreferences()
        val viewModel = makeViewModel(repository, prefs)
        observe(viewModel)
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.createList("Auchan") // selected now
        assertEquals(2, viewModel.uiState.value.lowStockProducts.size)

        viewModel.addLowStockToList(lidl) // NOT the selected list

        assertTrue(viewModel.uiState.value.lowStockProducts.isEmpty())
        assertEquals(lidl, prefs.lastRestockListId)
        viewModel.selectList(lidl)
        val items = viewModel.uiState.value.shoppingList
        assertEquals(setOf("Milk", "Bread"), items.map { it.productName }.toSet())
        assertTrue(items.all { it.amount == null })
    }

    // --- Low-stock restock suggestions ---

    @Test
    fun `use-up below the minimum emits a suggestion whose amount grows`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 3, unit = "l", minQuantity = 3)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val events = collectLowStock(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")

        viewModel.decrement(productId) // 2 < 3
        viewModel.decrement(productId) // 1 < 3

        assertEquals(listOf(1, 2), events.map { it.suggestedAmount })
        assertEquals("Milk", events.first().productName)
    }

    @Test
    fun `no suggestion without a minimum quantity`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val events = collectLowStock(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")

        viewModel.decrement(productId)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `no suggestion when the product is already on a list`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 2, unit = "l", minQuantity = 3)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val events = collectLowStock(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = 2)

        viewModel.decrement(productId)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `suggestion still emitted when there are no lists`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 2, unit = "l", minQuantity = 3)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val events = collectLowStock(viewModel)
        val productId = viewModel.uiState.value.products.single().id

        viewModel.decrement(productId)

        // The UI decides whether an Add action makes sense; the info always flows.
        assertEquals(1, events.size)
    }

    @Test
    fun `scan use-up folds the suggestion into the Used event`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val scans = mutableListOf<UseUpScanResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.useUpEvents.collect { scans.add(it) }
        }
        viewModel.addProduct(
            name = "Milk", quantity = 2, unit = "l", minQuantity = 3,
            barcodes = listOf("5901234123457"),
        )
        viewModel.createList("Lidl")

        viewModel.useUpByBarcode("5901234123457")

        // ONE event carrying both the outcome and the hint.
        val used = scans.single() as UseUpScanResult.Used
        assertEquals("Milk", used.productName)
        assertEquals(2, used.suggestion?.suggestedAmount) // min 3 − new stock 1
    }

    @Test
    fun `undoUseUp puts the unit back`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 2, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id

        viewModel.decrement(productId)
        assertEquals(1, viewModel.uiState.value.products.single().quantity)

        viewModel.undoUseUp(productId)
        assertEquals(2, viewModel.uiState.value.products.single().quantity)
    }

    @Test
    fun `addToList adds to the chosen list and remembers it`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val prefs = FakeUiPreferences()
        val viewModel = makeViewModel(repository, prefs)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.createList("Auchan") // selected now

        viewModel.addToList(lidl, productId, amount = 2) // NOT the selected list

        assertEquals(lidl, prefs.lastRestockListId)
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty()) // Auchan untouched
        viewModel.selectList(lidl)
        assertEquals(2, viewModel.uiState.value.shoppingList.single().amount)
    }

    @Test
    fun `defaultRestockListId prefers the remembered list and ignores stale ids`() = runTest {
        val repository = FakeProductRepository()
        val prefs = FakeUiPreferences()
        val viewModel = makeViewModel(repository, prefs)
        observe(viewModel)
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.createList("Auchan")
        val auchan = viewModel.uiState.value.selectedListId!!

        // Nothing remembered -> the currently selected list.
        assertEquals(auchan, viewModel.defaultRestockListId())

        prefs.lastRestockListId = lidl
        assertEquals(lidl, viewModel.defaultRestockListId())

        // A remembered id that no longer exists falls back to the selection.
        prefs.lastRestockListId = "gone"
        assertEquals(auchan, viewModel.defaultRestockListId())
    }

    // --- Item behavior within the selected list ---

    @Test
    fun `addToShoppingList adds an unchecked item with product data`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l", emoji = "M")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")

        viewModel.addToShoppingList(productId, amount = 4)

        val item = viewModel.uiState.value.shoppingList.single()
        assertEquals(4, item.amount)
        assertFalse(item.isChecked)
        assertEquals("Milk", item.productName)
        assertEquals("M", item.productEmoji)
        assertEquals("l", item.productUnit)
    }

    @Test
    fun `re-adding a product replaces the existing entry's values`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")

        // The add dialog pre-fills the current values for an already-listed
        // product, so confirming REPLACES them — no merge math.
        viewModel.addToShoppingList(productId, amount = 3)
        viewModel.addToShoppingList(productId, amount = 1)

        val item = viewModel.uiState.value.shoppingList.single()
        assertEquals(1, item.amount)
        assertFalse(item.isChecked)
    }

    @Test
    fun `adding over a checked item replaces it and leaves quantity untouched`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = 3)
        val itemId = viewModel.uiState.value.shoppingList.single().id
        viewModel.setShoppingItemChecked(itemId, checked = true)
        assertEquals(0, viewModel.uiState.value.products.single().quantity)

        viewModel.addToShoppingList(productId, amount = 2)

        val item = viewModel.uiState.value.shoppingList.single()
        assertFalse(item.isChecked)
        assertEquals(2, item.amount)
        assertEquals(0, viewModel.uiState.value.products.single().quantity)
    }

    @Test
    fun `an item can be added without an amount and re-adding sets exactly what was confirmed`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")

        viewModel.addToShoppingList(productId, amount = null)
        assertNull(viewModel.uiState.value.shoppingList.single().amount)

        viewModel.addToShoppingList(productId, amount = 5)
        assertEquals(5, viewModel.uiState.value.shoppingList.single().amount)

        // Replace semantics: clearing the (pre-filled) field really clears it.
        viewModel.addToShoppingList(productId, amount = null)
        assertNull(viewModel.uiState.value.shoppingList.single().amount)
    }

    @Test
    fun `checkWithAmount records the bought amount, checks the item, and checkout banks it`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = null)
        val itemId = viewModel.uiState.value.shoppingList.single().id

        viewModel.checkWithAmount(itemId, amount = 3)

        val item = viewModel.uiState.value.shoppingList.single()
        assertTrue(item.isChecked)
        assertEquals(3, item.amount)

        viewModel.finishShopping()
        assertEquals(4, viewModel.uiState.value.products.single().quantity)
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
    }

    @Test
    fun `a checked item without an amount does not change stock at checkout`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = 2)
        val itemId = viewModel.uiState.value.shoppingList.single().id
        viewModel.setShoppingItemChecked(itemId, checked = true)
        // Deliberately clear the amount after checking (soft invariant).
        viewModel.updateShoppingItem(itemId, amount = null, note = null)

        viewModel.finishShopping()

        assertEquals(1, viewModel.uiState.value.products.single().quantity)
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
    }

    @Test
    fun `updateShoppingItem changes the amount and rejects non-positive values`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = 2)
        val itemId = viewModel.uiState.value.shoppingList.single().id

        viewModel.updateShoppingItem(itemId, amount = 5, note = null)
        assertEquals(5, viewModel.uiState.value.shoppingList.single().amount)

        viewModel.updateShoppingItem(itemId, amount = 0, note = null) // ignored
        assertEquals(5, viewModel.uiState.value.shoppingList.single().amount)
    }

    @Test
    fun `item note is set on add, replaced on re-add, and dies with the item at checkout`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")

        viewModel.addToShoppingList(productId, amount = 2, note = "the blue one")
        assertEquals("the blue one", viewModel.uiState.value.shoppingList.single().note)

        // Replace semantics: the dialog pre-fills the current note, so whatever
        // is confirmed (including a cleared field) is what sticks.
        viewModel.addToShoppingList(productId, amount = 1, note = "only on sale")
        assertEquals("only on sale", viewModel.uiState.value.shoppingList.single().note)

        // The row-tap edit can change or clear it.
        val itemId = viewModel.uiState.value.shoppingList.single().id
        viewModel.updateShoppingItem(itemId, amount = 3, note = "promo")
        assertEquals("promo", viewModel.uiState.value.shoppingList.single().note)
        assertEquals(3, viewModel.uiState.value.shoppingList.single().amount)

        // Checkout removes the item — a later re-add starts with no note.
        viewModel.setShoppingItemChecked(itemId, checked = true)
        viewModel.finishShopping()
        viewModel.addToShoppingList(productId, amount = 1)
        assertNull(viewModel.uiState.value.shoppingList.single().note)
    }

    @Test
    fun `checking an item does not change quantity and keeps it visible`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = 4)
        val itemId = viewModel.uiState.value.shoppingList.single().id

        viewModel.setShoppingItemChecked(itemId, checked = true)

        assertEquals(1, viewModel.uiState.value.products.single().quantity)
        assertTrue(viewModel.uiState.value.shoppingList.single().isChecked)
    }

    @Test
    fun `checking an already checked item keeps it checked and does not change quantity`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = 2)
        val itemId = viewModel.uiState.value.shoppingList.single().id

        viewModel.setShoppingItemChecked(itemId, checked = true)
        viewModel.setShoppingItemChecked(itemId, checked = true)

        assertEquals(0, viewModel.uiState.value.products.single().quantity)
        assertTrue(viewModel.uiState.value.shoppingList.single().isChecked)
    }

    @Test
    fun `unchecking an item does not change quantity`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 5, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = 3)
        val itemId = viewModel.uiState.value.shoppingList.single().id
        viewModel.setShoppingItemChecked(itemId, checked = true)
        assertEquals(5, viewModel.uiState.value.products.single().quantity)

        viewModel.setShoppingItemChecked(itemId, checked = false)

        assertEquals(5, viewModel.uiState.value.products.single().quantity)
        assertFalse(viewModel.uiState.value.shoppingList.single().isChecked)
    }

    @Test
    fun `finishShopping applies checked amounts, removes them, and keeps unchecked`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l")
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        fun product(name: String) = viewModel.uiState.value.products.first { it.name == name }
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(product("Milk").id, amount = 2)
        viewModel.addToShoppingList(product("Cheese").id, amount = 3)
        viewModel.addToShoppingList(product("Bread").id, amount = 1)
        val shopping = viewModel.uiState.value.shoppingList
        viewModel.setShoppingItemChecked(shopping.first { it.productName == "Milk" }.id, checked = true)
        viewModel.setShoppingItemChecked(shopping.first { it.productName == "Cheese" }.id, checked = true)
        assertEquals(1, product("Milk").quantity)

        viewModel.finishShopping()

        assertEquals(3, product("Milk").quantity)
        assertEquals(3, product("Cheese").quantity)
        assertEquals(0, product("Bread").quantity)
        val remaining = viewModel.uiState.value.shoppingList.single()
        assertEquals("Bread", remaining.productName)
    }

    @Test
    fun `finishShopping with nothing checked leaves items and quantities unchanged`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = 2)

        viewModel.finishShopping()

        assertEquals(1, viewModel.uiState.value.products.single().quantity)
        assertEquals(1, viewModel.uiState.value.shoppingList.size)
    }

    @Test
    fun `archiving a product hides its shopping list item and restore shows it`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(productId, amount = 1)
        assertEquals(1, viewModel.uiState.value.shoppingList.size)

        viewModel.archive(productId)
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())

        viewModel.restore(productId)
        assertEquals(1, viewModel.uiState.value.shoppingList.size)
    }

    // --- Manual order (drag to reorder; persisted per list+product) ---

    @Test
    fun `items follow insertion order until reordered`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        // Non-alphabetical add order proves it's insertion order, not name.
        listOf("Cheese", "Apples", "Bread").forEach { viewModel.addToShoppingList(pid(it), 1) }

        assertEquals(
            listOf("Cheese", "Apples", "Bread"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `checking sinks an item to the bottom, most recently checked on top`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        listOf("Apples", "Bread", "Cheese").forEach { viewModel.addToShoppingList(pid(it), 1) }
        fun itemId(name: String) = viewModel.uiState.value.shoppingList.first { it.productName == name }.id

        viewModel.setShoppingItemChecked(itemId("Apples"), checked = true)
        viewModel.setShoppingItemChecked(itemId("Cheese"), checked = true)

        // Bread stays on top (unchecked); the checked block below is newest-first.
        assertEquals(
            listOf("Bread" to false, "Cheese" to true, "Apples" to true),
            viewModel.uiState.value.shoppingList.map { it.productName to it.isChecked },
        )
    }

    @Test
    fun `unchecking returns an item to its manual position`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        listOf("Apples", "Bread", "Cheese").forEach { viewModel.addToShoppingList(pid(it), 1) }
        fun itemId(name: String) = viewModel.uiState.value.shoppingList.first { it.productName == name }.id

        viewModel.setShoppingItemChecked(itemId("Apples"), checked = true) // sinks to bottom
        assertEquals("Apples", viewModel.uiState.value.shoppingList.last().productName)

        viewModel.setShoppingItemChecked(itemId("Apples"), checked = false)

        // Back in its own slot (added first -> top of the list), all unchecked again.
        assertEquals(
            listOf("Apples", "Bread", "Cheese"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `moveShoppingItem reorders the list`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        listOf("Apples", "Bread", "Cheese").forEach { viewModel.addToShoppingList(pid(it), 1) }

        // Drag Cheese (index 2) to the front.
        viewModel.moveShoppingItem(fromIndex = 2, toIndex = 0)

        assertEquals(
            listOf("Cheese", "Apples", "Bread"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `a removed and re-added product returns to its manual position`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        listOf("Apples", "Bread", "Cheese").forEach { viewModel.addToShoppingList(pid(it), 1) }
        viewModel.moveShoppingItem(fromIndex = 2, toIndex = 0) // [Cheese, Apples, Bread]

        val cheese = viewModel.uiState.value.shoppingList.first { it.productName == "Cheese" }
        viewModel.removeShoppingItem(cheese.id)
        assertEquals(
            listOf("Apples", "Bread"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )

        viewModel.addToShoppingList(pid("Cheese"), 1)

        // Back at the front (its remembered slot), not appended at the end.
        assertEquals(
            listOf("Cheese", "Apples", "Bread"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `removing an item emits an undo event with the item's snapshot`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val removals = mutableListOf<RemovedShoppingItem>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.itemRemovedEvents.collect { removals.add(it) }
        }
        viewModel.createList("Lidl")
        val productId = viewModel.uiState.value.products.single().id
        viewModel.addToShoppingList(productId, amount = 3, note = "the blue one")

        viewModel.removeShoppingItem(viewModel.uiState.value.shoppingList.single().id)

        val removed = removals.single()
        assertEquals(viewModel.uiState.value.selectedListId, removed.listId)
        assertEquals(productId, removed.productId)
        assertEquals("Milk", removed.productName)
        assertEquals(3, removed.amount)
        assertEquals("the blue one", removed.note)
    }

    @Test
    fun `undoRemoveItem puts the item back with amount, note and its manual slot`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val removals = mutableListOf<RemovedShoppingItem>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.itemRemovedEvents.collect { removals.add(it) }
        }
        viewModel.createList("Lidl")
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        listOf("Apples", "Bread", "Cheese").forEach { viewModel.addToShoppingList(pid(it), 1) }
        viewModel.moveShoppingItem(fromIndex = 2, toIndex = 0) // [Cheese, Apples, Bread]
        val cheese = viewModel.uiState.value.shoppingList.first { it.productName == "Cheese" }
        viewModel.updateShoppingItem(cheese.id, amount = 2, note = "gouda")

        viewModel.removeShoppingItem(cheese.id)
        viewModel.undoRemoveItem(removals.single())

        // Back at the front (position survives removal), details intact.
        assertEquals(
            listOf("Cheese", "Apples", "Bread"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
        val restored = viewModel.uiState.value.shoppingList.first()
        assertEquals(2, restored.amount)
        assertEquals("gouda", restored.note)
        assertFalse(restored.isChecked)
    }

    @Test
    fun `checkout leaves items of archived products alone`() = runTest {
        // The list screen hides them, so the finish dialog never counts them —
        // banking their amounts would grow the stock of a product the user
        // cannot see and delete a row they could not uncheck.
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        val milkId = pid("Milk")
        viewModel.addToShoppingList(milkId, amount = 3)
        viewModel.addToShoppingList(pid("Bread"), amount = 1)
        viewModel.uiState.value.shoppingList.forEach {
            viewModel.setShoppingItemChecked(it.id, checked = true)
        }

        viewModel.archive(milkId)
        viewModel.finishShopping()

        // Bread banked and gone; Milk untouched at 0 and still archived.
        assertEquals(
            listOf("Bread" to 1),
            viewModel.uiState.value.products.map { it.name to it.quantity },
        )
        assertEquals(
            listOf("Milk" to 0),
            viewModel.uiState.value.archivedProducts.map { it.name to it.quantity },
        )
        // Restoring the product brings its dormant, still-checked row back.
        viewModel.restore(milkId)
        assertEquals(
            listOf("Milk"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `undo does nothing but warn when the list was deleted meanwhile`() = runTest {
        // The Undo snackbar lives ten seconds — long enough to archive the list
        // and delete it from the archive. Re-adding into a list that is gone
        // used to trip the foreign key and crash the app.
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val removals = mutableListOf<RemovedShoppingItem>()
        val messages = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.itemRemovedEvents.collect { removals.add(it) }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect { messages.add(it) }
        }
        viewModel.createList("Lidl")
        val listId = viewModel.uiState.value.selectedListId!!
        val productId = viewModel.uiState.value.products.single().id
        viewModel.addToShoppingList(productId, amount = 1)
        viewModel.removeShoppingItem(viewModel.uiState.value.shoppingList.single().id)
        viewModel.deleteList(listId)

        viewModel.undoRemoveItem(removals.single())

        assertEquals(listOf(R.string.undo_list_gone), messages)
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
    }

    @Test
    fun `undo still restores the item when the list was only archived`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val removals = mutableListOf<RemovedShoppingItem>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.itemRemovedEvents.collect { removals.add(it) }
        }
        viewModel.createList("Lidl")
        val listId = viewModel.uiState.value.selectedListId!!
        val productId = viewModel.uiState.value.products.single().id
        viewModel.addToShoppingList(productId, amount = 1)
        viewModel.removeShoppingItem(viewModel.uiState.value.shoppingList.single().id)
        viewModel.archiveList(listId)

        viewModel.undoRemoveItem(removals.single())

        // The list is dormant, so the item is invisible — but it is back, and
        // restoring the list brings it into view.
        viewModel.restoreList(listId)
        viewModel.selectList(listId)
        assertEquals(
            listOf("Milk"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `finishShopping keeps positions so re-added items return to their slot`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        listOf("Apples", "Bread", "Cheese").forEach { viewModel.addToShoppingList(pid(it), 1) }
        viewModel.moveShoppingItem(fromIndex = 2, toIndex = 0) // [Cheese, Apples, Bread]

        val cheese = viewModel.uiState.value.shoppingList.first { it.productName == "Cheese" }
        viewModel.setShoppingItemChecked(cheese.id, checked = true)
        viewModel.finishShopping() // Cheese banked into stock and removed from the list.
        assertEquals(
            listOf("Apples", "Bread"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )

        viewModel.addToShoppingList(pid("Cheese"), 1)

        assertEquals(
            listOf("Cheese", "Apples", "Bread"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `reordering one list does not affect another`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        fun pid(name: String) = viewModel.uiState.value.products.first { it.name == name }.id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(pid("Milk"), 1)
        viewModel.addToShoppingList(pid("Bread"), 1)
        viewModel.createList("Auchan") // becomes selected
        viewModel.addToShoppingList(pid("Milk"), 1)
        viewModel.addToShoppingList(pid("Bread"), 1)

        // On Auchan, drag Bread (index 1) to the front.
        viewModel.moveShoppingItem(fromIndex = 1, toIndex = 0)
        assertEquals(
            listOf("Bread", "Milk"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )

        // Lidl keeps its own order.
        viewModel.selectList(lidl)
        assertEquals(
            listOf("Milk", "Bread"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    // --- Moving items between lists ---

    @Test
    fun `updateShoppingItem with a target list moves the item with amount and note`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.createList("Auchan")
        val auchan = viewModel.uiState.value.selectedListId!!
        viewModel.selectList(lidl)
        viewModel.addToShoppingList(productId, amount = 2, note = "promo")
        val itemId = viewModel.uiState.value.shoppingList.single().id

        // Edit + move in one confirm: new amount, kept note, different list.
        viewModel.updateShoppingItem(itemId, amount = 3, note = "promo", targetListId = auchan)

        assertTrue(viewModel.uiState.value.shoppingList.isEmpty()) // gone from Lidl
        viewModel.selectList(auchan)
        val moved = viewModel.uiState.value.shoppingList.single()
        assertEquals(3, moved.amount)
        assertEquals("promo", moved.note)
        assertFalse(moved.isChecked)
    }

    @Test
    fun `moving onto a list that already has the product is refused`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(productId, amount = 2, note = "from lidl")
        val lidlItem = viewModel.uiState.value.shoppingList.single().id
        viewModel.createList("Auchan")
        val auchan = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(productId, amount = 9, note = "old auchan")

        viewModel.selectList(lidl)
        viewModel.updateShoppingItem(lidlItem, amount = 2, note = "from lidl", targetListId = auchan)

        // Defense in depth: the move is a no-op (the UI disables such targets);
        // the amount/note edit still applied on the source item.
        val kept = viewModel.uiState.value.shoppingList.single()
        assertEquals(2, kept.amount)
        viewModel.selectList(auchan)
        val untouched = viewModel.uiState.value.shoppingList.single()
        assertEquals(9, untouched.amount)
        assertEquals("old auchan", untouched.note)
    }

    @Test
    fun `plannedByProduct maps products to the lists that plan them`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(productId, amount = 1)

        assertEquals(setOf(lidl), viewModel.uiState.value.plannedByProduct[productId])
    }

    // --- Cross-list isolation ---

    @Test
    fun `the same product on two lists is independent`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val milk = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(milk, amount = 2)
        viewModel.createList("Auchan")
        val auchan = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(milk, amount = 5)

        // Check + remove on Auchan.
        val auchanItem = viewModel.uiState.value.shoppingList.single()
        assertEquals(5, auchanItem.amount)
        viewModel.setShoppingItemChecked(auchanItem.id, checked = true)

        // Lidl's Milk is still ×2 and unchecked.
        viewModel.selectList(lidl)
        val lidlItem = viewModel.uiState.value.shoppingList.single()
        assertEquals(2, lidlItem.amount)
        assertFalse(lidlItem.isChecked)

        viewModel.selectList(auchan)
        assertTrue(viewModel.uiState.value.shoppingList.single().isChecked)
    }

    @Test
    fun `checkout affects only the selected list`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val milk = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(milk, amount = 2)
        viewModel.setShoppingItemChecked(viewModel.uiState.value.shoppingList.single().id, checked = true)
        viewModel.createList("Auchan")
        val auchan = viewModel.uiState.value.selectedListId!!
        viewModel.addToShoppingList(milk, amount = 3)

        viewModel.selectList(lidl)
        viewModel.finishShopping()

        assertEquals(2, viewModel.uiState.value.products.single().quantity)
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())

        viewModel.selectList(auchan)
        val auchanItem = viewModel.uiState.value.shoppingList.single()
        assertEquals(3, auchanItem.amount)
        assertFalse(auchanItem.isChecked)
    }

    // --- Store sections on the list ---

    @Test
    fun `unchecked items walk the store section by section, sectionless last`() = runTest {
        val repository = FakeProductRepository()
        // Added in the "wrong" order on purpose; 🧴 (cleaning) walks after
        // 🥛 (dairy) after 🍎 (produce), and no emoji means no section.
        repository.addProduct(name = "Plyn", quantity = 0, unit = null, emoji = "🧴")
        repository.addProduct(name = "Mleko", quantity = 0, unit = null, emoji = "🥛")
        repository.addProduct(name = "Jablka", quantity = 0, unit = null, emoji = "🍎")
        repository.addProduct(name = "Tajemnica", quantity = 0, unit = null, emoji = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.uiState.value.products.forEach { viewModel.addToShoppingList(it.id, amount = null) }
        // A one-off has no product, so its section comes from its name: the
        // bulb walks with the household aisle, ahead of the sectionless row.
        viewModel.addOneOffToShoppingList("żarówka", amount = null)

        val names = viewModel.uiState.value.shoppingList.map { it.productName }

        assertEquals(listOf("Jablka", "Mleko", "Plyn", "żarówka", "Tajemnica"), names)
    }

    @Test
    fun `a one-off takes the section its name implies, a product never does`() = runTest {
        val repository = FakeProductRepository()
        // A product with no section stays sectionless even though its name is
        // in the dictionary: an empty section is a choice the form promises to
        // keep, and only the one-off — which has no form — follows its name.
        repository.addProduct(name = "Mleko", quantity = 0, unit = null, emoji = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.uiState.value.products.forEach { viewModel.addToShoppingList(it.id, amount = null) }
        viewModel.addOneOffToShoppingList("kefir", amount = null)
        viewModel.addOneOffToShoppingList("zgrzeblarka", amount = null)

        val items = viewModel.uiState.value.shoppingList

        assertEquals("🥛", items.first { it.productName == "kefir" }.productEmoji)
        assertEquals(null, items.first { it.productName == "Mleko" }.productEmoji)
        // Nothing in the dictionary answers for it — the trailing group.
        assertEquals(null, items.first { it.productName == "zgrzeblarka" }.productEmoji)
        assertEquals(listOf("kefir", "Mleko", "zgrzeblarka"), items.map { it.productName })
    }

    @Test
    fun `a section picked for a one-off outranks its name and sorts it there`() = runTest {
        val viewModel = makeViewModel(FakeProductRepository())
        observe(viewModel)
        viewModel.createList("Sklep")
        // The dictionary would file this with the drinks; the shopper says it
        // is on the baking shelf in their shop, and the shopper decides.
        viewModel.addOneOffToShoppingList("kompot", amount = null)
        viewModel.addOneOffToShoppingList(
            "kompot na później",
            amount = null,
            sectionEmoji = ProductCategory.DRY_GOODS.emoji,
        )

        val items = viewModel.uiState.value.shoppingList

        assertEquals(ProductCategory.DRINKS.emoji, items.first { it.productName == "kompot" }.productEmoji)
        val corrected = items.first { it.productName == "kompot na później" }
        assertEquals(ProductCategory.DRY_GOODS.emoji, corrected.productEmoji)
        // Not merely displayed there: dry goods are walked before drinks, so
        // the corrected line has moved ahead of the one that was not corrected.
        assertEquals(listOf("kompot na później", "kompot"), items.map { it.productName })
    }

    @Test
    fun `only the pick is remembered, so an uncorrected line keeps following the dictionary`() =
        runTest {
            val viewModel = makeViewModel(FakeProductRepository())
            observe(viewModel)
            viewModel.createList("Sklep")
            viewModel.addOneOffToShoppingList("kompot", amount = null)
            viewModel.addOneOffToShoppingList("kompot", amount = null, sectionEmoji = "")

            val items = viewModel.uiState.value.shoppingList
            val untouched = items.first { it.sectionEmoji == null }

            // Nothing was stored for it, so a word added to the dictionary later
            // still reaches it. It shows a section all the same.
            assertEquals(ProductCategory.DRINKS.emoji, untouched.productEmoji)
            // And "no section" is a thing that can be said, distinct from
            // having said nothing: same name, no section, sorted to the end.
            val refused = items.first { it.sectionEmoji == "" }
            assertEquals(null, refused.productEmoji)
            assertEquals(items.last().id, refused.id)
        }

    @Test
    fun `undo brings back the section that was picked, not the one the name implies`() = runTest {
        val viewModel = makeViewModel(FakeProductRepository())
        observe(viewModel)
        val removals = mutableListOf<RemovedShoppingItem>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.itemRemovedEvents.collect { removals.add(it) }
        }
        viewModel.createList("Sklep")
        viewModel.addOneOffToShoppingList(
            "kompot",
            amount = null,
            sectionEmoji = ProductCategory.DRY_GOODS.emoji,
        )
        val id = viewModel.uiState.value.shoppingList.single().id

        viewModel.removeShoppingItem(id)
        viewModel.undoRemoveItem(removals.single())

        val restored = viewModel.uiState.value.shoppingList.single()
        assertEquals(ProductCategory.DRY_GOODS.emoji, restored.productEmoji)
        assertEquals(ProductCategory.DRY_GOODS.emoji, restored.sectionEmoji)
    }

    @Test
    fun `each list walks its own aisle order`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Mleko", quantity = 0, unit = null, emoji = "🥛")
        repository.addProduct(name = "Chleb", quantity = 0, unit = null, emoji = "🍞")
        repository.addProduct(name = "Mydlo", quantity = 0, unit = null, emoji = "🧼")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        val lidl = viewModel.uiState.value.lists.single().id
        viewModel.uiState.value.products.forEach { viewModel.addToShoppingList(it.id, amount = null) }
        // The default: bread, then dairy, then hygiene.
        assertEquals(
            listOf("Chleb", "Mleko", "Mydlo"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )

        // This shop starts with the chemist's shelf and ends with bread.
        viewModel.setSectionOrder(
            lidl,
            listOf(ProductCategory.HYGIENE, ProductCategory.DAIRY, ProductCategory.BREAD) +
                ProductCategory.entries.filterNot {
                    it == ProductCategory.HYGIENE ||
                        it == ProductCategory.DAIRY ||
                        it == ProductCategory.BREAD
                },
        )

        assertEquals(
            listOf("Mydlo", "Mleko", "Chleb"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
        // The second shop is untouched by the first one's layout.
        viewModel.createList("Biedronka")
        val biedronka = viewModel.uiState.value.lists.first { it.id != lidl }.id
        viewModel.selectList(biedronka)
        viewModel.uiState.value.products.forEach { viewModel.addToShoppingList(it.id, amount = null) }
        assertEquals(
            listOf("Chleb", "Mleko", "Mydlo"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `checked items ignore sections and park at the bottom`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Jablka", quantity = 0, unit = null, emoji = "🍎")
        repository.addProduct(name = "Plyn", quantity = 0, unit = null, emoji = "🧴")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.uiState.value.products.forEach { viewModel.addToShoppingList(it.id, amount = 1) }

        // Checking the produce item — first section — must send it below the
        // cleaning item, aisle notwithstanding: the cart has no aisles.
        val apples = viewModel.uiState.value.shoppingList.first { it.productName == "Jablka" }
        viewModel.setShoppingItemChecked(apples.id, checked = true)

        assertEquals(
            listOf("Plyn", "Jablka"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `a drag across sections is a no-op, within a section it sticks`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Jablka", quantity = 0, unit = null, emoji = "🍎")
        repository.addProduct(name = "Banany", quantity = 0, unit = null, emoji = "🍎")
        repository.addProduct(name = "Mleko", quantity = 0, unit = null, emoji = "🥛")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        // Add in name order: Jablka, Banany, Mleko → sorted: Jablka, Banany, Mleko.
        viewModel.uiState.value.products
            .sortedBy { it.name }
            .forEach { viewModel.addToShoppingList(it.id, amount = null) }
        assertEquals(
            listOf("Banany", "Jablka", "Mleko"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )

        // Dragging Mleko (dairy) to the top would leave its aisle — nothing moves.
        viewModel.moveShoppingItem(fromIndex = 2, toIndex = 0)
        assertEquals(
            listOf("Banany", "Jablka", "Mleko"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )

        // Swapping the two produce items stays inside the aisle — it sticks.
        viewModel.moveShoppingItem(fromIndex = 1, toIndex = 0)
        assertEquals(
            listOf("Jablka", "Banany", "Mleko"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `dragging a product past a one-off places it without inheriting a timestamp`() = runTest {
        val repository = FakeProductRepository()
        // Both sectionless, so they share the trailing group with one-offs.
        repository.addProduct(name = "Tajemnica", quantity = 0, unit = null, emoji = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.addToShoppingList(viewModel.uiState.value.products.single().id, amount = null)
        // A name no dictionary entry answers for, so the one-off really does
        // share the trailing group and can be dragged against.
        viewModel.addOneOffToShoppingList("zgrzeblarka", amount = null)
        assertEquals(
            listOf("Tajemnica", "zgrzeblarka"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )

        viewModel.moveShoppingItem(fromIndex = 0, toIndex = 1)

        // The drag has to actually happen...
        assertEquals(
            listOf("zgrzeblarka", "Tajemnica"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
        // ...without the product inheriting the one-off's creation time, which
        // would live on in product_list_order and park it at the end of its
        // aisle for good — long after the one-off left at checkout.
        val product = viewModel.uiState.value.shoppingList.first { it.productId != null }
        assertTrue(
            "position must stay in the hand-assigned range, was ${product.position}",
            product.position < 1_000.0,
        )
    }

    @Test
    fun `a product dragged past an already placed one-off keeps a sane slot`() = runTest {
        // The regression this exists for: a one-off dragged once had a slot, so
        // "has a slot" was mistaken for "is safe to borrow from" — and its slot
        // is a creation timestamp when its own neighbours were fresh one-offs.
        // A product landing next to it inherited 1.7e12 and never came back.
        val repository = FakeProductRepository()
        repository.addProduct(name = "Tajemnica", quantity = 0, unit = null, emoji = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.addOneOffToShoppingList("zgrzeblarka", amount = null)
        viewModel.addOneOffToShoppingList("krosno", amount = null)
        // One-offs first, so the product joins an aisle that has been dragged.
        viewModel.moveShoppingItem(fromIndex = 1, toIndex = 0)
        viewModel.addToShoppingList(viewModel.uiState.value.products.single().id, amount = null)

        val productIndex = viewModel.uiState.value.shoppingList
            .indexOfFirst { it.productId != null }
        viewModel.moveShoppingItem(fromIndex = productIndex, toIndex = 0)

        val after = viewModel.uiState.value.shoppingList
        assertEquals("Tajemnica", after.first().productName)
        assertTrue(
            "no row may keep a timestamp slot, got ${after.map { it.position }}",
            after.all { it.position < 1_000.0 },
        )
    }

    @Test
    fun `one-offs can be dragged into order within their section`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        // Three sectionless names, so they share one group and add in order.
        viewModel.addOneOffToShoppingList("zgrzeblarka", amount = null)
        viewModel.addOneOffToShoppingList("krosno", amount = null)
        viewModel.addOneOffToShoppingList("czółenko", amount = null)
        assertEquals(
            listOf("zgrzeblarka", "krosno", "czółenko"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )

        // Last to first. Nothing here has been placed by hand yet, so this is
        // exactly the case that used to be impossible: a one-off's neighbours
        // are one-offs, and the drag has to work anyway.
        viewModel.moveShoppingItem(fromIndex = 2, toIndex = 0)

        assertEquals(
            listOf("czółenko", "zgrzeblarka", "krosno"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
        // And the slot sticks: a second drag moves it back, rather than the
        // first one having been a one-time animation.
        viewModel.moveShoppingItem(fromIndex = 0, toIndex = 2)
        assertEquals(
            listOf("zgrzeblarka", "krosno", "czółenko"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `a dragged one-off takes its place among the products of its section`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Tajemnica", quantity = 0, unit = null, emoji = null)
        repository.addProduct(name = "Zagadka", quantity = 0, unit = null, emoji = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.uiState.value.products.forEach { viewModel.addToShoppingList(it.id, amount = null) }
        viewModel.addOneOffToShoppingList("zgrzeblarka", amount = null)
        assertEquals(
            listOf("Tajemnica", "Zagadka", "zgrzeblarka"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )

        // Between the two products, where the trailing-by-creation-time default
        // could never put it.
        viewModel.moveShoppingItem(fromIndex = 2, toIndex = 1)

        assertEquals(
            listOf("Tajemnica", "zgrzeblarka", "Zagadka"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
        // Every row of the aisle now carries a hand-assigned slot, the one-off
        // included: no timestamp is left for a later drag to borrow.
        val aisle = viewModel.uiState.value.shoppingList
        assertTrue(
            "expected hand-assigned slots, got ${aisle.map { it.position }}",
            aisle.all { it.position < 1_000.0 },
        )
    }

    @Test
    fun `moveShoppingItem reports whether a write was dispatched`() = runTest {
        // The drag mirror in the UI arms itself on this answer: after an
        // accepted move it waits for Room's echo before re-syncing, after a
        // declined one there is no echo to wait for — trusting a declined move
        // left the mirror showing an order the database never held.
        val repository = FakeProductRepository()
        repository.addProduct(name = "Mleko", quantity = 0, unit = null, emoji = "🥛")
        repository.addProduct(name = "Ser", quantity = 0, unit = null, emoji = "🥛")
        repository.addProduct(name = "Chleb", quantity = 0, unit = null, emoji = "🍞")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.uiState.value.products.forEach { viewModel.addToShoppingList(it.id, amount = null) }
        // Sorted: Chleb (bread), Mleko, Ser (dairy).

        // A real move within the dairy aisle.
        assertTrue(viewModel.moveShoppingItem(fromIndex = 2, toIndex = 1))
        // A drop outside the aisle: declined, nothing will echo.
        assertFalse(viewModel.moveShoppingItem(fromIndex = 0, toIndex = 2))
        // Indices from a list that no longer looks like this: declined too.
        assertFalse(viewModel.moveShoppingItem(fromIndex = 0, toIndex = 9))
        assertFalse(viewModel.moveShoppingItem(fromIndex = 1, toIndex = 1))
    }

    @Test
    fun `a one-off moved to another list leaves its slot behind`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.createList("Targ")
        val sklep = viewModel.uiState.value.lists.first { it.name == "Sklep" }.id
        val targ = viewModel.uiState.value.lists.first { it.name == "Targ" }.id
        viewModel.selectList(targ)
        viewModel.addOneOffToShoppingList("wiadro", amount = null)
        viewModel.selectList(sklep)
        viewModel.addOneOffToShoppingList("zgrzeblarka", amount = null)
        viewModel.addOneOffToShoppingList("krosno", amount = null)
        // Dragged to the front, so it holds a small hand-assigned slot (1.0) —
        // a number that would outrank everything on the target list.
        viewModel.moveShoppingItem(fromIndex = 1, toIndex = 0)
        val moved = viewModel.uiState.value.shoppingList.first { it.productName == "krosno" }

        viewModel.updateShoppingItem(moved.id, amount = null, note = null, targetListId = targ)

        viewModel.selectList(targ)
        // It arrives like a newly written line — at the end, not parachuted to
        // the top on the strength of a slot that meant something elsewhere.
        assertEquals(
            listOf("wiadro", "krosno"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    @Test
    fun `a one-off dragged out of its section stays put`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Mleko", quantity = 0, unit = null, emoji = "🥛")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.addToShoppingList(viewModel.uiState.value.products.single().id, amount = null)
        // Sectionless, so it trails the dairy group in a group of its own.
        viewModel.addOneOffToShoppingList("zgrzeblarka", amount = null)

        // Dropping it into the dairy aisle: its section comes from its name, so
        // the sort would put it straight back. A no-op instead of a flicker.
        viewModel.moveShoppingItem(fromIndex = 1, toIndex = 0)

        assertEquals(
            listOf("Mleko", "zgrzeblarka"),
            viewModel.uiState.value.shoppingList.map { it.productName },
        )
    }

    // --- One-off items ---

    @Test
    fun `a one-off name outlives the line it was typed on`() = runTest {
        // The point of the whole table: the LINE dies at checkout, the WORD
        // does not, so next November the picker can offer it back.
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.addOneOffToShoppingList("znicze", amount = 2, unit = "sztuki")
        val item = viewModel.uiState.value.shoppingList.single()

        viewModel.setShoppingItemChecked(item.id, checked = true)
        viewModel.finishShopping()

        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
        val suggestion = viewModel.uiState.value.oneOffSuggestions.single()
        assertEquals("znicze", suggestion.name)
        // The unit comes back too — retyping "sztuki" is the tedious half.
        assertEquals("sztuki", suggestion.unit)
    }

    @Test
    fun `buying the same name again moves it up instead of duplicating it`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.addOneOffToShoppingList("znicze", amount = 2, unit = "sztuki")
        viewModel.addOneOffToShoppingList("wiadro", amount = null)
        // Case and stray spaces are the same word, not a second one.
        viewModel.addOneOffToShoppingList("  Znicze ", amount = 4, unit = "opakowania")

        val suggestions = viewModel.uiState.value.oneOffSuggestions
        assertEquals(2, suggestions.size)
        // Newest first, and the entry carries what it was last bought as.
        assertEquals("Znicze", suggestions.first().name)
        assertEquals("opakowania", suggestions.first().unit)
        assertEquals("wiadro", suggestions[1].name)
    }

    @Test
    fun `a forgotten name stops being suggested`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Sklep")
        viewModel.addOneOffToShoppingList("zniczee", amount = null)
        viewModel.addOneOffToShoppingList("wiadro", amount = null)

        // The typo goes; what was meant stays.
        viewModel.forgetOneOffSuggestion("zniczee")

        assertEquals(
            listOf("wiadro"),
            viewModel.uiState.value.oneOffSuggestions.map { it.name },
        )
    }

    @Test
    fun `a one-off lands on the list under its own name, after the products`() = runTest {
        val repository = FakeProductRepository()
        // Dairy, so the bulb's own aisle (household) still puts it last.
        repository.addProduct(name = "Milk", quantity = 0, unit = "l", emoji = "🥛")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(viewModel.uiState.value.products.single().id, amount = 1)

        viewModel.addOneOffToShoppingList("  żarówka ", amount = 2)

        val items = viewModel.uiState.value.shoppingList
        assertEquals(2, items.size)
        val oneOff = items.last() // no manual slot — one-offs gather at the end
        assertNull(oneOff.productId)
        assertEquals("żarówka", oneOff.productName)
        assertEquals(2, oneOff.amount)
        // No unit given, so the row shows a bare count.
        assertNull(oneOff.productUnit)
    }

    @Test
    fun `a one-off can say what its amount counts`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")

        // The two shapes this exists for: a weight and a packaging count.
        viewModel.addOneOffToShoppingList("szynka", amount = 200, unit = " g ")
        viewModel.addOneOffToShoppingList("chusteczki", amount = 3, unit = "opakowania")

        val items = viewModel.uiState.value.shoppingList
        assertEquals("g", items.first { it.productName == "szynka" }.productUnit)
        assertEquals(200, items.first { it.productName == "szynka" }.amount)
        assertEquals("opakowania", items.first { it.productName == "chusteczki" }.productUnit)
    }

    @Test
    fun `editing a one-off changes its unit, editing a product row cannot`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l", emoji = "🥛")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        viewModel.addToShoppingList(viewModel.uiState.value.products.single().id, amount = 1)
        viewModel.addOneOffToShoppingList("szynka", amount = 200, unit = "g")

        val oneOff = viewModel.uiState.value.shoppingList.first { it.productId == null }
        val productRow = viewModel.uiState.value.shoppingList.first { it.productId != null }
        // Weighed at the counter, not by the gram after all.
        viewModel.updateShoppingItem(oneOff.id, amount = 2, unit = "plastry", note = null)
        // The product's unit belongs to the product: the edit dialog offers no
        // field for it, and even if something passed one it must not stick.
        viewModel.updateShoppingItem(productRow.id, amount = 2, unit = "beczki", note = null)

        val updated = viewModel.uiState.value.shoppingList
        assertEquals("plastry", updated.first { it.productId == null }.productUnit)
        assertEquals("l", updated.first { it.productId != null }.productUnit)
    }

    @Test
    fun `two one-offs of the same name are two lines, and checkout removes only checked ones`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")

        viewModel.addOneOffToShoppingList("żarówka", amount = null)
        viewModel.addOneOffToShoppingList("żarówka", amount = null)
        assertEquals(2, viewModel.uiState.value.shoppingList.size)

        viewModel.setShoppingItemChecked(viewModel.uiState.value.shoppingList.first().id, checked = true)
        viewModel.finishShopping()

        // One bought and gone; the other still waiting. No product appeared
        // anywhere out of this.
        assertEquals(1, viewModel.uiState.value.shoppingList.size)
        assertTrue(viewModel.uiState.value.products.isEmpty())
    }

    @Test
    fun `undo of a removed one-off brings it back by name`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        viewModel.addOneOffToShoppingList("znicz", amount = 3, unit = "sztuki", note = "czerwony")
        val item = viewModel.uiState.value.shoppingList.single()

        viewModel.removeShoppingItem(item.id)
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
        viewModel.undoRemoveItem(
            RemovedShoppingItem(
                listId = viewModel.uiState.value.selectedListId!!,
                productId = null,
                productName = "znicz",
                amount = 3,
                unit = "sztuki",
                sectionEmoji = null,
                note = "czerwony",
            ),
        )

        val restored = viewModel.uiState.value.shoppingList.single()
        assertNull(restored.productId)
        assertEquals("znicz", restored.productName)
        assertEquals(3, restored.amount)
        // The unit comes back with it: "3" alone is not what was written down.
        assertEquals("sztuki", restored.productUnit)
        assertEquals("czerwony", restored.note)
    }

    @Test
    fun `one-offs plan nothing - low stock ignores them and they block no deletion`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = null, minQuantity = 2)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")

        // A one-off named like the product must not count as planning it.
        viewModel.addOneOffToShoppingList("Milk", amount = 1)

        assertEquals(1, viewModel.uiState.value.lowStockProducts.size)
        assertTrue(viewModel.uiState.value.referencedProductIds.isEmpty())
    }
}
