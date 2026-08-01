package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.R
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PantryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun makeViewModel(repository: FakeProductRepository) =
        PantryViewModel(
            repository,
            FakeShoppingListRepository(repository),
            FakeBarcodeRepository(),
            FakeUiPreferences(),
        )

    @Test
    fun `uiState maps repository products and clears loading`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 2, unit = "l")
        val viewModel = makeViewModel(repository)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.products.size)
        assertEquals("Milk", state.products.single().name)
        assertEquals(2, state.products.single().quantity)
        assertEquals("l", state.products.single().unit)
    }

    @Test
    fun `addProduct surfaces the new product in uiState`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        assertTrue(viewModel.uiState.value.products.isEmpty())

        viewModel.addProduct(name = "Eggs", quantity = 10, unit = null)

        val products = viewModel.uiState.value.products
        assertEquals(1, products.size)
        assertEquals("Eggs", products.single().name)
        assertEquals(10, products.single().quantity)
    }

    @Test
    fun `a best-before date is kept, and editing can clear it`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.addProduct(name = "Syrop", quantity = 1, unit = null, expiresOn = "2026-08-20")
        val product = viewModel.uiState.value.products.single()
        assertEquals("2026-08-20", product.expiresOn)

        // Clearing the field is a real answer, not "leave it as it was".
        viewModel.updateProduct(
            id = product.id,
            name = product.name,
            quantity = product.quantity,
            unit = product.unit,
            expiresOn = null,
        )
        assertNull(viewModel.uiState.value.products.single().expiresOn)
    }

    @Test
    fun `addProduct reaches a name the pantry already has instead of doubling it`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(
            name = "Mleko",
            quantity = 2,
            unit = "l",
            minQuantity = 4,
            notes = "UHT",
            emoji = "🥛",
        )
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id

        // The form warns while the name is typed, so this is the race: the other
        // phone created "Mleko" while the form was open. One product wins, with
        // its own details — a second one would split its barcodes and low stock.
        viewModel.addProduct(name = " mleko ", quantity = 0, unit = null)

        val product = viewModel.uiState.value.products.single()
        assertEquals(id, product.id)
        assertEquals(2, product.quantity)
        assertEquals("l", product.unit)
        assertEquals(4, product.minQuantity)
        assertEquals("UHT", product.notes)
    }

    @Test
    fun `addProduct restores that name from the archive and says so`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Kasza", quantity = 0, unit = "kg", minQuantity = 2)
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id
        viewModel.archive(id)
        val messages = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect { messages += it }
        }

        viewModel.addProduct(name = "kasza", quantity = 0, unit = null)

        val product = viewModel.uiState.value.products.single()
        assertEquals(id, product.id)
        assertEquals("kg", product.unit)
        assertEquals(2, product.minQuantity)
        assertTrue(viewModel.uiState.value.archivedProducts.isEmpty())
        assertEquals(listOf(R.string.product_back_from_archive), messages)
    }

    @Test
    fun `decrement clamps quantity at zero`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Butter", quantity = 1, unit = null)
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id

        viewModel.decrement(id)
        viewModel.decrement(id)

        assertEquals(0, viewModel.uiState.value.products.single().quantity)
    }

    @Test
    fun `updateProduct changes name quantity and unit`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Mlik", quantity = 1, unit = null)
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id

        viewModel.updateProduct(id = id, name = "Milk", quantity = 3, unit = "l")

        val product = viewModel.uiState.value.products.single()
        assertEquals("Milk", product.name)
        assertEquals(3, product.quantity)
        assertEquals("l", product.unit)
        assertEquals(id, product.id)
    }

    @Test
    fun `archive moves the product to the archived list`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Flour", quantity = 1, unit = "kg")
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id

        viewModel.archive(id)

        assertTrue(viewModel.uiState.value.products.isEmpty())
        assertEquals("Flour", viewModel.uiState.value.archivedProducts.single().name)
    }

    @Test
    fun `a product created from the picker comes back for the amount step`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.addProductForList(
            name = "groszek",
            quantity = 0,
            unit = "puszka",
            minQuantity = 2,
            emoji = "🫘",
        )

        val product = viewModel.uiState.value.products.single()
        assertEquals("groszek", product.name)
        // The full form's fields survive the trip — that is the point of using
        // it instead of creating from the name alone.
        assertEquals("puszka", product.unit)
        assertEquals(2, product.minQuantity)
        assertEquals("🫘", product.emoji)
        // Handed back so the picker can continue with it, and only once.
        assertEquals(product.id, viewModel.productForList.value)
        viewModel.clearProductForList()
        assertNull(viewModel.productForList.value)
    }

    @Test
    fun `planning an archived product brings it back so the item is visible`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Groszek", quantity = 0, unit = "puszka")
        val lists = FakeShoppingListRepository(repository)
        val viewModel = PantryViewModel(repository, lists, FakeBarcodeRepository(), FakeUiPreferences())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        lists.createList("Sklep")
        val id = viewModel.uiState.value.products.single().id
        viewModel.archive(id)

        viewModel.addToShoppingList(id, amount = 2)

        // Restoring is what makes the row appear at all: items of archived
        // products are filtered out, so without it this would be invisible.
        assertTrue(viewModel.uiState.value.archivedProducts.isEmpty())
        assertEquals(id, viewModel.uiState.value.shoppingList.single().productId)
        // And it keeps what it had — planning is not editing.
        assertEquals("puszka", viewModel.uiState.value.products.single().unit)
    }

    @Test
    fun `the restock dialog also brings an archived product back`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Groszek", quantity = 0, unit = null)
        val lists = FakeShoppingListRepository(repository)
        val viewModel = PantryViewModel(repository, lists, FakeBarcodeRepository(), FakeUiPreferences())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val listId = lists.createList("Sklep")
        val id = viewModel.uiState.value.products.single().id
        viewModel.archive(id)

        viewModel.addToList(listId, id, amount = 1)

        assertTrue(viewModel.uiState.value.archivedProducts.isEmpty())
        assertEquals(id, viewModel.uiState.value.shoppingList.single().productId)
    }

    @Test
    fun `an archived product of that name is restored instead of duplicated`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(
            name = "Groszek",
            quantity = 0,
            unit = "puszka",
            minQuantity = 3,
            notes = null,
            emoji = null,
        )
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id
        viewModel.archive(id)

        // Typed with different case and spacing — still the same product, and
        // its stored details survive: the form behind this path is blank by
        // design, so writing it through emptied unit, minimum and notes.
        val messages = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect { messages += it }
        }
        viewModel.addProductForList(name = "  groszek ", quantity = 0, unit = null)

        assertTrue(viewModel.uiState.value.archivedProducts.isEmpty())
        val product = viewModel.uiState.value.products.single()
        assertEquals(id, product.id)
        assertEquals("Groszek", product.name)
        assertEquals("puszka", product.unit)
        assertEquals(3, product.minQuantity)
        assertEquals(id, viewModel.productForList.value)
        // Silence here would leave the user to discover the restore later.
        assertEquals(listOf(R.string.product_back_from_archive), messages)
    }

    @Test
    fun `a blank name creates nothing`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.addProductForList(name = "   ", quantity = 0, unit = null)

        assertTrue(viewModel.uiState.value.products.isEmpty())
        assertNull(viewModel.productForList.value)
    }

    @Test
    fun `deleting an archived product removes it from the archive`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Kasza", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id
        viewModel.archive(id)

        viewModel.deleteArchived(id)

        assertTrue(viewModel.uiState.value.archivedProducts.isEmpty())
        assertTrue(viewModel.uiState.value.products.isEmpty())
    }

    @Test
    fun `a product a list still refers to survives and says why`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Kasza", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id
        viewModel.archive(id)
        // The refusal comes from the data layer, not the UI: between the button
        // appearing and the tap, the other device can put it on a list.
        repository.referencedProductIds = setOf(id)

        val messages = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect { messages += it }
        }
        viewModel.deleteArchived(id)

        assertEquals("Kasza", viewModel.uiState.value.archivedProducts.single().name)
        assertEquals(listOf(R.string.delete_product_in_use), messages)
    }

    @Test
    fun `an active product is never deleted by the archive action`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Kasza", quantity = 1, unit = null)
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id

        viewModel.deleteArchived(id)

        assertEquals("Kasza", viewModel.uiState.value.products.single().name)
    }

    @Test
    fun `an item on any list marks its product as referenced`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Kasza", quantity = 0, unit = null)
        val lists = FakeShoppingListRepository(repository)
        val viewModel = PantryViewModel(repository, lists, FakeBarcodeRepository(), FakeUiPreferences())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id
        val listId = lists.createList("Sklep")
        lists.addItem(listId, id, amount = null, note = null)

        // Archiving the list must not make the product deletable: restoring it
        // would find the item gone.
        lists.archiveList(listId)

        assertEquals(setOf(id), viewModel.uiState.value.referencedProductIds)
    }

    @Test
    fun `restore moves the product back to the active list`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Rice", quantity = 2, unit = "kg")
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id
        viewModel.archive(id)
        assertTrue(viewModel.uiState.value.products.isEmpty())

        viewModel.restore(id)

        assertTrue(viewModel.uiState.value.archivedProducts.isEmpty())
        assertEquals("Rice", viewModel.uiState.value.products.single().name)
    }
}
