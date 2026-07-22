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
class PantryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `uiState maps repository products and clears loading`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Milk", quantity = 2, unit = "l")
        val viewModel = PantryViewModel(repository)

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
        val viewModel = PantryViewModel(repository)
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
        val viewModel = PantryViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id

        viewModel.decrement(id)
        viewModel.decrement(id)

        assertEquals(0, viewModel.uiState.value.products.single().quantity)
    }

    @Test
    fun `delete removes the product`() = runTest {
        val repository = FakeProductRepository()
        repository.addProduct(name = "Flour", quantity = 1, unit = "kg")
        val viewModel = PantryViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val id = viewModel.uiState.value.products.single().id

        viewModel.delete(id)

        assertTrue(viewModel.uiState.value.products.isEmpty())
    }
}
