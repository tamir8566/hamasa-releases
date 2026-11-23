package com.tamir.hamasa.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.data.db.SetSpec
import com.tamir.hamasa.data.db.TemplateItemEntity
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import com.tamir.hamasa.data.db.decodeSets
import com.tamir.hamasa.data.db.encodeSets
import com.tamir.hamasa.data.db.formatSetKg
import com.tamir.hamasa.domain.game.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One set row of the per-set table. Text is kept as typed; [id] is only for stable Compose keys. */
data class EditorSetRow(
    val id: Long,
    val kgText: String,
    val repsText: String
)

/** One exercise row in the editor. [key] is only for stable Compose keys. */
data class EditorItem(
    val key: Long,
    val exerciseId: String,
    val nameHe: String,
    val branch: Branch,
    val unit: String,              // "kg" | "reps" | "sec"
    val isCompound: Boolean,
    /** One row per set, in order. Never empty. */
    val rows: List<EditorSetRow>,
    /** What he did per set index the last time he finished this exercise ("הקודם"). Empty = never. */
    val previous: List<SetSpec>,
    val restSec: Int,
    val note: String = ""
)

/** "35", "22.5". */
internal fun kgInputText(kg: Float): String = formatSetKg(kg)

/** Typed kg text: digits plus one decimal separator (',' becomes '.'), at most 6 characters. */
internal fun cleanKgInput(v: String): String {
    val sb = StringBuilder()
    var dot = false
    for (c in v) {
        if (c.isDigit()) sb.append(c)
        else if ((c == '.' || c == ',') && !dot) { sb.append('.'); dot = true }
        if (sb.length >= 6) break
    }
    return sb.toString()
}

/** Typed reps/seconds text: digits only, at most 4. */
internal fun cleanRepsInput(v: String): String = v.filter { it.isDigit() }.take(4)

data class EditorState(
    val templateId: Long = 0L,        // 0 = new
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val nameHe: String = "",
    val notes: String = "",
    val items: List<EditorItem> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    /** All exercises (catalog + custom) for the picker. */
    val exercises: List<ExerciseEntity> = emptyList(),
    val pickerOpen: Boolean = false,
    val filter: String = "",
    /** Shambiuluk's line after a successful save; the screen shows it and then leaves. */
    val savedVoice: String? = null
) {
    val canSave: Boolean get() = nameHe.trim().isNotEmpty() && items.isNotEmpty() && !saving && !loading
}

