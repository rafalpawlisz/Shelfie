package io.github.rafalpawlisz.shelfie.data.sync

/** Firestore subcollections under households/{hid} mirroring the Room tables. */
enum class SyncCollection(val path: String) {
    PRODUCTS("products"),
    LISTS("lists"),
    ITEMS("items"),
    BARCODES("barcodes"),
    LIST_ORDER("listOrder"),
}

/**
 * What repositories see of the sync layer: deletion notifications only.
 * Upserts are not hooked — the engine observes the Room flows and mirrors
 * row changes by diffing, which covers every mutation path by construction.
 * Deletions can't be recovered from a snapshot diff across process restarts,
 * so they are reported explicitly at mutation time; the Firestore SDK's
 * durable offline queue takes it from there.
 */
interface SyncEngine {
    fun onDeleted(collection: SyncCollection, docIds: List<String>)
}

/** Default wiring for tests and for builds without sync. */
object NoopSyncEngine : SyncEngine {
    override fun onDeleted(collection: SyncCollection, docIds: List<String>) = Unit
}

/** Composite doc id of a product_list_order row. */
fun listOrderDocId(listId: String, productId: String): String = "${listId}_$productId"
