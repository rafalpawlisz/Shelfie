package io.github.rafalpawlisz.shelfie.pantry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class FakeShoppingListRepositoryTest {

    // The real DAO's insert throws SQLiteConstraintException for a list or
    // product that does not exist; the fake must refuse the same writes, or
    // tests pass against rows the real app could never hold.
    private suspend fun assertRefused(block: suspend () -> Unit) {
        try {
            block()
            fail("expected the write to be refused")
        } catch (expected: IllegalStateException) {
            // refused as intended
        }
    }

    @Test
    fun `addItem refuses a nonexistent list or product`() = runTest {
        val products = FakeProductRepository()
        val repository = FakeShoppingListRepository(products)
        val productId = products.addProduct(name = "Milk", quantity = 0, unit = "l")
        val listId = repository.createList("Lidl")

        assertRefused { repository.addItem("no-such-list", productId, amount = null) }
        assertRefused { repository.addItem(listId, "no-such-product", amount = null) }

        // A valid write still goes through the checks.
        repository.addItem(listId, productId, amount = 1)
    }

    @Test
    fun `addOneOffItem refuses a nonexistent list`() = runTest {
        val repository = FakeShoppingListRepository(FakeProductRepository())

        assertRefused { repository.addOneOffItem("no-such-list", "żarówka", amount = null) }
    }
}
