package io.github.rafalpawlisz.shelfie.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v1 -> v2: soft delete (archiving). Historically applied via destructive
// fallback; written out so the migration chain is complete from v1.
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN archivedAt INTEGER")
    }
}

// v2 -> v3: creation timestamp (backfilled from updatedAt for existing rows),
// restock threshold and free-form notes.
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE products ADD COLUMN minQuantity INTEGER")
        db.execSQL("ALTER TABLE products ADD COLUMN notes TEXT")
        db.execSQL("UPDATE products SET createdAt = updatedAt")
    }
}
