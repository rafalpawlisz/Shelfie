package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OneOffSuggestionDao {

    // Newest first: what was bought last month is a likelier guess than what was
    // bought last year, and the picker shows only the head of this list.
    @Query("SELECT * FROM one_off_suggestions ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<OneOffSuggestionEntity>>

    // Upsert, not insert: the id is derived from the name, so buying the same
    // thing again moves the existing row up instead of adding a second one.
    @Upsert
    suspend fun upsert(entity: OneOffSuggestionEntity)

    @Query("DELETE FROM one_off_suggestions WHERE id = :id")
    suspend fun delete(id: String)

    // --- Sync apply (pull direction) ---

    @Query("SELECT updatedAt FROM one_off_suggestions WHERE id = :id")
    suspend fun updatedAtOf(id: String): Long?

    // Reconcile candidates: rows already part of a completed sync (see
    // ProductDao.idsSyncedUpTo).
    @Query("SELECT id FROM one_off_suggestions WHERE updatedAt <= :syncedUpTo")
    suspend fun idsSyncedUpTo(syncedUpTo: Long): List<String>
}
