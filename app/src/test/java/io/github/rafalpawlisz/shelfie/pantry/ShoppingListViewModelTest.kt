package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.ui.pantry.LowStockSuggestion
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
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

    private fun TestScope.collectLowStock(viewModel: PantryViewModel): List<LowStockSuggestion> {
        val events = mutableListOf<LowStockSuggestion>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.lowStockEvents.collect { events.add(it) }
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
            viewModel.scanEvents.collect { scans.add(it) }
        }
        val lowStock = collectLowStock(viewModel)
        viewModel.addProduct(
            name = "Milk", quantity = 2, unit = "l", minQuantity = 3,
            barcodes = listOf("5901234123457"),
        )
        viewModel.createList("Lidl")

        viewModel.useUpByBarcode("5901234123457")

        val used = scans.single() as UseUpScanResult.Used
        assertEquals("Milk", used.productName)
        assertEquals(2, used.suggestion?.suggestedAmount) // min 3 − new stock 1
        assertTrue(lowStock.isEmpty()) // no second event for the same use-up
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
    fun `adding a product with an unchecked item sums amounts into one entry`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")

        viewModel.addToShoppingList(productId, amount = 3)
        viewModel.addToShoppingList(productId, amount = 1)

        val item = viewModel.uiState.value.shoppingList.single()
        assertEquals(4, item.amount)
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
    fun `an item can be added without an amount and a concrete amount wins on merge`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")

        viewModel.addToShoppingList(productId, amount = null)
        assertNull(viewModel.uiState.value.shoppingList.single().amount)

        // null + null stays "just buy it".
        viewModel.addToShoppingList(productId, amount = null)
        assertNull(viewModel.uiState.value.shoppingList.single().amount)

        // null + 5 -> 5 (the concrete amount wins).
        viewModel.addToShoppingList(productId, amount = 5)
        assertEquals(5, viewModel.uiState.value.shoppingList.single().amount)

        // 5 + null -> 5 (kept), 5 + 2 -> 7 (sum regression).
        viewModel.addToShoppingList(productId, amount = null)
        assertEquals(5, viewModel.uiState.value.shoppingList.single().amount)
        viewModel.addToShoppingList(productId, amount = 2)
        assertEquals(7, viewModel.uiState.value.shoppingList.single().amount)
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
    fun `item note is set on add, replaced on merge, and dies with the item at checkout`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        val productId = viewModel.uiState.value.products.single().id
        viewModel.createList("Lidl")

        viewModel.addToShoppingList(productId, amount = 2, note = "the blue one")
        assertEquals("the blue one", viewModel.uiState.value.shoppingList.single().note)

        // Re-adding without a note keeps the existing one.
        viewModel.addToShoppingList(productId, amount = 1, note = null)
        assertEquals("the blue one", viewModel.uiState.value.shoppingList.single().note)

        // A new note replaces it.
        viewModel.addToShoppingList(productId, amount = null, note = "only on sale")
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
}
