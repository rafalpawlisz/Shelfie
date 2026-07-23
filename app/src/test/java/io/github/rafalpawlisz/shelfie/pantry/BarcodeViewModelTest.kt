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

    private fun makeViewModel(
        products: FakeProductRepository,
        barcodes: FakeBarcodeRepository,
    ) = PantryViewModel(products, FakeShoppingListRepository(products), barcodes)

    @Test
    fun `addBarcode surfaces the code under its product`() = runTest {
        val products = FakeProductRepository()
        products.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(products, FakeBarcodeRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id

        viewModel.addBarcode(productId, "5900000000001")

        assertEquals(
            listOf("5900000000001"),
            viewModel.uiState.value.barcodesByProduct[productId],
        )
    }

    @Test
    fun `a product can hold several barcodes`() = runTest {
        val products = FakeProductRepository()
        products.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(products, FakeBarcodeRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id

        viewModel.addBarcode(productId, "5900000000001")
        viewModel.addBarcode(productId, "5900000000002")

        assertEquals(2, viewModel.uiState.value.barcodesByProduct[productId]?.size)
    }

    @Test
    fun `removeBarcode drops the code`() = runTest {
        val products = FakeProductRepository()
        products.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(products, FakeBarcodeRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val productId = viewModel.uiState.value.products.single().id
        viewModel.addBarcode(productId, "5900000000001")

        viewModel.removeBarcode("5900000000001")

        assertNull(viewModel.uiState.value.barcodesByProduct[productId])
    }

    @Test
    fun `scanning a code already on another product reassigns it`() = runTest {
        val products = FakeProductRepository()
        products.addProduct(name = "Milk", quantity = 0, unit = "l")
        products.addProduct(name = "Cream", quantity = 0, unit = "l")
        val viewModel = makeViewModel(products, FakeBarcodeRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val milkId = viewModel.uiState.value.products.first { it.name == "Milk" }.id
        val creamId = viewModel.uiState.value.products.first { it.name == "Cream" }.id
        viewModel.addBarcode(milkId, "5900000000001")

        viewModel.addBarcode(creamId, "5900000000001")

        assertNull(viewModel.uiState.value.barcodesByProduct[milkId])
        assertEquals(listOf("5900000000001"), viewModel.uiState.value.barcodesByProduct[creamId])
    }
}
