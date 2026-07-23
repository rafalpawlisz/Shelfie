package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun makeViewModel(products: FakeProductRepository) =
        PantryViewModel(products, FakeShoppingListRepository(products), FakeBarcodeRepository())

    @Test
    fun `adding a product with barcodes stores them under the new product`() = runTest {
        val products = FakeProductRepository()
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.addProduct(
            name = "Milk",
            quantity = 0,
            unit = "l",
            barcodes = listOf("5900000000001", "5900000000002"),
        )

        val productId = viewModel.uiState.value.products.single().id
        assertEquals(
            listOf("5900000000001", "5900000000002"),
            viewModel.uiState.value.barcodesByProduct[productId],
        )
    }

    @Test
    fun `updating a product adds new barcodes and removes dropped ones`() = runTest {
        val products = FakeProductRepository()
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.addProduct(name = "Milk", quantity = 0, unit = "l", barcodes = listOf("A", "B"))
        val id = viewModel.uiState.value.products.single().id

        // Keep A, drop B, add C.
        viewModel.updateProduct(id = id, name = "Milk", quantity = 0, unit = "l", barcodes = listOf("A", "C"))

        assertEquals(setOf("A", "C"), viewModel.uiState.value.barcodesByProduct[id]?.toSet())
    }

    @Test
    fun `updating with an empty list clears all barcodes`() = runTest {
        val products = FakeProductRepository()
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.addProduct(name = "Milk", quantity = 0, unit = "l", barcodes = listOf("A"))
        val id = viewModel.uiState.value.products.single().id

        viewModel.updateProduct(id = id, name = "Milk", quantity = 0, unit = "l", barcodes = emptyList())

        assertNull(viewModel.uiState.value.barcodesByProduct[id])
    }

    @Test
    fun `a code assigned to another product moves on rescan`() = runTest {
        val products = FakeProductRepository()
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.addProduct(name = "Milk", quantity = 0, unit = "l", barcodes = listOf("X"))
        viewModel.addProduct(name = "Cream", quantity = 0, unit = "l")
        val milkId = viewModel.uiState.value.products.first { it.name == "Milk" }.id
        val creamId = viewModel.uiState.value.products.first { it.name == "Cream" }.id

        viewModel.updateProduct(id = creamId, name = "Cream", quantity = 0, unit = "l", barcodes = listOf("X"))

        assertNull(viewModel.uiState.value.barcodesByProduct[milkId])
        assertEquals(listOf("X"), viewModel.uiState.value.barcodesByProduct[creamId])
    }
}