/** Editor for one of the user's own workouts. Call [load] once with the route's template id (-1 or 0 = new). */
class TemplateEditorViewModel : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    private var loadedFor: Long? = null
    private val keys = java.util.concurrent.atomic.AtomicLong(1L)

    fun load(templateId: Long) {
        if (loadedFor != null) return
        loadedFor = templateId
        viewModelScope.launch(Dispatchers.IO) {
            val all = AppGraph.workouts.allExercisesOnce().sortedBy { it.nameHe }
            if (templateId <= 0L) {
                _state.update { it.copy(templateId = 0L, exercises = all, loading = false) }
                return@launch
            }
            val t = AppGraph.workouts.templateOnce(templateId)
            if (t == null) {
                _state.update { it.copy(templateId = 0L, exercises = all, loading = false) }
                return@launch
            }
            val byId = all.associateBy { it.id }
            val rows = ArrayList<EditorItem>()
            for (row in AppGraph.workouts.templateItemsOnce(templateId)) {
                val e = byId[row.exerciseId] ?: continue
                rows.add(
                    EditorItem(
                        key = keys.getAndIncrement(), exerciseId = e.id, nameHe = e.nameHe, branch = e.branch, unit = e.unit, isCompound = e.isCompound,
                        rows = decodeSets(row).map { toRow(it) }, previous = previousOf(e.id),
                        restSec = row.restSec, note = row.note
                    )
                )
            }
            _state.update {
                it.copy(
                    templateId = t.id, createdAt = t.createdAt, lastUsedAt = t.lastUsedAt,
                    nameHe = t.nameHe, notes = t.notes, items = rows, exercises = all, loading = false
                )
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(nameHe = v.take(60)) }
    fun setNotes(v: String) = _state.update { it.copy(notes = v.take(300)) }

    fun openPicker() = _state.update { it.copy(pickerOpen = true, filter = "") }
    fun closePicker() = _state.update { it.copy(pickerOpen = false, filter = "") }
    fun setFilter(v: String) = _state.update { it.copy(filter = v.take(40)) }

    private fun toRow(spec: SetSpec): EditorSetRow =
        EditorSetRow(id = keys.getAndIncrement(), kgText = kgInputText(spec.kg), repsText = spec.reps.toString())

    /** "הקודם": completed sets of the last finished session with this exercise, in set order. */
    private suspend fun previousOf(exerciseId: String): List<SetSpec> =
        AppGraph.workouts.lastDoneSetsFor(exerciseId).map { SetSpec(it.weight, it.reps) }

    /** Adds the exercise with 3 rows prefilled from "הקודם" when there is history; the user then edits the table. */
    fun addExercise(e: ExerciseEntity) {
        _state.update { it.copy(pickerOpen = false, filter = "") }
        viewModelScope.launch(Dispatchers.IO) {
            val previous = previousOf(e.id)
            val fallback = SetSpec(0f, if (e.unit == "sec") 30 else 8)
            val specs = List(3) { i -> previous.getOrNull(i) ?: previous.lastOrNull() ?: fallback }
                .map { if (e.unit == "kg") it else it.copy(kg = 0f) }
            val rest = when (e.unit) {
                "sec" -> 30
                "reps" -> 60
                else -> if (e.isCompound) 150 else 75
            }
            val item = EditorItem(
                key = keys.getAndIncrement(), exerciseId = e.id, nameHe = e.nameHe, branch = e.branch, unit = e.unit, isCompound = e.isCompound,
                rows = specs.map { toRow(it) }, previous = previous, restSec = rest
            )
            _state.update { it.copy(items = it.items + item) }
        }
    }

    fun remove(key: Long) = _state.update { s -> s.copy(items = s.items.filter { it.key != key }) }

    fun moveUp(key: Long) = move(key, -1)
    fun moveDown(key: Long) = move(key, 1)

    private fun move(key: Long, delta: Int) {
        _state.update { s ->
            val list = s.items.toMutableList()
            val i = list.indexOfFirst { it.key == key }
            val j = i + delta
            if (i < 0 || j < 0 || j >= list.size) return@update s
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
            s.copy(items = list)
        }
    }

    private fun edit(key: Long, change: (EditorItem) -> EditorItem) {
        _state.update { s -> s.copy(items = s.items.map { if (it.key == key) change(it) else it }) }
    }

    fun setKg(key: Long, rowId: Long, text: String) = edit(key) { item ->
        item.copy(rows = item.rows.map { if (it.id == rowId) it.copy(kgText = cleanKgInput(text)) else it })
    }

    fun setReps(key: Long, rowId: Long, text: String) = edit(key) { item ->
        item.copy(rows = item.rows.map { if (it.id == rowId) it.copy(repsText = cleanRepsInput(text)) else it })
    }

    /** "+ הוסף סט": a new row copying the previous row's values. At most 12 sets. */
    fun addRow(key: Long) = edit(key) { item ->
        if (item.rows.size >= 12) return@edit item
        val last = item.rows.lastOrNull()
        val row = EditorSetRow(id = keys.getAndIncrement(), kgText = last?.kgText ?: "0", repsText = last?.repsText ?: "8")
        item.copy(rows = item.rows + row)
    }

    /** Deletes one set row; the last remaining row stays. */
    fun removeRow(key: Long, rowId: Long) = edit(key) { item ->
        if (item.rows.size <= 1) item else item.copy(rows = item.rows.filter { it.id != rowId })
    }

    fun stepRest(key: Long, delta: Int) = edit(key) { it.copy(restSec = (it.restSec + delta).coerceIn(30, 240)) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        val now = System.currentTimeMillis()
        val template = WorkoutTemplateEntity(
            id = s.templateId,
            nameHe = s.nameHe.trim(),
            createdAt = if (s.templateId == 0L) now else s.createdAt,
            lastUsedAt = s.lastUsedAt,
            notes = s.notes.trim()
        )
        val items = s.items.mapIndexed { index, it ->
            val specs = it.rows.map { r ->
                val kg = if (it.unit == "kg") (r.kgText.replace(',', '.').toFloatOrNull() ?: 0f).coerceIn(0f, 500f) else 0f
                val reps = (r.repsText.toIntOrNull() ?: 0).coerceIn(1, 999)
                SetSpec(kg, reps)
            }.ifEmpty { listOf(SetSpec(0f, 8)) }
            TemplateItemEntity(
                templateId = s.templateId, orderIndex = index, exerciseId = it.exerciseId,
                // legacy fields mirror the first set; setsCsv holds every set
                sets = specs.size, reps = specs.first().reps, weightKg = specs.first().kg,
                restSec = it.restSec, note = it.note, setsCsv = encodeSets(specs)
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val id = AppGraph.workouts.saveTemplate(template, items)
            runCatching { CloudSync.pushSoon(AppGraph.appContext) }
            _state.update { it.copy(templateId = id, saving = false, savedVoice = Voice.templateSaved()) }
        }
    }

    fun dismissSaved() = _state.update { it.copy(savedVoice = null) }
}
