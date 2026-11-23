package com.tamir.hamasa.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Templates ("האימונים שלי") and their items. An abstract class (not an interface) so the
 * @Transaction helpers below have plain bodies that Room wraps in a transaction.
 */
@Dao
abstract class TemplateDao {

    @Query("SELECT * FROM workout_templates ORDER BY lastUsedAt DESC, id DESC")
    abstract fun templates(): Flow<List<WorkoutTemplateEntity>>

    @Query("SELECT * FROM workout_templates WHERE id = :id LIMIT 1")
    abstract suspend fun byId(id: Long): WorkoutTemplateEntity?

    @Query("SELECT * FROM template_items WHERE templateId = :templateId ORDER BY orderIndex ASC, id ASC")
    abstract fun items(templateId: Long): Flow<List<TemplateItemEntity>>

    @Query("SELECT * FROM template_items WHERE templateId = :templateId ORDER BY orderIndex ASC, id ASC")
    abstract suspend fun itemsOnce(templateId: Long): List<TemplateItemEntity>

    /** Every item of every template, for counting exercises per template on the list screen. */
    @Query("SELECT * FROM template_items ORDER BY templateId ASC, orderIndex ASC")
    abstract fun allItems(): Flow<List<TemplateItemEntity>>

    // --- export / import / cloud (stage 1 D) ---
    @Query("SELECT * FROM workout_templates ORDER BY id")
    abstract suspend fun templatesOnce(): List<WorkoutTemplateEntity>

    @Query("SELECT * FROM template_items ORDER BY id")
    abstract suspend fun allItemsOnce(): List<TemplateItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTemplates(list: List<WorkoutTemplateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertItems(list: List<TemplateItemEntity>)

    @Insert
    abstract suspend fun insertTemplate(t: WorkoutTemplateEntity): Long

    @Update
    abstract suspend fun updateTemplate(t: WorkoutTemplateEntity)

    @Insert
    abstract suspend fun insertItems(items: List<TemplateItemEntity>)

    @Query("DELETE FROM template_items WHERE templateId = :templateId")
    abstract suspend fun deleteItems(templateId: Long)

    @Query("DELETE FROM workout_templates WHERE id = :id")
    abstract suspend fun deleteTemplateRow(id: Long)

    @Query("UPDATE workout_templates SET lastUsedAt = :at WHERE id = :id")
    abstract suspend fun markUsed(id: Long, at: Long)

    @Query("UPDATE template_items SET orderIndex = :orderIndex WHERE id = :itemId")
    abstract suspend fun setOrder(itemId: Long, orderIndex: Int)

    /**
     * Inserts (id == 0) or updates the template and replaces all of its items.
     * Items get the returned template id and their position as orderIndex. Returns the template id.
     */
    @Transaction
    open suspend fun saveWithItems(template: WorkoutTemplateEntity, items: List<TemplateItemEntity>): Long {
        val id: Long = if (template.id == 0L) {
            insertTemplate(template)
        } else {
            updateTemplate(template)
            template.id
        }
        deleteItems(id)
        val rows = items.mapIndexed { index, item -> item.copy(id = 0L, templateId = id, orderIndex = index) }
        if (rows.isNotEmpty()) insertItems(rows)
        return id
    }

    /** Deletes the template and all of its items. */
    @Transaction
    open suspend fun deleteWithItems(id: Long) {
        deleteItems(id)
        deleteTemplateRow(id)
    }

    /** Rewrites orderIndex for the given item ids in the order they are passed. */
    @Transaction
    open suspend fun reorder(itemIdsInOrder: List<Long>) {
        itemIdsInOrder.forEachIndexed { index, itemId -> setOrder(itemId, index) }
    }
}
