package com.elysium.vanguard.features.filemanager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.vanguard.core.rename.BatchRenameEngine
import com.elysium.vanguard.core.rename.BatchRenameException
import com.elysium.vanguard.ui.theme.GlobalColors
import com.elysium.vanguard.ui.theme.SectionColorManager
import com.elysium.vanguard.ui.theme.neonGlass
import java.io.File

private enum class RenameMode { TEMPLATE, REGEX }

/**
 * Batch rename dialog (Phase 1.6 UI): template mode (`{counter}` …) or
 * regex search/replace mode, with a live old → new preview computed by the
 * pure [BatchRenameEngine]. Applying hands the built [BatchRenameEngine.Pattern]
 * back to the caller (the file manager view model executes + reloads).
 */
@Composable
fun BatchRenameDialog(
    files: List<File>,
    onDismiss: () -> Unit,
    onApply: (BatchRenameEngine.Pattern) -> Unit,
) {
    val accentColor = SectionColorManager.fileAccent

    var mode by remember { mutableStateOf(RenameMode.TEMPLATE) }
    var template by remember { mutableStateOf("{counter}_{name}") }
    var regexPattern by remember { mutableStateOf("") }
    var regexReplacement by remember { mutableStateOf("") }
    var ignoreCase by remember { mutableStateOf(false) }
    var wholeName by remember { mutableStateOf(false) }
    var conflictIndex by remember { mutableStateOf(0) }

    val conflicts = listOf(
        BatchRenameEngine.ConflictResolution.SKIP,
        BatchRenameEngine.ConflictResolution.APPEND_SUFFIX,
        BatchRenameEngine.ConflictResolution.ABORT,
    )
    val conflict = conflicts[conflictIndex]

    val pattern = when (mode) {
        RenameMode.TEMPLATE -> BatchRenameEngine.Pattern(
            template = template,
            onConflict = conflict,
        )
        RenameMode.REGEX -> BatchRenameEngine.Pattern(
            template = "", // unused — regex rule drives the name
            onConflict = conflict,
            regex = BatchRenameEngine.RegexRule(
                pattern = regexPattern,
                replacement = regexReplacement,
                ignoreCase = ignoreCase,
                applyToWholeName = wholeName,
            ),
        )
    }

    // Live preview — pure engine call; invalid regex surfaces as an error.
    val preview = remember(files, template, regexPattern, regexReplacement, ignoreCase, wholeName, conflict, mode) {
        try {
            Result.success(BatchRenameEngine().plan(files, pattern))
        } catch (e: BatchRenameException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    val plan = preview.getOrNull()
    val previewError = preview.exceptionOrNull()?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlobalColors.primary.copy(alpha = 0.12f),
        title = {
            Text(
                "BATCH RENAME · ${files.size} FILES",
                color = accentColor,
                fontFamily = FontFamily.Monospace,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Mode switch
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("PATTERN", mode == RenameMode.TEMPLATE, accentColor) {
                        mode = RenameMode.TEMPLATE
                    }
                    ModeChip("REGEX", mode == RenameMode.REGEX, accentColor) {
                        mode = RenameMode.REGEX
                    }
                }

                if (mode == RenameMode.TEMPLATE) {
                    RenameField(
                        value = template,
                        onValueChange = { template = it },
                        label = "Pattern  {counter} {name} {date} {ext} {parent} {size}",
                        accentColor = accentColor,
                    )
                } else {
                    RenameField(
                        value = regexPattern,
                        onValueChange = { regexPattern = it },
                        label = "Search (regex) — groups: $1 $2",
                        accentColor = accentColor,
                    )
                    RenameField(
                        value = regexReplacement,
                        onValueChange = { regexReplacement = it },
                        label = "Replace",
                        accentColor = accentColor,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = ignoreCase,
                            onCheckedChange = { ignoreCase = it },
                            colors = CheckboxDefaults.colors(checkedColor = accentColor),
                        )
                        Text("Ignore case", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Checkbox(
                            checked = wholeName,
                            onCheckedChange = { wholeName = it },
                            colors = CheckboxDefaults.colors(checkedColor = accentColor),
                        )
                        Text("Whole name", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }

                // Conflict strategy
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "ON CONFLICT:",
                        color = accentColor.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    listOf("SKIP", "SUFFIX", "ABORT").forEachIndexed { i, label ->
                        val selected = conflictIndex == i
                        OutlinedButton(
                            onClick = { conflictIndex = i },
                            border = BorderStroke(1.dp, if (selected) accentColor else Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier.height(28.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                label,
                                color = if (selected) accentColor else Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                // Preview
                when {
                    previewError != null -> Text(
                        previewError,
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    plan != null && plan.renames.isEmpty() && plan.skipped.isEmpty() ->
                        Text(
                            "No changes — pattern produces no renames",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                        )
                    plan != null -> {
                        val skippedNames = plan.skipped.map { it.name }.toSet()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                                .neonGlass(cornerRadius = 12.dp, glowColor = accentColor.copy(alpha = 0.08f))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            plan.renames.forEach { rename ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        rename.original.name,
                                        color = Color.White.copy(alpha = 0.45f),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Text(
                                        " → ",
                                        color = accentColor,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        rename.renamed.name,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                }
                            }
                            plan.skipped.forEach { skipped ->
                                Text(
                                    "SKIP  ${skipped.name}",
                                    color = Color(0xFFFF6B6B).copy(alpha = 0.8f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            if (plan.aborted) {
                                Text(
                                    "ABORTED at first conflict",
                                    color = Color(0xFFFF6B6B),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(pattern) },
                enabled = plan != null && plan.renames.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GlobalColors.primary.copy(alpha = 0.2f),
                    disabledContainerColor = Color.White.copy(alpha = 0.08f),
                ),
                border = BorderStroke(1.dp, GlobalColors.primary),
            ) {
                Text(
                    "EXECUTE (${plan?.renames?.size ?: 0})",
                    color = if (plan != null && plan.renames.isNotEmpty()) GlobalColors.primary
                    else Color.White.copy(alpha = 0.4f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ABORT", color = Color.White.copy(alpha = 0.5f))
            }
        },
    )
}

@Composable
private fun ModeChip(label: String, selected: Boolean, accentColor: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, if (selected) accentColor else Color.White.copy(alpha = 0.2f)),
        modifier = Modifier.height(30.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        Text(
            label,
            color = if (selected) accentColor else Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun RenameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    accentColor: Color,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .neonGlass(cornerRadius = 16.dp, glowColor = accentColor.copy(alpha = 0.1f)),
        colors = TextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = accentColor,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = accentColor,
            unfocusedIndicatorColor = Color.White.copy(alpha = 0.1f),
        ),
    )
}
