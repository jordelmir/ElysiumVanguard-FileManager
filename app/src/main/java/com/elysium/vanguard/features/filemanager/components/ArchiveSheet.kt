package com.elysium.vanguard.features.filemanager.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.vanguard.core.util.ArchiveFormat
import com.elysium.vanguard.core.util.CompressionEngine
import com.elysium.vanguard.ui.theme.TitanColors
import java.io.File

/**
 * PHASE 10.3 — ZArchiver-style archive bottom sheet.
 *
 * One component, two modes (`Compress` / `Extract`), all formats
 * ([ArchiveFormat] surfaces), password input, live progress bar,
 * cancel button, done state. Mounted from the file manager's
 * "Archive" FAB and from the file-row overflow menu.
 *
 * The sheet is self-contained: it talks to the compression engine
 * directly via coroutines inside the sheet, broadcasting progress
 * to whoever mounts it. We deliberately don't go through the
 * foreground service for the in-app operation — the service is
 * still there for the "minimize and let it finish" use case but
 * for a quick 5-file ZIP we want the user to see the progress
 * bar right in the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveSheet(
    mode: ArchiveMode,
    initialFiles: List<File>,
    initialArchive: File? = null,
    detectedFormat: ArchiveFormat? = null,
    onDismiss: () -> Unit,
    onCompress: (
        files: List<File>,
        output: File,
        format: ArchiveFormat,
        password: String?,
        options: CompressionEngine.CompressionOptions
    ) -> Unit,
    onExtract: (
        archive: File,
        outputDir: File,
        password: String?
    ) -> Unit,
    progress: ArchiveProgress? = null
) {
    val isWorking = progress != null && !progress.done
    val isDone = progress?.done == true && progress.error == null
    val isError = progress?.done == true && progress.error != null

    var format by remember {
        mutableStateOf(
            detectedFormat
                ?: (if (mode == ArchiveMode.Compress) ArchiveFormat.ZIP else initialArchive?.let {
                    ArchiveFormat.fromPath(it.absolutePath)
                } ?: ArchiveFormat.ZIP)
        )
    }
    var usePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var archiveName by remember {
        mutableStateOf(
            when (mode) {
                ArchiveMode.Compress -> {
                    val first = initialFiles.firstOrNull()?.nameWithoutExtension
                        ?: "archive"
                    val multi = initialFiles.size > 1
                    val baseName = if (multi) "archive" else first
                    "$baseName${format.canonicalExtension}"
                }
                ArchiveMode.Extract -> {
                    val src = initialArchive?.nameWithoutExtension
                        ?: "extracted"
                    if (src.endsWith(".tar")) {
                        src.removeSuffix(".tar")
                    } else src
                }
            }
        )
    }
    // Advanced compression options (compress mode only)
    var compressionLevel by remember { mutableStateOf(CompressionEngine.CompressionLevel.NORMAL) }
    var dictionarySize by remember { mutableStateOf(CompressionEngine.DictionarySize.MB_16) }
    var splitSize by remember { mutableStateOf(CompressionEngine.SplitSize.NONE) }
    var encryptFileNames by remember { mutableStateOf(false) }
    var solidArchive by remember { mutableStateOf(false) }

    // When the user changes the format in Compress mode, refresh the
    // suggested extension on the name field.
    LaunchedEffect(format) {
        if (mode == ArchiveMode.Compress) {
            val baseName = archiveName.substringBeforeLast('.', "")
            if (baseName.isNotEmpty()) {
                archiveName = "$baseName${format.canonicalExtension}"
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isWorking) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TitanColors.CarbonGray
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (mode == ArchiveMode.Compress) Icons.Default.Archive
                                  else Icons.Default.Unarchive,
                    contentDescription = null,
                    tint = TitanColors.NeonCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (mode == ArchiveMode.Compress) "COMPRESS TO ARCHIVE"
                               else "EXTRACT ARCHIVE",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = when (mode) {
                            ArchiveMode.Compress ->
                                "${initialFiles.size} item(s) → ${format.displayName}"
                            ArchiveMode.Extract ->
                                "${initialArchive?.name ?: "?"} → ${format.displayName}"
                        },
                        color = TitanColors.NeonCyan.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                IconButton(onClick = { if (!isWorking) onDismiss() }) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White.copy(alpha = 0.6f))
                }
            }

            HorizontalDivider(color = TitanColors.NeonCyan.copy(alpha = 0.2f))

            // Format picker (always visible; defaults match the archive
            // we're about to extract or to ZIP for compress)
            Text(
                "FORMAT",
                color = TitanColors.NeonCyan.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            FormatChips(
                selected = format,
                allowed = if (mode == ArchiveMode.Compress) ArchiveFormat.creatable
                         else ArchiveFormat.extractable,
                enabled = !isWorking && !isDone,
                onSelect = { format = it }
            )

            // Output name (compress only)
            if (mode == ArchiveMode.Compress) {
                Text(
                    "ARCHIVE NAME",
                    color = TitanColors.NeonCyan.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                OutlinedTextField(
                    value = archiveName,
                    onValueChange = { archiveName = it },
                    singleLine = true,
                    enabled = !isWorking && !isDone,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TitanColors.NeonCyan,
                        unfocusedBorderColor = TitanColors.NeonCyan.copy(alpha = 0.3f),
                        cursorColor = TitanColors.NeonCyan
                    )
                )
            }

            // Advanced compression options (compress mode only)
            if (mode == ArchiveMode.Compress) {
                AdvancedOptionsPanel(
                    format = format,
                    compressionLevel = compressionLevel,
                    onCompressionLevelChange = { compressionLevel = it },
                    dictionarySize = dictionarySize,
                    onDictionarySizeChange = { dictionarySize = it },
                    splitSize = splitSize,
                    onSplitSizeChange = { splitSize = it },
                    encryptFileNames = encryptFileNames,
                    onEncryptFileNamesChange = { encryptFileNames = it },
                    solidArchive = solidArchive,
                    onSolidArchiveChange = { solidArchive = it },
                    enabled = !isWorking && !isDone
                )
            }

            // Password
            if (format.supportsPassword) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = usePassword,
                        onCheckedChange = { usePassword = it },
                        enabled = !isWorking && !isDone,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TitanColors.NeonCyan,
                            checkedTrackColor = TitanColors.NeonCyan.copy(alpha = 0.4f)
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (mode == ArchiveMode.Compress) "PROTECT WITH PASSWORD"
                        else "ARCHIVE IS PASSWORD-PROTECTED",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (usePassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        enabled = !isWorking && !isDone,
                        visualTransformation = if (showPassword)
                            VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = TitanColors.NeonCyan
                                )
                            }
                        },
                        placeholder = {
                            Text(
                                "password",
                                color = TitanColors.NeonCyan.copy(alpha = 0.4f),
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TitanColors.NeonCyan,
                            unfocusedBorderColor = TitanColors.NeonCyan.copy(alpha = 0.3f),
                            cursorColor = TitanColors.NeonCyan
                        )
                    )
                    if (format == ArchiveFormat.ZIP) {
                        Text(
                            "ZIP passwords use AES-256 (WinZip AES) — the same " +
                                "strong encryption 7-Zip and WinRAR use. Entry " +
                                "names remain visible in the archive listing.",
                            color = TitanColors.NeonYellow.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else if (format == ArchiveFormat.SEVEN_Z && mode == ArchiveMode.Compress) {
                        Text(
                            "7Z password output is not supported in this version. " +
                                "Use ZIP with password or unencrypted 7Z.",
                            color = TitanColors.NeonRed.copy(alpha = 0.8f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                Text(
                    "${format.displayName} doesn't support password protection.",
                    color = TitanColors.NeonCyan.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Progress / status
            if (progress != null) {
                HorizontalDivider(color = TitanColors.NeonCyan.copy(alpha = 0.2f))
                ArchiveProgressPanel(progress)
            }

            // Action buttons
            if (!isDone && !isError) {
                Button(
                    onClick = {
                        if (mode == ArchiveMode.Compress) {
                            val outputDir = initialFiles.firstOrNull()?.parentFile
                                ?: File("/sdcard")
                            val output = File(outputDir, archiveName)
                            val options = CompressionEngine.CompressionOptions(
                                level = compressionLevel,
                                dictionarySize = dictionarySize,
                                splitSize = splitSize,
                                encryptFileNames = encryptFileNames,
                                solidArchive = solidArchive
                            )
                            onCompress(
                                initialFiles,
                                output,
                                format,
                                if (usePassword && password.isNotEmpty()) password else null,
                                options
                            )
                        } else {
                            val archive = initialArchive ?: return@Button
                            val outName = archiveName.ifEmpty {
                                archive.nameWithoutExtension
                            }
                            val outputDir = File(archive.parentFile ?: File("/sdcard"), outName)
                            onExtract(
                                archive,
                                outputDir,
                                if (usePassword && password.isNotEmpty()) password else null
                            )
                        }
                    },
                    enabled = !isWorking &&
                        archiveName.isNotBlank() &&
                        (usePassword == false || password.isNotEmpty() ||
                            (mode == ArchiveMode.Extract)),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TitanColors.NeonCyan,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        if (mode == ArchiveMode.Compress) Icons.Default.Archive
                        else Icons.Default.Unarchive,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (mode == ArchiveMode.Compress) "CREATE ${format.displayName}"
                        else "EXTRACT ${format.displayName}",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TitanColors.RadioactiveGreen,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("DONE", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FormatChips(
    selected: ArchiveFormat,
    allowed: List<ArchiveFormat>,
    enabled: Boolean,
    onSelect: (ArchiveFormat) -> Unit
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(allowed) { fmt ->
            val isSelected = fmt == selected
            Surface(
                onClick = { if (enabled) onSelect(fmt) },
                color = if (isSelected) TitanColors.NeonCyan.copy(alpha = 0.18f)
                        else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSelected) TitanColors.NeonCyan
                    else TitanColors.NeonCyan.copy(alpha = 0.25f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        fmt.displayName,
                        color = if (isSelected) TitanColors.NeonCyan
                                else Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace
                    )
                    if (fmt.supportsPassword) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Supports password",
                            tint = if (isSelected) TitanColors.NeonCyan
                                   else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedOptionsPanel(
    format: ArchiveFormat,
    compressionLevel: CompressionEngine.CompressionLevel,
    onCompressionLevelChange: (CompressionEngine.CompressionLevel) -> Unit,
    dictionarySize: CompressionEngine.DictionarySize,
    onDictionarySizeChange: (CompressionEngine.DictionarySize) -> Unit,
    splitSize: CompressionEngine.SplitSize,
    onSplitSizeChange: (CompressionEngine.SplitSize) -> Unit,
    encryptFileNames: Boolean,
    onEncryptFileNamesChange: (Boolean) -> Unit,
    solidArchive: Boolean,
    onSolidArchiveChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = TitanColors.NeonCyan.copy(alpha = 0.2f))
        Text(
            "ADVANCED OPTIONS",
            color = TitanColors.NeonCyan.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )

        // Compression Level
        OptionRow(
            label = "COMPRESSION LEVEL",
            description = "Higher = smaller but slower",
            enabled = enabled
        ) {
            DropdownMenuButton(
                options = CompressionEngine.CompressionLevel.entries.toList(),
                selected = compressionLevel,
                onSelect = { onCompressionLevelChange(it as CompressionEngine.CompressionLevel) },
                format = { (it as CompressionEngine.CompressionLevel).displayName },
                enabled = enabled
            )
        }

        // Dictionary Size (for 7Z, LZMA)
        if (format == ArchiveFormat.SEVEN_Z || format.name.startsWith("TAR_")) {
            OptionRow(
                label = "DICTIONARY SIZE",
                description = "Larger = better compression for similar files",
                enabled = enabled
            ) {
                DropdownMenuButton(
                    options = CompressionEngine.DictionarySize.entries.toList(),
                    selected = dictionarySize,
                    onSelect = { onDictionarySizeChange(it as CompressionEngine.DictionarySize) },
                    format = { (it as CompressionEngine.DictionarySize).displayName },
                    enabled = enabled
                )
            }
        }

        // Split Archive
        if (format.multiFile) {
            OptionRow(
                label = "SPLIT SIZE",
                description = "Create multi-part archives",
                enabled = enabled
            ) {
                DropdownMenuButton(
                    options = CompressionEngine.SplitSize.entries.toList(),
                    selected = splitSize,
                    onSelect = { onSplitSizeChange(it as CompressionEngine.SplitSize) },
                    format = { (it as CompressionEngine.SplitSize).displayName },
                    enabled = enabled
                )
            }
        }

        // Encrypt file names (only for formats with AES-256)
        if (format == ArchiveFormat.SEVEN_Z) {
            OptionRow(
                label = "ENCRYPT FILE NAMES",
                description = "Hide file names in encrypted archive",
                enabled = enabled
            ) {
                Switch(
                    checked = encryptFileNames,
                    onCheckedChange = onEncryptFileNamesChange,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TitanColors.NeonCyan,
                        checkedTrackColor = TitanColors.NeonCyan.copy(alpha = 0.4f)
                    )
                )
            }
        }

        // Solid Archive (for 7Z, TAR)
        if (format == ArchiveFormat.SEVEN_Z || format.name.startsWith("TAR")) {
            OptionRow(
                label = "SOLID ARCHIVE",
                description = "Treat all files as one data block (better ratio, slower partial extract)",
                enabled = enabled
            ) {
                Switch(
                    checked = solidArchive,
                    onCheckedChange = onSolidArchiveChange,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TitanColors.NeonCyan,
                        checkedTrackColor = TitanColors.NeonCyan.copy(alpha = 0.4f)
                    )
                )
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    description: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White.copy(alpha = alpha),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = description,
                color = TitanColors.NeonCyan.copy(alpha = 0.5f * alpha),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        content()
    }
}

@Composable
private fun DropdownMenuButton(
    options: List<Any>,
    selected: Any,
    onSelect: (Any) -> Unit,
    format: (Any) -> String,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.fillMaxWidth()
    ) {
        DropdownMenuItem(
            text = { Text(format(selected), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            trailingIcon = { Icon(Icons.Default.ExpandMore, contentDescription = null, tint = TitanColors.NeonCyan) },
            onClick = { expanded = !expanded },
            enabled = enabled
        )
        options.forEach { opt ->
            DropdownMenuItem(
                text = { Text(format(opt), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                onClick = { onSelect(opt); expanded = false },
                enabled = enabled
            )
        }
    }
}

@Composable
private fun ArchiveProgressPanel(progress: ArchiveProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (progress.done && progress.error == null) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = TitanColors.RadioactiveGreen,
                    modifier = Modifier.size(20.dp)
                )
            } else if (progress.error != null) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = TitanColors.NeonRed,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                CircularProgressIndicator(
                    color = TitanColors.NeonCyan,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    progress.error != null -> "ERROR"
                    progress.done -> "COMPLETE"
                    else -> "WORKING…"
                },
                color = when {
                    progress.error != null -> TitanColors.NeonRed
                    progress.done -> TitanColors.RadioactiveGreen
                    else -> TitanColors.NeonCyan
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${progress.percentage.coerceIn(0, 100)}%",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        LinearProgressIndicator(
            progress = (progress.percentage / 100f).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = when {
                progress.error != null -> TitanColors.NeonRed
                progress.done -> TitanColors.RadioactiveGreen
                else -> TitanColors.NeonCyan
            },
            trackColor = Color.White.copy(alpha = 0.1f)
        )
        if (progress.currentFile.isNotEmpty()) {
            Text(
                text = progress.currentFile,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
        // Elapsed / speed / ETA row
        if (!progress.done) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Elapsed: ${formatDuration(progress.elapsedSeconds)}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (progress.speed > 0) {
                    Text(
                        text = "Speed: ${formatBytes(progress.speed)}/s",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "ETA: ${formatDuration(progress.etaSeconds)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        if (progress.error != null) {
            Text(
                text = progress.error,
                color = TitanColors.NeonRed,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B/s"
    bytes < 1024 * 1024 -> "%.1f KB/s".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB/s".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB/s".format(bytes / 1024.0 / 1024 / 1024)
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

enum class ArchiveMode { Compress, Extract }

data class ArchiveProgress(
    val percentage: Int,
    val currentFile: String,
    val done: Boolean,
    val error: String? = null,
    val speed: Long = 0,
    val etaSeconds: Long = 0,
    val totalBytes: Long = 0,
    val processedBytes: Long = 0,
    val elapsedSeconds: Long = 0
)
