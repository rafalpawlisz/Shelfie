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
    fun `a name the pantry lacks is created and listed in one step`() = runTest {
        val repository = FakeProductRepository()
        val lists = FakeShoppingListRepository(repository)
        val viewModel = PantryViewModel(repository, lists, FakeBarcodeRepository(), FakeUiPreferences())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        lists.createList("Sklep")

        viewModel.createAndAddToShoppingList("groszek", amount = 2, note = "puszka")

        val product = viewModel.uiState.value.products.single()
        assertEquals("groszek", product.name)
        // The same suggester the product form uses, so the row is not blank.
        assertEquals("🫘", product.emoji)
        val item = viewModel.uiState.value.shoppingList.single()
        assertEquals(product.id, item.productId)
        assertEquals(2, item.amount)
        assertEquals("puszka", item.note)
    }

    @Test
    fun `an archived product of that name is restored instead of duplicated`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Groszek", quantity = 0, unit = null)
        val lists = FakeShoppingListRepository(repository)
        val viewModel = PantryViewModel(repository, lists, FakeBarcodeRepository(), FakeUiPreferences())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        lists.createList("Sklep")
        val id = viewModel.uiState.value.products.single().id
        viewModel.archive(id)

        // Typed with different case and spacing — still the same product.
        viewModel.createAndAddToShoppingList("  groszek ", amount = null)

        assertTrue(viewModel.uiState.value.archivedProducts.isEmpty())
        assertEquals(listOf("Groszek"), viewModel.uiState.value.products.map { it.name })
        assertEquals(id, viewModel.uiState.value.shoppingList.single().productId)
    }

    @Test
    fun `creating from the picker needs a list and a name`() = runTest {
        val repository = FakeProductRepository()
        val lists = FakeShoppingListRepository(repository)
        val viewModel = PantryViewModel(repository, lists, FakeBarcodeRepository(), FakeUiPreferences())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        // No list selected yet: nothing to add to, so nothing is created either.
        viewModel.createAndAddToShoppingList("groszek", amount = null)
        assertTrue(viewModel.uiState.value.products.isEmpty())

        lists.createList("Sklep")
        viewModel.createAndAddToShoppingList("   ", amount = null)
        assertTrue(viewModel.uiState.value.products.isEmpty())
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
