package com.tamir.hamasa.ui.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.db.Branch
import com.tamir.hamasa.data.db.ExerciseEntity
import com.tamir.hamasa.domain.coach.ExerciseTargets
import com.tamir.hamasa.domain.game.Voice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The form being edited. [id] is empty for a new exercise. */
data class CustomExerciseForm(
    val id: String = "",
    val nameHe: String = "",
    val description: String = "",
    val branch: Branch = Branch.CHEST,
    val target: String = ExerciseTargets.CHEST,
    val equipment: String = "machine",
    val isCompound: Boolean = false,
    val unit: String = "kg",
    val preferred: Boolean = false
) {
    val isNew: Boolean get() = id.isEmpty()
    val canSave: Boolean get() = nameHe.trim().isNotEmpty()
}

class CustomExercisesViewModel : ViewModel() {

    val exercises: StateFlow<List<ExerciseEntity>> =
        AppGraph.workouts.customExercises.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Null = list view; non-null = the form is open. */
    private val _form = MutableStateFlow<CustomExerciseForm?>(null)
    val form: StateFlow<CustomExerciseForm?> = _form

    /** Exercise waiting for delete confirmation. */
    private val _deleting = MutableStateFlow<ExerciseEntity?>(null)
    val deleting: StateFlow<ExerciseEntity?> = _deleting

    /** Shambiuluk's one-liner after a save, auto-cleared. */
    private val _voice = MutableStateFlow<String?>(null)
    val voice: StateFlow<String?> = _voice

    private var voiceJob: Job? = null

    fun startNew() {
        _form.value = CustomExerciseForm()
    }

    fun startEdit(e: ExerciseEntity) {
        val target = if (ExerciseTargets.byKey(e.target) != null) e.target else ExerciseTargets.defaultFor(e.branch)
        _form.value = CustomExerciseForm(
            id = e.id,
            nameHe = e.nameHe,
            description = e.description,
            branch = e.branch,
            target = target,
            equipment = e.equipment,
            isCompound = e.isCompound,
            unit = e.unit,
            preferred = e.preferred
        )
    }

    fun cancel() {
        _form.value = null
    }

    fun update(change: (CustomExerciseForm) -> CustomExerciseForm) {
        val cur = _form.value ?: return
        _form.value = change(cur)
    }

    /** Picking a branch also moves the target to the first target of that branch when the current one does not belong. */
    fun setBranch(b: Branch) {
        update { f ->
            val stillValid = ExerciseTargets.byKey(f.target)?.branch == b
            f.copy(branch = b, target = if (stillValid) f.target else ExerciseTargets.defaultFor(b))
        }
    }

    fun setTarget(key: String) {
        val def = ExerciseTargets.byKey(key) ?: return
        update { f -> f.copy(target = key, branch = def.branch) }
    }

    fun save() {
        val f = _form.value ?: return
        if (!f.canSave) return
        val isNew = f.isNew
        val entity = ExerciseEntity(
            id = if (isNew) AppGraph.workouts.newCustomId() else f.id,
            nameHe = f.nameHe.trim(),
            branch = f.branch,
            equipment = f.equipment,
            isCompound = f.isCompound,
            unit = f.unit,
            alternativesCsv = "",
            isCustom = true,
            description = f.description.trim(),
            target = f.target,
            preferred = f.preferred
        )
        _form.value = null
        viewModelScope.launch {
            AppGraph.workouts.saveCustomExercise(entity)
            CloudSync.pushSoon(AppGraph.appContext)
            if (isNew) showVoice(Voice.customExerciseCreated(entity.nameHe)) else showVoice("עודכן. רשמתי.")
        }
    }

    fun askDelete(e: ExerciseEntity) {
        _deleting.value = e
    }

    fun cancelDelete() {
        _deleting.value = null
    }

    fun confirmDelete() {
        val e = _deleting.value ?: return
        _deleting.value = null
        viewModelScope.launch {
            AppGraph.workouts.deleteCustomExercise(e.id)
            showVoice("נמחק. שיהיה רשום: לא אני ביקשתי.")
        }
    }

    fun dismissVoice() {
        voiceJob?.cancel()
        _voice.value = null
    }

    private fun showVoice(text: String) {
        voiceJob?.cancel()
        _voice.value = text
        voiceJob = viewModelScope.launch {
            delay(6000)
            _voice.value = null
        }
    }
}
