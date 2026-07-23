package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
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

    private fun makeViewModel(repository: FakeProductRepository) =
        PantryViewModel(repository, FakeShoppingListRepository(repository), FakeBarcodeRepository())

    // Keeps the WhileSubscribed uiState hot so reads see the latest state.
    private fun TestScope.observe(viewModel: PantryViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
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

    @Test
    fun `unchecked items sort before checked ones, alphabetical within groups`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Apples", quantity = 0, unit = null)
        repository.addProduct(name = "Bread", quantity = 0, unit = null)
        repository.addProduct(name = "Cheese", quantity = 0, unit = null)
        val viewModel = makeViewModel(repository)
        observe(viewModel)
        viewModel.createList("Lidl")
        viewModel.uiState.value.products.forEach { viewModel.addToShoppingList(it.id, 1) }
        val apples = viewModel.uiState.value.shoppingList.first { it.productName == "Apples" }
        viewModel.setShoppingItemChecked(apples.id, checked = true)

        val names = viewModel.uiState.value.shoppingList.map { it.productName to it.isChecked }
        assertEquals(
            listOf("Bread" to false, "Cheese" to false, "Apples" to true),
            names,
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
