package com.tamir.hamasa.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.TemplateItemEntity
import com.tamir.hamasa.data.db.WorkoutTemplateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The list screen of "האימונים שלי". */
class TemplatesViewModel : ViewModel() {

    val templates: StateFlow<List<WorkoutTemplateEntity>> =
        AppGraph.workouts.templates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** templateId -> number of exercises. */
    val counts: StateFlow<Map<Long, Int>> =
        AppGraph.workouts.templateItems
            .map { items: List<TemplateItemEntity> -> items.groupBy { it.templateId }.mapValues { it.value.size } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Template waiting for delete confirmation. */
    private val _deleting = MutableStateFlow<WorkoutTemplateEntity?>(null)
    val deleting: StateFlow<WorkoutTemplateEntity?> = _deleting

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting

    fun askDelete(t: WorkoutTemplateEntity) {
        _deleting.value = t
    }

    fun cancelDelete() {
        _deleting.value = null
    }

    fun confirmDelete() {
        val t = _deleting.value ?: return
        _deleting.value = null
        viewModelScope.launch(Dispatchers.IO) { AppGraph.workouts.deleteTemplate(t.id) }
    }

    /** Starts a session from the template through the normal game loop and hands the session id to [onStarted]. */
    fun start(templateId: Long, onStarted: (Long) -> Unit) {
        if (_starting.value) return
        _starting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = AppGraph.workouts.startFromTemplate(
                    templateId,
                    // today's sleep as the morning roll uses it (watch or his slider); null when unknown
                    runCatching { com.tamir.hamasa.data.health.WatchSleep.todayHours(AppGraph.prefs.current()) }.getOrNull()
                )
                withContext(Dispatchers.Main) { onStarted(id) }
            } finally {
                _starting.value = false
            }
        }
    }
}
