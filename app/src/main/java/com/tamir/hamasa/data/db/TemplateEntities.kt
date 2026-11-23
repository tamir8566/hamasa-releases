package com.tamir.hamasa.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * "האימונים שלי": a workout the user built himself. Starting it creates a normal GYM session
 * through WorkoutRepository.startSession, so XP, gold, badges, bosses and the streak behave exactly
 * like a coach workout. The coach never changes the reps or weights stored here.
 *
 * Added in db version 4 (see AppDatabase.MIGRATION_3_4). Column types must stay in sync with the SQL there.
 * TemplateItemEntity.setsCsv was added in db version 5 (AppDatabase.MIGRATION_4_5).
 */
@Entity(tableName = "workout_templates")
data class WorkoutTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nameHe: String,
    val createdAt: Long,
    val lastUsedAt: Long = 0L,          // 0 = never started
    val notes: String = ""
)

/** One exercise row inside a template, in [orderIndex] order. [reps] means seconds when the exercise unit is "sec". */
@Entity(tableName = "template_items", indices = [Index("templateId")])
data class TemplateItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val orderIndex: Int,
    val exerciseId: String,
    val sets: Int,
    val reps: Int,
    val weightKg: Float,                // 0 = bodyweight, or "use the last known weight" when the plan is built
    val restSec: Int,
    val note: String = "",
    /**
     * Per-set targets "kg:reps;kg:reps;..." (kg plain decimal with '.', reps int; seconds for unit "sec").
     * Empty = legacy row: [sets] × ([weightKg], [reps]). Read through [decodeSets]. Added in db version 5.
     */
    val setsCsv: String = ""
)

/** One set row of a template exercise. [kg] 0 = bodyweight (or "last known weight" for a weighted exercise). */
data class SetSpec(val kg: Float, val reps: Int)

/** Plain decimal with '.', no trailing ".0" ("35", "22.5"). */
fun formatSetKg(kg: Float): String {
    val v = if (kg.isNaN() || kg < 0f) 0f else kg
    val whole = v.toLong()
    return if (v == whole.toFloat()) whole.toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}

/** "kg:reps;kg:reps". Empty list -> "". */
fun encodeSets(list: List<SetSpec>): String =
    list.joinToString(";") { formatSetKg(it.kg) + ":" + it.reps.coerceAtLeast(0).toString() }

/** Parses [encodeSets] output. Malformed pieces are skipped; ',' is accepted as a decimal separator. */
fun decodeSetsCsv(csv: String): List<SetSpec> {
    if (csv.isBlank()) return emptyList()
    val out = ArrayList<SetSpec>()
    for (piece in csv.split(';')) {
        val parts = piece.trim().split(':')
        if (parts.size != 2) continue
        val kg = parts[0].trim().replace(',', '.').toFloatOrNull() ?: continue
        val reps = parts[1].trim().toIntOrNull() ?: continue
        out.add(SetSpec(kg.coerceAtLeast(0f), reps.coerceAtLeast(0)))
    }
    return out
}

/** The item's sets: [TemplateItemEntity.setsCsv] when present, else the legacy sets × (weightKg, reps). Never empty. */
fun decodeSets(item: TemplateItemEntity): List<SetSpec> {
    val parsed = decodeSetsCsv(item.setsCsv)
    if (parsed.isNotEmpty()) return parsed
    val n = item.sets.coerceAtLeast(1)
    return List(n) { SetSpec(item.weightKg.coerceAtLeast(0f), item.reps.coerceAtLeast(0)) }
}
