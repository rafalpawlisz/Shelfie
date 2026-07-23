package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
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
class ShoppingListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun makeViewModel(repository: FakeProductRepository) =
        PantryViewModel(repository, FakeShoppingListRepository(repository), FakeBarcodeRepository())

    @Test
    fun `addToShoppingList creates an unchecked item with product data`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l", emoji = "M")
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id

        viewModel.addToShoppingList(productId, amount = 4)

        val item = viewModel.uiState.value.shoppingList.single()
        assertEquals(productId, item.productId)
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
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id

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
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id
        viewModel.addToShoppingList(productId, amount = 3)
        val firstItemId = viewModel.uiState.value.shoppingList.single().id
        viewModel.setShoppingItemChecked(firstItemId, checked = true)
        assertEquals(0, viewModel.uiState.value.products.single().quantity)

        viewModel.addToShoppingList(productId, amount = 2)

        val item = viewModel.uiState.value.shoppingList.single()
        assertFalse(item.isChecked)
        assertEquals(2, item.amount)
        assertEquals(0, viewModel.uiState.value.products.single().quantity)
    }

    @Test
    fun `checking an item does not change quantity and keeps it visible`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l")
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id
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
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id
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
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id
        viewModel.addToShoppingList(productId, amount = 3)
        val itemId = viewModel.uiState.value.shoppingList.single().id
        viewModel.setShoppingItemChecked(itemId, checked = true)
        assertEquals(5, viewModel.uiState.value.products.single().quantity)

        viewModel.setShoppingItemChecked(itemId, checked = false)

        assertEquals(5, viewModel.uiState.value.products.single().quantity)
        assertFalse(viewModel.uiState.value.shoppingList.single().isChecked)
    }

    @Test
    fun `finishShopping applies all checked amounts, removes them, and keeps unchecked`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l")
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        fun product(name: String) = viewModel.uiState.value.products.first { it.name == name }
        viewModel.addToShoppingList(product("Milk").id, amount = 2)
        viewModel.addToShoppingList(product("Cheese").id, amount = 3)
        viewModel.addToShoppingList(product("Bread").id, amount = 1)
        val shopping = viewModel.uiState.value.shoppingList
        viewModel.setShoppingItemChecked(shopping.first { it.productName == "Milk" }.id, checked = true)
        viewModel.setShoppingItemChecked(shopping.first { it.productName == "Cheese" }.id, checked = true)
        // Nothing applied yet.
        assertEquals(1, product("Milk").quantity)
        assertEquals(0, product("Cheese").quantity)

        viewModel.finishShopping()

        assertEquals(3, product("Milk").quantity)
        assertEquals(3, product("Cheese").quantity)
        assertEquals(0, product("Bread").quantity)
        val remaining = viewModel.uiState.value.shoppingList.single()
        assertEquals("Bread", remaining.productName)
        assertFalse(remaining.isChecked)
    }

    @Test
    fun `finishShopping with nothing checked leaves items and quantities unchanged`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 1, unit = "l")
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id
        viewModel.addToShoppingList(productId, amount = 2)

        viewModel.finishShopping()

        assertEquals(1, viewModel.uiState.value.products.single().quantity)
        assertEquals(1, viewModel.uiState.value.shoppingList.size)
        assertFalse(viewModel.uiState.value.shoppingList.single().isChecked)
    }

    @Test
    fun `archiving a product hides its shopping list item`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id
        viewModel.addToShoppingList(productId, amount = 1)
        assertEquals(1, viewModel.uiState.value.shoppingList.size)

        viewModel.archive(productId)
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())

        viewModel.restore(productId)
        assertEquals(1, viewModel.uiState.value.shoppingList.size)
    }

    @Test
    fun `unchecked items sort before checked ones, alphabetical within groups`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.uiState.value.products.forEach { viewModel.addToShoppingList(it.id, amount = 1) }
        val apples = viewModel.uiState.value.shoppingList.first { it.productName == "Apples" }
        viewModel.setShoppingItemChecked(apples.id, checked = true)

        val names = viewModel.uiState.value.shoppingList.map { it.productName to it.isChecked }
        assertEquals(
            listOf("Bread" to false, "Cheese" to false, "Apples" to true),
            names,
        )
    }
}
