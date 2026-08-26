package io.github.rafalpawlisz.shelfie.pantry

import io.github.rafalpawlisz.shelfie.MainDispatcherRule
import io.github.rafalpawlisz.shelfie.ui.pantry.PantryViewModel
import io.github.rafalpawlisz.shelfie.ui.pantry.UseUpScanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun makeViewModel(products: FakeProductRepository) =
        PantryViewModel(
            products,
            FakeShoppingListRepository(products),
            FakeBarcodeRepository(),
            FakeUiPreferences(),
        )

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
        viewModel.updateProduct(
            id = id, name = "Milk", quantity = 0, unit = "l",
            addedBarcodes = listOf("C"), removedBarcodes = listOf("B"),
        )

        assertEquals(setOf("A", "C"), viewModel.uiState.value.barcodesByProduct[id]?.toSet())
    }

    @Test
    fun `updating with an empty list clears all barcodes`() = runTest {
        val products = FakeProductRepository()
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.addProduct(name = "Milk", quantity = 0, unit = "l", barcodes = listOf("A"))
        val id = viewModel.uiState.value.products.single().id

        viewModel.updateProduct(
            id = id, name = "Milk", quantity = 0, unit = "l",
            removedBarcodes = listOf("A"),
        )

        assertNull(viewModel.uiState.value.barcodesByProduct[id])
    }

    @Test
    fun `scanning a code of a measured product asks for the amount instead of subtracting`() = runTest {
        val products = FakeProductRepository()
        products.addProduct(name = "Milk", quantity = 2, unit = "l")
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val events = mutableListOf<UseUpScanResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.useUpEvents.collect { events += it } }
        val id = viewModel.uiState.value.products.single().id
        viewModel.addProduct(name = "ignored", quantity = 0, unit = null) // noise product
        viewModel.updateProduct(
            id = id, name = "Milk", quantity = 2, unit = "l",
            addedBarcodes = listOf("5901234123457"),
        )

        viewModel.useUpByBarcode("5901234123457")

        assertEquals(2, viewModel.uiState.value.products.first { it.name == "Milk" }.quantity)
        assertTrue(events.isEmpty())
        assertEquals(id, viewModel.pendingUseUp.value?.id)
    }

    @Test
    fun `scanning a code of a countable product subtracts one and reports Used`() = runTest {
        val products = FakeProductRepository()
        products.addProduct(name = "Butter", quantity = 2, unit = null)
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val events = mutableListOf<UseUpScanResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.useUpEvents.collect { events += it } }
        val id = viewModel.uiState.value.products.single().id
        viewModel.updateProduct(
            id = id, name = "Butter", quantity = 2, unit = null,
            addedBarcodes = listOf("5901234123457"),
        )

        viewModel.useUpByBarcode("5901234123457")

        assertEquals(1, viewModel.uiState.value.products.single().quantity)
        assertEquals(UseUpScanResult.Used(id, "Butter"), events.last())
    }

    @Test
    fun `scanning a code of a zero-stock product reports OutOfStock and does not go negative`() = runTest {
        val products = FakeProductRepository()
        products.addProduct(name = "Milk", quantity = 0, unit = "l")
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val events = mutableListOf<UseUpScanResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.useUpEvents.collect { events += it } }
        val id = viewModel.uiState.value.products.single().id
        viewModel.updateProduct(
            id = id, name = "Milk", quantity = 0, unit = "l",
            addedBarcodes = listOf("5901234123457"),
        )

        viewModel.useUpByBarcode("5901234123457")

        assertEquals(0, viewModel.uiState.value.products.single().quantity)
        assertEquals(UseUpScanResult.OutOfStock("Milk"), events.last())
    }

    @Test
    fun `scanning an unknown code reports UnknownCode and changes nothing`() = runTest {
        val products = FakeProductRepository()
        products.addProduct(name = "Milk", quantity = 2, unit = "l")
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        val events = mutableListOf<UseUpScanResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.useUpEvents.collect { events += it } }

        viewModel.useUpByBarcode("0000000000000")

        assertEquals(2, viewModel.uiState.value.products.single().quantity)
        assertTrue(events.last() is UseUpScanResult.UnknownCode)
    }

    @Test
    fun `saving a product leaves alone a code that has since moved elsewhere`() = runTest {
        // The edit form was opened while "X" belonged to Milk; the code was then
        // rescanned onto Cream. Saving Milk — which still reports dropping "X" —
        // used to delete by code alone and took Cream's mapping with it.
        val products = FakeProductRepository()
        val viewModel = makeViewModel(products)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.addProduct(name = "Milk", quantity = 0, unit = "l", barcodes = listOf("X"))
        viewModel.addProduct(name = "Cream", quantity = 0, unit = "l")
        val milkId = viewModel.uiState.value.products.first { it.name == "Milk" }.id
        val creamId = viewModel.uiState.value.products.first { it.name == "Cream" }.id
        viewModel.updateProduct(
            id = creamId, name = "Cream", quantity = 0, unit = "l",
            addedBarcodes = listOf("X"),
        )

        viewModel.updateProduct(
            id = milkId, name = "Milk", quantity = 0, unit = "l",
            removedBarcodes = listOf("X"),
        )

        assertEquals(listOf("X"), viewModel.uiState.value.barcodesByProduct[creamId])
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

        viewModel.updateProduct(
            id = creamId, name = "Cream", quantity = 0, unit = "l",
            addedBarcodes = listOf("X"),
        )

        assertNull(viewModel.uiState.value.barcodesByProduct[milkId])
        assertEquals(listOf("X"), viewModel.uiState.value.barcodesByProduct[creamId])
    }
}
