package com.elysium.vanguard.features.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.tasks.ScheduledTaskEntity
import com.elysium.vanguard.core.tasks.TaskScheduling
import com.elysium.vanguard.core.tasks.TaskType
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * Scheduled tasks / auto-tasks manager: list recurring jobs, toggle them,
 * delete them, and create new ones (trash purge or directory sync on an
 * interval or daily schedule).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    viewModel: ScheduledTasksViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = TitanColors.NeonCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Scheduled Tasks", color = TitanColors.NeonCyan)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TitanColors.NeonCyan)
                    }
                },
                actions = {
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Default.Add, contentDescription = "New task", tint = TitanColors.NeonCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF050810),
                    titleContentColor = TitanColors.NeonCyan,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF050810)),
        ) {
            if (tasks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = TitanColors.NeonCyan.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No scheduled tasks yet",
                        color = TitanColors.NeonCyan.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap + to automate trash purges or folder syncs",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { viewModel.setEnabled(task, it) },
                            onDelete = { viewModel.delete(task) },
                        )
                        HorizontalDivider(color = Color(0xFF1A2030), thickness = 1.dp)
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateTaskDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, type, source, dest, intervalHours, dailyHour, showError ->
                viewModel.create(name, type, source, dest, intervalHours, dailyHour) { error ->
                    if (error == null) showCreate = false else showError(error)
                }
            },
        )
    }
}

@Composable
private fun TaskRow(
    task: ScheduledTaskEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            tint = if (task.enabled) TitanColors.NeonCyan else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.name,
                color = if (task.enabled) Color.White else Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${typeLabel(task.taskType)} · ${task.triggerSummary}",
                color = TitanColors.NeonCyan.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Switch(
            checked = task.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = TitanColors.NeonCyan,
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete ${task.name}",
                tint = TitanColors.NeonRed,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun typeLabel(type: TaskType): String = when (type) {
    TaskType.PURGE_TRASH -> "PURGE TRASH"
    TaskType.COPY_DIRECTORY -> "COPY FOLDER"
}

private enum class TriggerMode { INTERVAL, DAILY }

@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        type: TaskType,
        sourcePath: String,
        destPath: String,
        intervalHours: Int,
        dailyHour: Int,
        showError: (String) -> Unit,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TaskType.PURGE_TRASH) }
    var source by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("") }
    var triggerMode by remember { mutableStateOf(TriggerMode.INTERVAL) }
    var intervalText by remember { mutableStateOf("6") }
    var hourText by remember { mutableStateOf("3") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0C111C),
        title = {
            Text("NEW SCHEDULED TASK", color = TitanColors.NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TaskField(name, { name = it }, "Name")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeChip("PURGE TRASH", type == TaskType.PURGE_TRASH) { type = TaskType.PURGE_TRASH }
                    TypeChip("COPY FOLDER", type == TaskType.COPY_DIRECTORY) { type = TaskType.COPY_DIRECTORY }
                }

                if (type == TaskType.COPY_DIRECTORY) {
                    TaskField(source, { source = it }, "Source folder path")
                    TaskField(dest, { dest = it }, "Destination folder path")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeChip("EVERY N HOURS", triggerMode == TriggerMode.INTERVAL) {
                        triggerMode = TriggerMode.INTERVAL
                    }
                    TypeChip("DAILY", triggerMode == TriggerMode.DAILY) {
                        triggerMode = TriggerMode.DAILY
                    }
                }

                if (triggerMode == TriggerMode.INTERVAL) {
                    TaskField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter(Char::isDigit) },
                        label = "Interval in hours (min ${TaskScheduling.MIN_INTERVAL_HOURS})",
                        keyboardType = KeyboardType.Number,
                    )
                } else {
                    TaskField(
                        value = hourText,
                        onValueChange = { hourText = it.filter(Char::isDigit) },
                        label = "Hour of day (0–23)",
                        keyboardType = KeyboardType.Number,
                    )
                }

                error?.let {
                    Text(it, color = TitanColors.NeonRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val intervalHours = if (triggerMode == TriggerMode.INTERVAL) {
                        (intervalText.toIntOrNull() ?: 0).coerceAtLeast(TaskScheduling.MIN_INTERVAL_HOURS)
                    } else 0
                    val dailyHour = if (triggerMode == TriggerMode.DAILY) {
                        hourText.toIntOrNull() ?: -1
                    } else -1
                    onCreate(name, type, source, dest, intervalHours, dailyHour) { error = it }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TitanColors.NeonCyan, contentColor = Color.Black),
            ) {
                Text("CREATE", fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color.White.copy(alpha = 0.5f))
            }
        },
    )
}

@Composable
private fun TaskField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TitanColors.NeonCyan,
            unfocusedBorderColor = TitanColors.NeonCyan.copy(alpha = 0.35f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = TitanColors.NeonCyan,
            unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) TitanColors.NeonCyan else Color.White.copy(alpha = 0.2f),
        ),
        modifier = Modifier.height(32.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            label,
            color = if (selected) TitanColors.NeonCyan else Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
