package com.elysium.vanguard.features.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.tasks.ScheduledTaskEntity
import com.elysium.vanguard.core.tasks.ScheduledTaskRepository
import com.elysium.vanguard.core.tasks.TaskType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScheduledTasksViewModel @Inject constructor(
    private val repository: ScheduledTaskRepository,
) : ViewModel() {

    val tasks: StateFlow<List<ScheduledTaskEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Create a task; [onResult] receives an error message (stays open) or
     * `null` on success (caller closes the dialog).
     */
    fun create(
        name: String,
        type: TaskType,
        sourcePath: String,
        destPath: String,
        intervalHours: Int,
        dailyHour: Int,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val error = repository.create(
                name = name,
                type = type,
                sourcePath = sourcePath,
                destPath = destPath,
                intervalHours = intervalHours,
                dailyHour = dailyHour,
            )
            onResult(error)
        }
    }

    fun setEnabled(task: ScheduledTaskEntity, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(task.id, enabled) }
    }

    fun delete(task: ScheduledTaskEntity) {
        viewModelScope.launch { repository.delete(task.id) }
    }
}
