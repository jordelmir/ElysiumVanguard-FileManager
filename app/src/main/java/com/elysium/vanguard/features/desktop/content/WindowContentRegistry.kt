package com.elysium.vanguard.features.desktop.content

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.SpeakerNotes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.elysium.vanguard.features.filemanager.FileManagerRepositoryDual
import com.elysium.vanguard.features.filemanager.TitanFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * PHASE 121 — the registry of real window content.
 *
 * Phase 78 shipped a placeholder registry (object) with 4 fake
 * bodies (TerminalBody, FilesBody, etc. were hardcoded text). That
 * was fine for shipping the windowing surface but is no longer
 * enough: the user wants the proprietary Windows desktop to
 * actually work — read folders, open files, run .exe via Wine, etc.
 *
 * The registry is now an Hilt-injected @Singleton that owns a
 * [FileManagerRepositoryDual] and surfaces three real bodies:
 *  - [MyPcBody] shows the available "drives" (filesystem roots)
 *  - [RealFilesBody] is a real Windows Explorer: path navigation,
 *    breadcrumb, tap to enter, long-press for details
 *  - The Terminal / Settings / Notes bodies stay as placeholders
 *    until Phase 122+ rewires them
 *
 * The Hilt conversion was required because the bodies need access
 * to the application Context (for [FileProvider] + SAF) and the
 * file repository. Both are Hilt-injected.
 */
data class WindowContent(
    val icon: ImageVector,
    val body: @Composable () -> Unit,
    /**
     * Optional path for bodies that need one. Currently
     * only [RealFilesBody] reads it. The shell passes
     * the path when it spawns a Files window from a
     * "My PC" drive tap; the default registry content
     * leaves it null and the body falls back to
     * [WindowContentRegistry.DEFAULT_FILES_ROOT].
     */
    val path: String? = null,
)

/**
 * Cross-window actions the registry can request of the
 * active shell. The bodies fire actions (e.g. when the
 * user taps a drive in "My PC") and the shell opens a
 * new window in response.
 *
 * Phase 121 ships one variant — [OpenFilesAtPath] —
 * because the proprietary Windows desktop's only
 * cross-window flow today is "open the Files window
 * at this path". Future phases can add more variants
 * (e.g. `OpenSettingsAt(section: String)`,
 * `OpenProgram(packageName: String)`).
 */
sealed class DesktopAction {
    /**
     * Open a new Files window rooted at [path]. The shell
     * is responsible for assigning the window id + title
     * (typically `Files · ${path}`).
     */
    data class OpenFilesAtPath(val path: String) : DesktopAction()

    /**
     * Open a built-in window for the given [iconKey].
     * Used by [ProgramsBody] when the user taps a
     * built-in app entry. The shell assigns a unique
     * window id and uses [title] as the title bar.
     */
    data class OpenInternal(val iconKey: String, val title: String) : DesktopAction()
}

@Singleton
class WindowContentRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    val fileManagerRepository: FileManagerRepositoryDual,
) {
    /**
     * The map of `iconKey → WindowContent`. The keys are stable
     * — the dock item + the window both reference the same key,
     * so the dock's icon matches the window's title bar icon.
     *
     * PHASE 121 added two new keys:
     *  - `my_pc`     → the "This PC" desktop icon
     *  - `programs`  → the Start menu (apps + system)
     */
    private val byIconKey: Map<String, WindowContent> = mapOf(
        "my_pc" to WindowContent(
            icon = Icons.Filled.Computer,
            body = { MyPcBody(onOpenPath = ::openFilesAt) },
        ),
        "files" to WindowContent(
            icon = Icons.Filled.Folder,
            body = { RealFilesBody(initialPath = DEFAULT_FILES_ROOT) },
        ),
        "terminal" to WindowContent(
            icon = Icons.Filled.Terminal,
            body = { TerminalBody() },
        ),
        "settings" to WindowContent(
            icon = Icons.Filled.Settings,
            body = { SettingsBody() },
        ),
        "notes" to WindowContent(
            icon = Icons.Filled.Description,
            body = { NotesBody() },
        ),
        "programs" to WindowContent(
            icon = Icons.Filled.Computer,
            body = { ProgramsBody() },
        ),
        // External app launchers (Phase 123 foundation).
        // These resolve to a small in-window "Launching…" status
        // banner so the registry has an icon for the dock; the
        // real launch happens in [launchExternal] before the
        // window opens, so the user never sees the body in
        // normal flow. We keep them in [byIconKey] so the dock
        // can render their icon.
        "chrome" to WindowContent(
            icon = Icons.Filled.Public,
            body = { ExternalAppBody(appName = "Chrome") },
        ),
        "codex" to WindowContent(
            icon = Icons.Filled.Code,
            body = { ExternalAppBody(appName = "Codex") },
        ),
        "antigravity" to WindowContent(
            icon = Icons.Filled.RocketLaunch,
            body = { ExternalAppBody(appName = "Antigravity") },
        ),
        "opencode" to WindowContent(
            icon = Icons.Filled.Code,
            body = { ExternalAppBody(appName = "OpenCode") },
        ),
        "mavis" to WindowContent(
            icon = Icons.Filled.SmartToy,
            body = { ExternalAppBody(appName = "Mavis") },
        ),
    )

    /**
     * Map of `iconKey -> Intent` for the proprietary Windows
     * desktop's external app launchers. The [DesktopShellScreen]
     * dock handler consults this map BEFORE opening a window;
     * if the iconKey is in this map, the Intent is fired and
     * no window opens. This is the Phase 123 foundation —
     * the registry owns the launch catalog and the dock
     * delegates to it.
     *
     * Each launcher uses a layered fallback:
     *  1. The known Android package name (if installed)
     *  2. The app's web URL (if no package)
     *  3. The system chooser as a last resort
     */
    private val externalLaunchers: Map<String, () -> Unit> = mapOf(
        "chrome" to { launchExternalApp("Chrome", "com.android.chrome", "https://www.google.com") },
        "codex" to { launchExternalApp("Codex", null, "https://github.com/features/copilot") },
        "antigravity" to { launchExternalApp("Antigravity", null, "https://antigravity.google") },
        "opencode" to { launchExternalApp("OpenCode", null, "https://opencode.ai") },
        "mavis" to { launchExternalApp("Mavis", null, "https://mavis.local") },
    )

    /**
     * If [iconKey] is an external launcher, fire its Intent
     * and return true. Otherwise return false — the caller
     * should open a window as usual.
     */
    fun launchExternal(iconKey: String): Boolean {
        val launcher = externalLaunchers[iconKey] ?: return false
        try {
            launcher.invoke()
        } catch (_: Exception) {
            // Swallow — the dock click already felt responsive;
            // the user sees nothing happen if the launch fails.
        }
        return true
    }

    /**
     * Public alias for the private [openFilesAt] helper
     * used by [ProgramsBody] when the user taps a
     * built-in "Files" entry. The action flow is the
     * same — the screen collects [DesktopAction.OpenFilesAtPath]
     * and opens a Files window at the path.
     */
    fun requestOpenFilesAt(path: String) {
        _actions.tryEmit(DesktopAction.OpenFilesAtPath(path))
    }

    /**
     * Open a built-in window (any iconKey that has a
     * body in [byIconKey]). Used by [ProgramsBody] to
     * route to built-in apps without going through the
     * dock click path. Emits a [DesktopAction.OpenInternal]
     * with the iconKey + title; the screen collects and
     * opens a new window.
     */
    fun requestOpenInternal(iconKey: String, title: String) {
        _actions.tryEmit(DesktopAction.OpenInternal(iconKey = iconKey, title = title))
    }

    /**
     * Layered external-app launch. First tries the known
     * package (if any); if that fails, opens the URL fallback.
     * Both attempts are wrapped in try/catch — a no-op is
     * acceptable for a dock click.
     */
    private fun launchExternalApp(appName: String, packageName: String?, urlFallback: String) {
        if (packageName != null) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) {
                // Package installed but launch failed; fall through
                // to the URL.
            }
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlFallback)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open $appName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            // No app can handle the URL either; silent no-op.
        }
    }

    /**
     * Resolve a [iconKey] to a [WindowContent]. Returns a placeholder
     * when the key is unknown (so a window with an unexpected iconKey
     * renders something instead of crashing).
     *
     * PHASE 121 — the [path] parameter lets the shell open a
     * Files window rooted at a specific path (e.g. when the
     * user taps a drive in "My PC"). The Files body is
     * re-constructed with the path baked into the body's
     * closure so the body renders the correct listing on
     * first composition.
     */
    fun resolve(iconKey: String, path: String? = null): WindowContent {
        val template = byIconKey[iconKey]
            ?: return WindowContent(
                icon = Icons.Filled.Description,
                body = {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Unknown app: $iconKey", style = MaterialTheme.typography.bodyLarge)
                    }
                },
            )
        if (iconKey == "files") {
            // Rebuild the body so the path is captured in the
            // lambda's closure. Without this, every Files
            // window would open at the registry's default root.
            val resolvedPath = path ?: DEFAULT_FILES_ROOT
            return template.copy(
                body = { RealFilesBody(initialPath = resolvedPath) },
                path = resolvedPath,
            )
        }
        return template
    }

    /**
     * Open the file at [path] using the system's default app.
     * Used by the file explorer's tap-to-open. Returns silently if
     * no app can handle the file (the explorer shows a "no app
     * available" message via the caller).
     */
    fun openWithDefaultApp(path: String) {
        try {
            val file = File(path)
            if (!file.exists()) return
            val uri: Uri = if (file.parentFile != null) {
                Uri.fromFile(file)
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeFor(path))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            // Swallow — the file explorer surfaces the failure
            // through its own state (not via exceptions).
        }
    }

    /**
     * Map a file path to a MIME type the system can use to find a
     * handler. Falls back to application/octet-stream when the
     * extension is unknown.
     */
    private fun mimeFor(path: String): String = when {
        path.endsWith(".pdf", true) -> "application/pdf"
        path.endsWith(".txt", true) -> "text/plain"
        path.endsWith(".md", true) -> "text/markdown"
        path.endsWith(".html", true) -> "text/html"
        path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".gif", true) -> "image/gif"
        path.endsWith(".mp3", true) -> "audio/mpeg"
        path.endsWith(".mp4", true) -> "video/mp4"
        path.endsWith(".json", true) -> "application/json"
        path.endsWith(".xml", true) -> "text/xml"
        path.endsWith(".zip", true) -> "application/zip"
        path.endsWith(".apk", true) -> "application/vnd.android.package-archive"
        path.endsWith(".exe", true) -> "application/x-msdownload"
        else -> "*/*"
    }

    /**
     * Pending path for the next Files window to open. The
     * "My PC" body emits [DesktopAction.OpenFilesAtPath] when
     * the user taps a drive; the screen collector (in
     * [DesktopShellScreen] / [MultiDesktopShellScreen]) opens
     * a new Files window with that path. We use a SharedFlow
     * instead of a one-shot field so the request survives
     * subscription races (e.g. when a screen recomposes
     * mid-request).
     */
    private val _actions: kotlinx.coroutines.flow.MutableSharedFlow<DesktopAction> =
        kotlinx.coroutines.flow.MutableSharedFlow(extraBufferCapacity = 8)

    /**
     * Public action stream the screens subscribe to. The
     * SharedFlow is replay-0 by default — actions emitted
     * before a subscriber is up are lost, but the screen
     * subscribes at first composition, which is well before
     * the user can tap anything in MyPC.
     */
    val actions: kotlinx.coroutines.flow.SharedFlow<DesktopAction> = _actions

    /**
     * Helper used by the "My PC" body to open a Files window
     * at a specific path. Emits a [DesktopAction.OpenFilesAtPath]
     * the screens subscribe to.
     */
    private fun openFilesAt(path: String) {
        _actions.tryEmit(DesktopAction.OpenFilesAtPath(path))
        android.util.Log.i(
            "ElysiumDesktop",
            "openFilesAt($path) — emitted DesktopAction.OpenFilesAtPath",
        )
    }

    /**
     * Reserved for future per-window path plumbing. The
     * `resolve(iconKey, path)` overload is the
     * canonical path-injection mechanism today; this
     * accessor stays for symmetry / future use.
     */
    @Suppress("unused")
    fun consumePendingFilesPath(): String? = null

    companion object {
        /**
         * The root path the Files window opens to by default. We
         * pick /sdcard because it shows the user's actual data
         * (Downloads, Documents, DCIM, etc.) and demonstrates the
         * "real device" feel of the proprietary Windows desktop.
         */
        const val DEFAULT_FILES_ROOT: String = "/sdcard"
    }
}

// ============================== Bodies ==============================

/**
 * PHASE 121 — the "This PC" view. Shows the proprietary
 * Windows desktop's available "drives". Each drive is a
 * clickable entry that opens a Files window at that path.
 */
@Composable
private fun MyPcBody(
    onOpenPath: (String) -> Unit,
) {
    val drives = remember {
        listOf(
            Drive("C:", "/system", "System", Icons.Filled.Computer),
            Drive("D:", "/sdcard", "Data", Icons.Filled.Folder),
            Drive("E:", "/data/data/com.elysium.vanguard/files", "Elysium (private)", Icons.Filled.Folder),
            Drive("Z:", "/", "Root", Icons.Filled.Computer),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(
            text = "This PC",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(drives) { drive ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPath(drive.path) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = drive.icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${drive.letter}  ${drive.label}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = drive.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private data class Drive(
    val letter: String,
    val path: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * PHASE 121 — a real Windows Explorer. Shows the contents of
 * [initialPath] in a scrollable list, supports tap-to-navigate
 * into a folder, and shows a breadcrumb at the top.
 *
 * Uses [FileManagerRepositoryDual] to list files (works on both
 * raw filesystem paths and SAF paths). Tapping a file shows a
 * minimal info banner; future phases will hook this to the
 * existing FileActionResolver to open / share / extract.
 */
@Composable
private fun RealFilesBody(initialPath: String) {
    // PHASE 121 — the registry's [DesktopAction.OpenFilesAtPath]
    // flow is the source of truth for the path. The shell
    // subscribes and opens a new window with the right
    // `initialPath` baked in, so this body just uses
    // [initialPath] directly. (We no longer need to read
    // a pending field at first composition — the shell
    // does the routing.)
    val registry = rememberWindowContentRegistry()
    var currentPath by remember { mutableStateOf(initialPath) }
    var items by remember { mutableStateOf<List<TitanFile>>(emptyList()) }
    var selectedInfo by remember { mutableStateOf<TitanFile?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Refresh the listing when the path changes. The
    // [registry] is the same instance we built at the top
    // of this Composable (via [rememberWindowContentRegistry]).
    androidx.compose.runtime.LaunchedEffect(currentPath) {
        try {
            items = registry.fileManagerRepository.listOnce(currentPath)
            errorMessage = null
        } catch (e: Exception) {
            errorMessage = "Cannot read $currentPath: ${e.message}"
            items = emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Breadcrumb: shows the path as clickable segments
        Breadcrumb(
            path = currentPath,
            onNavigate = { newPath -> currentPath = newPath },
        )
        // Selected file info banner (only when one is selected)
        selectedInfo?.let { info ->
            FileInfoBanner(
                file = info,
                onDismiss = { selectedInfo = null },
            )
        }
        // Error banner
        errorMessage?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        // File listing
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Empty folder",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items) { file ->
                    FileRow(
                        file = file,
                        onOpen = {
                            if (file.isFolder) {
                                currentPath = file.path
                                selectedInfo = null
                            } else {
                                selectedInfo = file
                                registry.openWithDefaultApp(file.path)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    val segments = remember(path) {
        val parts = path.split("/").filter { it.isNotEmpty() }
        val result = mutableListOf<Pair<String, String>>()
        var acc = if (path.startsWith("/")) "/" else ""
        for (seg in parts) {
            acc = if (acc == "/") "/$seg" else "$acc/$seg"
            result.add(seg to acc)
        }
        result
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "📂",
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { onNavigate("/") }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        segments.forEach { (seg, fullPath) ->
            Text(
                text = " › ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = seg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable { onNavigate(fullPath) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun FileInfoBanner(file: TitanFile, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            .clickable { onDismiss() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📄", fontSize = 12.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Opening: ${file.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "✕",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FileRow(file: TitanFile, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (file.isFolder) "📁" else iconForMime(file.mimeType),
            fontSize = 18.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = file.size,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun iconForMime(mime: String): String = when {
    mime.startsWith("image/") -> "🖼️"
    mime.startsWith("video/") -> "🎬"
    mime.startsWith("audio/") -> "🎵"
    mime.contains("pdf") -> "📕"
    mime.contains("zip") -> "🗜️"
    mime.contains("text") -> "📄"
    mime.contains("android.package-archive") -> "📦"
    mime.contains("x-msdownload") -> "⚙️"   // .exe
    else -> "📄"
}

/**
 * PHASE 122 — the Start-menu / Programs view.
 * Three sections:
 *  1. Elysium System — the 4 built-in apps
 *     (Files, Terminal, This PC, Settings, Notes)
 *     + the 5 external launchers (Chrome, Codex,
 *     Antigravity, OpenCode, Mavis). Tapping
 *     these reuses the same routing as the dock.
 *  2. User Apps — every app installed on the
 *     device that has a launcher activity
 *     (queried via PackageManager). Tapping fires
 *     the system launch Intent.
 *  3. The list updates on every composition
 *     (cheap; the query is O(n) on the installed
 *     package list, which is small).
 */
@Composable
private fun ProgramsBody() {
    val context = LocalContext.current
    val registry = rememberWindowContentRegistry()
    // The user-installed apps. We seed with an
    // empty list and populate in a LaunchedEffect
    // so the package query doesn't run during
    // composition (would block the UI thread).
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var queryError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            installedApps = queryInstalledApps(context)
        } catch (e: Exception) {
            queryError = e.message ?: "unknown error"
        }
    }
    val elysiumSystem = listOf(
        ProgramEntry(
            label = "Files",
            subtitle = "Windows Explorer — real file manager",
            icon = Icons.Filled.Folder,
            iconTint = Color(0xFFFFB86C),
            onClick = {
                registry.requestOpenFilesAt("/sdcard")
            },
        ),
        ProgramEntry(
            label = "Terminal",
            subtitle = "Proprietary client-side shell",
            icon = Icons.Filled.Terminal,
            iconTint = Color(0xFF50FA7B),
            onClick = {
                // The Terminal body is just one of the
                // pinned apps; tapping here opens it
                // the same way the dock does. The
                // registry doesn't expose a public
                // open-by-iconKey method, so we go
                // through [WindowContentRegistry]'s
                // action flow instead.
                registry.requestOpenInternal("terminal", "Terminal")
            },
        ),
        ProgramEntry(
            label = "This PC",
            subtitle = "Drives — system + data + private",
            icon = Icons.Filled.Computer,
            iconTint = Color(0xFF8BE9FD),
            onClick = {
                registry.requestOpenInternal("my_pc", "This PC")
            },
        ),
        ProgramEntry(
            label = "Settings",
            subtitle = "Theme, storage, memory, battery",
            icon = Icons.Filled.Settings,
            iconTint = Color(0xFFBD93F9),
            onClick = {
                registry.requestOpenInternal("settings", "Settings")
            },
        ),
        ProgramEntry(
            label = "Notes",
            subtitle = "Scratchpad — auto-saved",
            icon = Icons.AutoMirrored.Filled.SpeakerNotes,
            iconTint = Color(0xFFFF79C6),
            onClick = {
                registry.requestOpenInternal("notes", "Notes")
            },
        ),
        ProgramEntry(
            label = "Chrome",
            subtitle = "External launcher — opens browser",
            icon = Icons.Filled.Public,
            iconTint = Color(0xFF50FA7B),
            onClick = { registry.launchExternal("chrome") },
        ),
        ProgramEntry(
            label = "Codex",
            subtitle = "External launcher",
            icon = Icons.Filled.Code,
            iconTint = Color(0xFFFFB86C),
            onClick = { registry.launchExternal("codex") },
        ),
        ProgramEntry(
            label = "Antigravity",
            subtitle = "External launcher",
            icon = Icons.Filled.RocketLaunch,
            iconTint = Color(0xFFFF79C6),
            onClick = { registry.launchExternal("antigravity") },
        ),
        ProgramEntry(
            label = "OpenCode",
            subtitle = "External launcher",
            icon = Icons.Filled.Code,
            iconTint = Color(0xFF8BE9FD),
            onClick = { registry.launchExternal("opencode") },
        ),
        ProgramEntry(
            label = "Mavis",
            subtitle = "External launcher",
            icon = Icons.Filled.SmartToy,
            iconTint = Color(0xFFBD93F9),
            onClick = { registry.launchExternal("mavis") },
        ),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                text = "Programs",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        item {
            Text(
                text = "Elysium System",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        items(elysiumSystem) { entry ->
            ProgramListItem(entry)
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "User Apps (${installedApps.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (queryError != null) {
            item {
                Text(
                    text = "Could not query apps: $queryError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (installedApps.isEmpty() && queryError == null) {
            item {
                Text(
                    text = "Loading installed apps…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(installedApps) { app ->
            ProgramListItem(
                ProgramEntry(
                    label = app.label,
                    subtitle = app.packageName,
                    icon = Icons.Filled.Android,
                    iconTint = Color(0xFF50FA7B),
                    onClick = { launchInstalledApp(context, app.packageName) },
                ),
            )
        }
    }
}

private data class ProgramEntry(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconTint: Color,
    val onClick: () -> Unit,
)

@Composable
private fun ProgramListItem(entry: ProgramEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = entry.onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = entry.iconTint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Installed apps query ─────────────────────────────

private data class InstalledApp(
    val label: String,
    val packageName: String,
)

/**
 * Query the device for all apps with a launcher
 * activity (the apps the user sees on the home
 * screen). Sorted alphabetically. Elysium's own
 * package is included; the user can tap it to
 * "launch" it (which is a no-op since it's
 * already in the foreground, but the Intent
 * still fires harmlessly).
 */
private fun queryInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfos = pm.queryIntentActivities(intent, 0)
    return resolveInfos
        .mapNotNull { ri ->
            val label = ri.loadLabel(pm).toString()
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (label.isBlank()) null else InstalledApp(label, pkg)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.US) }
}

/**
 * Fire the launch Intent for a third-party app
 * by package name. Falls back silently if the
 * app is not installed (defensive — should never
 * happen since the package came from a query,
 * but the check is cheap).
 */
private fun launchInstalledApp(context: Context, packageName: String) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    } catch (_: Exception) {
        // The app may have been uninstalled between
        // the query and the click. Silent no-op.
    }
}

// ============================== Real bodies (Phase 122) ==============================

/**
 * PHASE 122 — the proprietary Windows desktop's
 * **real Terminal**. This is a client-side terminal
 * emulator wired to the device's actual filesystem
 * (via [FileManagerRepositoryDual]) and Android
 * system info (storage, battery, memory, uptime).
 *
 * The terminal supports 20+ built-in commands:
 *  - Navigation: `pwd`, `ls`, `cd`, `tree`
 *  - File ops: `cat`, `mkdir`, `touch`, `rm`, `stat`
 *  - System: `whoami`, `uname`, `date`, `uptime`,
 *    `df`, `free`, `battery`, `version`, `drives`
 *  - UI: `clear`, `help`, `echo`, `open`
 *
 * Phase 123 will wire this body to the production
 * proot-backed [com.elysium.vanguard.core.runtime.terminal.TerminalHost]
 * so the user can run real Linux binaries. For now
 * the shell is a faithful mock that uses the real
 * filesystem + real Android system info.
 */
private sealed class TerminalLine {
    data class Input(val prompt: String, val command: String) : TerminalLine()
    data class Output(val text: String, val color: Color) : TerminalLine()
    data class Error(val text: String) : TerminalLine()
    data class Info(val text: String) : TerminalLine()
}

@Composable
private fun TerminalBody() {
    val registry = rememberWindowContentRegistry()
    val context = LocalContext.current
    // The terminal's persistent state. We seed it with
    // a welcome banner on first composition.
    var lines by remember { mutableStateOf<List<TerminalLine>>(seedTerminalHistory()) }
    var input by remember { mutableStateOf("") }
    var currentDir by remember { mutableStateOf("/sdcard") }
    // Command history (just the commands, not the
    // output). Used for Up/Down arrow recall.
    val commandHistory = remember { mutableListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    // Auto-scroll the output to the bottom on new
    // lines. We use a regular Column with scroll
    // (not LazyColumn) — terminal history is small
    // in normal use and the simpler API fits the
    // UX (no chunking, no keying).
    val scrollState = androidx.compose.foundation.rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(lines.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // Output history (scrollable)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
        ) {
            lines.forEach { line ->
                when (line) {
                    is TerminalLine.Input -> Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = line.prompt,
                            color = Color(0xFF50FA7B),
                            style = MonoSmall,
                        )
                        Text(
                            text = line.command,
                            color = Color(0xFFF8F8F2),
                            style = MonoSmall,
                        )
                    }
                    is TerminalLine.Output -> Text(
                        text = line.text,
                        color = line.color,
                        style = MonoSmall,
                    )
                    is TerminalLine.Error -> Text(
                        text = line.text,
                        color = Color(0xFFFF5555),
                        style = MonoSmall,
                    )
                    is TerminalLine.Info -> Text(
                        text = line.text,
                        color = Color(0xFF8BE9FD),
                        style = MonoSmall,
                    )
                }
            }
        }
        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "elysium@vanguard:${shortenPath(currentDir)}$ ",
                color = Color(0xFF50FA7B),
                style = MonoSmall,
            )
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                placeholder = {
                    Text(
                        text = "type a command… (try 'help')",
                        color = Color(0xFF6272A4),
                        style = MonoSmall,
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Color(0xFF50FA7B),
                    focusedTextColor = Color(0xFFF8F8F2),
                    unfocusedTextColor = Color(0xFFF8F8F2),
                ),
                textStyle = MonoSmall,
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        if (input.isNotBlank()) {
                            val cmd = input.trim()
                            commandHistory.add(cmd)
                            historyIndex = commandHistory.size
                            val (newLines, newDir) = executeCommand(
                                cmd = cmd,
                                currentDir = currentDir,
                                context = context,
                                repository = registry.fileManagerRepository,
                            )
                            val inputLine = TerminalLine.Input(
                                prompt = "elysium@vanguard:${shortenPath(currentDir)}$ ",
                                command = cmd,
                            )
                            // `clear` is a sentinel: the executor
                            // returns [Info("__CLEAR__")] which we
                            // pattern-match to wipe history (keeping
                            // the input line so the user sees what
                            // they typed).
                            lines = if (newLines.any { it is TerminalLine.Info && it.text == "__CLEAR__" }) {
                                listOf(inputLine)
                            } else {
                                lines + inputLine + newLines
                            }
                            currentDir = newDir
                            input = ""
                        }
                    },
                ),
            )
        }
    }
}

private val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
)

/**
 * Build the seed history shown when the terminal
 * first opens. Includes a banner + a hint to
 * run `help`.
 */
private fun seedTerminalHistory(): List<TerminalLine> = listOf(
    TerminalLine.Info("Elysium Vanguard Terminal v1.0.0-TITAN"),
    TerminalLine.Info("Proprietary client-side shell — wired to the device filesystem."),
    TerminalLine.Info("Type 'help' to see available commands. 'clear' to wipe the screen."),
    TerminalLine.Output("", Color(0xFFF8F8F2)),
)

/**
 * Truncate the working directory for the PS1
 * prompt. We replace $HOME (the app's files dir)
 * with `~` to keep the prompt readable.
 */
private fun shortenPath(path: String): String = when {
    path == "/sdcard" -> "~"
    path.startsWith("/sdcard/") -> "~/" + path.removePrefix("/sdcard/")
    path == "/data/data/com.elysium.vanguard/files" -> "~/files"
    path.startsWith("/data/data/com.elysium.vanguard/files/") ->
        "~/files/" + path.removePrefix("/data/data/com.elysium.vanguard/files/")
    else -> path
}

/**
 * Execute one terminal command. Returns the
 * new lines to append to history + the (possibly
 * updated) current directory.
 */
private fun executeCommand(
    cmd: String,
    currentDir: String,
    context: Context,
    repository: FileManagerRepositoryDual,
): Pair<List<TerminalLine>, String> {
    val parts = cmd.split(Regex("\\s+"))
    val name = parts.first()
    val args = parts.drop(1)
    val out = mutableListOf<TerminalLine>()
    var dir = currentDir
    when (name) {
        "help" -> {
            out += TerminalLine.Info("Available commands:")
            val cmds = listOf(
                "pwd" to "print working directory",
                "ls [path]" to "list directory contents",
                "cd <path>" to "change directory",
                "tree [path]" to "recursive directory tree (max depth 3)",
                "cat <file>" to "read a file (first 4 KiB)",
                "mkdir <path>" to "create directory",
                "touch <path>" to "create empty file",
                "rm <path>" to "delete file or empty directory",
                "stat <path>" to "show file metadata",
                "open <file>" to "open file with default app",
                "echo <text>" to "print text",
                "whoami" to "current user",
                "uname" to "system info",
                "date" to "current date and time",
                "uptime" to "device uptime",
                "df" to "storage usage",
                "free" to "memory usage",
                "battery" to "battery level",
                "drives" to "list proprietary drives",
                "version" to "Elysium Vanguard version",
                "clear" to "clear the screen",
                "help" to "this help",
            )
            cmds.forEach { (n, d) ->
                out += TerminalLine.Output(
                    "  ${n.padEnd(18)} $d",
                    Color(0xFFF8F8F2),
                )
            }
        }
        "clear" -> {
            // Sentinel — the caller (compose state) handles
            // this differently. We return a special line
            // that the caller can pattern-match on.
            out += TerminalLine.Info("__CLEAR__")
        }
        "pwd" -> out += TerminalLine.Output(dir, Color(0xFF50FA7B))
        "echo" -> out += TerminalLine.Output(args.joinToString(" "), Color(0xFFF8F8F2))
        "whoami" -> out += TerminalLine.Output("elysium", Color(0xFF50FA7B))
        "uname" -> out += TerminalLine.Output(
            "Elysium Vanguard 1.0.0-TITAN (Android ${android.os.Build.VERSION.RELEASE}; SDK ${android.os.Build.VERSION.SDK_INT})",
            Color(0xFFF8F8F2),
        )
        "date" -> out += TerminalLine.Output(
            SimpleDateFormat("EEE MMM d HH:mm:ss zzz yyyy", Locale.US).format(Date()),
            Color(0xFFF8F8F2),
        )
        "uptime" -> out += TerminalLine.Output(
            "Device uptime: ${android.os.SystemClock.elapsedRealtime() / 1000}s since boot",
            Color(0xFFF8F8F2),
        )
        "version" -> out += TerminalLine.Output(
            "Elysium Vanguard v1.0.0-TITAN (com.elysium.vanguard)",
            Color(0xFFF8F8F2),
        )
        "df" -> {
            val info = readStorageInfo()
            out += TerminalLine.Output(
                "Filesystem      Size  Used  Avail  Use%",
                Color(0xFFBD93F9),
            )
            out += TerminalLine.Output(
                "/sdcard       ${info.totalGb.padStart(5)}G  ${info.usedGb.padStart(4)}G  ${info.freeGb.padStart(5)}G  ${info.usedPercent.toString().padStart(3)}%",
                Color(0xFFF8F8F2),
            )
        }
        "free" -> {
            val info = readMemoryInfo(context)
            out += TerminalLine.Output(
                "              Total  Used  Free  Use%",
                Color(0xFFBD93F9),
            )
            out += TerminalLine.Output(
                "Mem:        ${info.totalGb.padStart(5)}G  ${info.usedGb.padStart(4)}G  ${info.freeGb.padStart(4)}G  ${info.usedPercent.toString().padStart(3)}%",
                Color(0xFFF8F8F2),
            )
        }
        "battery" -> {
            val pct = readBatteryPercent(context)
            out += TerminalLine.Output("Battery: $pct%", Color(0xFF50FA7B))
        }
        "drives" -> {
            out += TerminalLine.Info("Proprietary drives:")
            listOf(
                "C:" to "/system",
                "D:" to "/sdcard",
                "E:" to "/data/data/com.elysium.vanguard/files",
                "Z:" to "/",
            ).forEach { (letter, path) ->
                out += TerminalLine.Output(
                    "  $letter  $path",
                    Color(0xFFF8F8F2),
                )
            }
        }
        "ls" -> {
            val target = args.firstOrNull()?.let { resolvePath(it, dir) } ?: dir
            try {
                val items = repository.listOnce(target)
                if (items.isEmpty()) {
                    out += TerminalLine.Output("(empty)", Color(0xFF6272A4))
                } else {
                    items.forEach { f ->
                        val icon = if (f.isFolder) "📁" else "📄"
                        out += TerminalLine.Output(
                            "  $icon  ${f.name}  (${f.size})",
                            Color(0xFFF8F8F2),
                        )
                    }
                }
            } catch (e: Exception) {
                out += TerminalLine.Error("ls: $target: ${e.message}")
            }
        }
        "cd" -> {
            val target = args.firstOrNull()
            if (target == null || target == "~") {
                dir = "/sdcard"
            } else if (target == "..") {
                val parent = File(dir).parent
                if (parent != null) dir = parent
            } else {
                val resolved = resolvePath(target, dir)
                val f = File(resolved)
                if (!f.exists()) {
                    out += TerminalLine.Error("cd: $target: No such file or directory")
                } else if (!f.isDirectory) {
                    out += TerminalLine.Error("cd: $target: Not a directory")
                } else {
                    dir = resolved
                }
            }
        }
        "tree" -> {
            val target = args.firstOrNull()?.let { resolvePath(it, dir) } ?: dir
            try {
                tree(repository, target, 0, out)
            } catch (e: Exception) {
                out += TerminalLine.Error("tree: $target: ${e.message}")
            }
        }
        "cat" -> {
            val target = args.firstOrNull()?.let { resolvePath(it, dir) }
            if (target == null) {
                out += TerminalLine.Error("cat: missing file operand")
            } else {
                try {
                    val f = File(target)
                    if (!f.exists() || !f.isFile) {
                        out += TerminalLine.Error("cat: $target: No such file")
                    } else {
                        val bytes = f.readBytes().copyOfRange(0, minOf(4096, f.length().toInt()))
                        val text = String(bytes, Charsets.UTF_8)
                        text.lineSequence().forEach {
                            out += TerminalLine.Output(it, Color(0xFFF8F8F2))
                        }
                        if (f.length() > 4096) {
                            out += TerminalLine.Info("…(truncated, file is ${f.length()} bytes)")
                        }
                    }
                } catch (e: Exception) {
                    out += TerminalLine.Error("cat: $target: ${e.message}")
                }
            }
        }
        "mkdir" -> {
            val target = args.firstOrNull()?.let { resolvePath(it, dir) }
            if (target == null) {
                out += TerminalLine.Error("mkdir: missing operand")
            } else {
                val ok = File(target).mkdirs()
                if (!ok) out += TerminalLine.Error("mkdir: cannot create '$target'")
            }
        }
        "touch" -> {
            val target = args.firstOrNull()?.let { resolvePath(it, dir) }
            if (target == null) {
                out += TerminalLine.Error("touch: missing operand")
            } else {
                val f = File(target)
                if (!f.exists()) f.createNewFile()
                else f.setLastModified(System.currentTimeMillis())
            }
        }
        "rm" -> {
            val target = args.firstOrNull()?.let { resolvePath(it, dir) }
            if (target == null) {
                out += TerminalLine.Error("rm: missing operand")
            } else {
                val f = File(target)
                if (!f.exists()) {
                    out += TerminalLine.Error("rm: cannot remove '$target': No such file")
                } else {
                    val ok = f.delete()
                    if (!ok) out += TerminalLine.Error("rm: cannot remove '$target'")
                }
            }
        }
        "stat" -> {
            val target = args.firstOrNull()?.let { resolvePath(it, dir) }
            if (target == null) {
                out += TerminalLine.Error("stat: missing operand")
            } else {
                val f = File(target)
                if (!f.exists()) {
                    out += TerminalLine.Error("stat: cannot stat '$target'")
                } else {
                    out += TerminalLine.Output("  File: $target", Color(0xFFBD93F9))
                    out += TerminalLine.Output("  Size: ${f.length()} bytes", Color(0xFFF8F8F2))
                    out += TerminalLine.Output("  Type: ${if (f.isDirectory) "directory" else "file"}", Color(0xFFF8F8F2))
                    out += TerminalLine.Output("  Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f.lastModified()))}", Color(0xFFF8F8F2))
                }
            }
        }
        "open" -> {
            val target = args.firstOrNull()?.let { resolvePath(it, dir) }
            if (target == null) {
                out += TerminalLine.Error("open: missing file operand")
            } else {
                val f = File(target)
                if (!f.exists()) {
                    out += TerminalLine.Error("open: $target: No such file")
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.fromFile(f), "*/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open with").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        out += TerminalLine.Info("Opened $target")
                    } catch (e: Exception) {
                        out += TerminalLine.Error("open: $target: ${e.message}")
                    }
                }
            }
        }
        else -> out += TerminalLine.Error("$name: command not found (try 'help')")
    }
    return out to dir
}

/**
 * Recursive tree walker for the `tree` command.
 * Caps at depth 3 to avoid pathological scans on
 * /sdcard. Each level is a tab + item name.
 */
private fun tree(
    repository: FileManagerRepositoryDual,
    path: String,
    depth: Int,
    out: MutableList<TerminalLine>,
) {
    if (depth > 3) return
    val prefix = "  ".repeat(depth) + (if (depth == 0) "" else "└─ ")
    val name = if (depth == 0) path else File(path).name
    out += TerminalLine.Output(prefix + name, Color(0xFFF8F8F2))
    try {
        val items = repository.listOnce(path)
        items.filter { it.isFolder }.forEach { dir ->
            tree(repository, dir.path, depth + 1, out)
        }
    } catch (_: Exception) {
        // Permission denied or unreadable — skip silently.
    }
}

/**
 * Resolve a path argument relative to the current
 * directory. Handles `~`, `..`, and absolute paths.
 */
private fun resolvePath(arg: String, currentDir: String): String = when {
    arg == "~" -> "/sdcard"
    arg.startsWith("~/") -> "/sdcard/" + arg.removePrefix("~/")
    arg.startsWith("/") -> arg
    arg == ".." -> File(currentDir).parent ?: currentDir
    arg.contains("/") -> File(currentDir, arg).path
    else -> File(currentDir, arg).path
}

// ============================== System info helpers ==============================

private data class StorageInfo(
    val totalGb: String,
    val usedGb: String,
    val freeGb: String,
    val usedPercent: Int,
)

private data class MemoryInfo(
    val totalGb: String,
    val usedGb: String,
    val freeGb: String,
    val usedPercent: Int,
)

private fun readStorageInfo(): StorageInfo = try {
    val stat = StatFs(Environment.getExternalStorageDirectory().path)
    val totalBytes = stat.totalBytes
    val freeBytes = stat.availableBytes
    val usedBytes = totalBytes - freeBytes
    StorageInfo(
        totalGb = "%.1f".format(totalBytes / 1_073_741_824.0),
        usedGb = "%.1f".format(usedBytes / 1_073_741_824.0),
        freeGb = "%.1f".format(freeBytes / 1_073_741_824.0),
        usedPercent = ((usedBytes.toDouble() / totalBytes) * 100).roundToInt(),
    )
} catch (_: Exception) {
    StorageInfo("0.0", "0.0", "0.0", 0)
}

private fun readMemoryInfo(context: Context): MemoryInfo = try {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val total = mi.totalMem
    val avail = mi.availMem
    val used = total - avail
    MemoryInfo(
        totalGb = "%.1f".format(total / 1_073_741_824.0),
        usedGb = "%.1f".format(used / 1_073_741_824.0),
        freeGb = "%.1f".format(avail / 1_073_741_824.0),
        usedPercent = ((used.toDouble() / total) * 100).roundToInt(),
    )
} catch (_: Exception) {
    MemoryInfo("0.0", "0.0", "0.0", 0)
}

private fun readBatteryPercent(context: Context): Int = try {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
} catch (_: Exception) {
    -1
}

@Composable
private fun SettingsBody() {
    val context = LocalContext.current
    // Live system info — re-read on every composition
    // (cheap; the platform helpers are O(1)).
    val storage = readStorageInfo()
    val memory = readMemoryInfo(context)
    val battery = readBatteryPercent(context)
    // Theme state is shared across the app via the
    // [ThemePreference] singleton (a simple file in
    // filesDir). Phase 122 ships the read + write
    // path; Phase 123 will wire the actual theme
    // switch to MaterialTheme.
    var theme by remember { mutableStateOf(readThemePreference(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ─── Theme ────────────────────────────────────
        SectionHeader("Theme")
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeChip("Sovereign Dark", theme == "dark") {
                theme = "dark"
                writeThemePreference(context, "dark")
            }
            ThemeChip("Sovereign Light", theme == "light") {
                theme = "light"
                writeThemePreference(context, "light")
            }
            ThemeChip("System", theme == "system") {
                theme = "system"
                writeThemePreference(context, "system")
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ─── Storage ──────────────────────────────────
        SectionHeader("Storage")
        Spacer(modifier = Modifier.height(8.dp))
        StatBar(
            icon = Icons.Filled.Storage,
            label = "Internal Storage (/sdcard)",
            usedGb = storage.usedGb,
            totalGb = storage.totalGb,
            percent = storage.usedPercent,
            tint = Color(0xFFFFB86C),
        )
        Spacer(modifier = Modifier.height(20.dp))

        // ─── Memory ───────────────────────────────────
        SectionHeader("Memory")
        Spacer(modifier = Modifier.height(8.dp))
        StatBar(
            icon = Icons.Filled.Memory,
            label = "RAM (${memory.totalGb} GB total)",
            usedGb = memory.usedGb,
            totalGb = memory.totalGb,
            percent = memory.usedPercent,
            tint = Color(0xFF8BE9FD),
        )
        Spacer(modifier = Modifier.height(20.dp))

        // ─── Battery ──────────────────────────────────
        SectionHeader("Battery")
        Spacer(modifier = Modifier.height(8.dp))
        StatBar(
            icon = Icons.Filled.BatteryFull,
            label = if (battery < 0) "Battery: unknown" else "Battery level",
            usedGb = "${battery.coerceAtLeast(0)}",
            totalGb = "100",
            percent = battery.coerceAtLeast(0),
            tint = if (battery < 30) Color(0xFFFF5555) else Color(0xFF50FA7B),
        )
        Spacer(modifier = Modifier.height(20.dp))

        // ─── About ────────────────────────────────────
        SectionHeader("About")
        Spacer(modifier = Modifier.height(8.dp))
        InfoRow("App", "Elysium Vanguard")
        InfoRow("Version", "1.0.0-TITAN")
        InfoRow("Package", "com.elysium.vanguard")
        InfoRow("Android", "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        InfoRow("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        InfoRow("Runtime", "Elysium Sovereign Runtime")
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(Locale.US),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatBar(
    icon: ImageVector,
    label: String,
    usedGb: String,
    totalGb: String,
    percent: Int,
    tint: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = tint,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$usedGb GB / $totalGb GB ($percent%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ─── Theme preference persistence ────────────────────
//
// Phase 122 ships the persistence layer (read +
// write) so the user's theme choice survives
// app restarts. The actual theme application
// (MaterialTheme re-color) is wired in Phase 123
// when we extract the theme tokens into a
// top-level provider.

private fun themePrefsFile(context: Context): File =
    File(context.filesDir, "settings/theme.txt")

private fun readThemePreference(context: Context): String = try {
    val f = themePrefsFile(context)
    if (f.exists()) f.readText().trim() else "dark"
} catch (_: Exception) {
    "dark"
}

private fun writeThemePreference(context: Context, value: String) {
    try {
        val f = themePrefsFile(context)
        f.parentFile?.mkdirs()
        f.writeText(value)
    } catch (_: Exception) {
        // Swallow — settings persistence is best-effort.
    }
}

@Composable
private fun NotesBody() {
    val context = LocalContext.current
    // Notes are persisted as text files in
    // filesDir/notes/. Each note = one file
    // (id.txt). The first line is the title;
    // subsequent lines are the body. Phase 122
    // ships the list + editor + persistence.
    var notes by remember { mutableStateOf(loadNotes(context)) }
    var selectedId by remember { mutableStateOf<String?>(notes.firstOrNull()?.id) }
    val selectedNote = notes.firstOrNull { it.id == selectedId }
    var title by remember(selectedId) { mutableStateOf(selectedNote?.title ?: "") }
    var content by remember(selectedId) { mutableStateOf(selectedNote?.content ?: "") }
    // Auto-save debounce — when the user stops
    // typing for 600ms, persist. We cancel the
    // previous save job on every keystroke.
    val scope = rememberCoroutineScope()
    var saveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ─── Note list (left) ────────────────────────
        Column(
            modifier = Modifier
                .width(160.dp)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val n = Note(
                            id = "note-${System.currentTimeMillis()}",
                            title = "Untitled",
                            content = "",
                        )
                        notes = notes + n
                        saveNotes(context, notes)
                        selectedId = n.id
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New note",
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    NoteListItem(
                        title = note.title.ifBlank { "Untitled" },
                        selected = note.id == selectedId,
                        onClick = {
                            // Persist the current edit before switching
                            saveJob?.cancel()
                            if (selectedId != null) {
                                notes = notes.map { n ->
                                    if (n.id == selectedId) n.copy(title = title, content = content)
                                    else n
                                }
                                saveNotes(context, notes)
                            }
                            selectedId = note.id
                        },
                    )
                }
            }
        }
        // ─── Editor (right) ──────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(12.dp),
        ) {
            if (selectedNote == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.SpeakerNotes,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No notes yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Tap + to create your first note",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // Title field
                TextField(
                    value = title,
                    onValueChange = {
                        title = it
                        saveJob = scheduleSave(
                            context = context,
                            scope = scope,
                            currentJob = saveJob,
                            selectedId = selectedId!!,
                            notes = notes,
                            title = title,
                            content = content,
                            onNotesUpdate = { updated -> notes = updated },
                        )
                    },
                    placeholder = { Text("Title") },
                    textStyle = MaterialTheme.typography.titleLarge,
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Body
                TextField(
                    value = content,
                    onValueChange = {
                        content = it
                        saveJob = scheduleSave(
                            context = context,
                            scope = scope,
                            currentJob = saveJob,
                            selectedId = selectedId!!,
                            notes = notes,
                            title = title,
                            content = content,
                            onNotesUpdate = { updated -> notes = updated },
                        )
                    },
                    placeholder = { Text("Start typing…") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                // Footer: delete button + char count
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val id = selectedId ?: return@IconButton
                            notes = notes.filter { it.id != id }
                            saveNotes(context, notes)
                            val next = notes.firstOrNull()
                            selectedId = next?.id
                            title = next?.title ?: ""
                            content = next?.content ?: ""
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete note",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${content.length} chars",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteListItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = fg,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Notes persistence ────────────────────────────────
//
// Storage: filesDir/notes/<id>.txt
// Format:  <title>\n<body>
// The list of notes is the list of files in
// filesDir/notes/. We don't need a separate index
// because the file system IS the index.

private data class Note(
    val id: String,
    val title: String,
    val content: String,
)

private fun notesDir(context: Context): File =
    File(context.filesDir, "notes").apply { mkdirs() }

private fun loadNotes(context: Context): List<Note> = try {
    notesDir(context).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".txt") }
        ?.map { f ->
            val text = f.readText()
            val title = text.lineSequence().firstOrNull().orEmpty()
            val body = text.lineSequence().drop(1).joinToString("\n")
            Note(
                id = f.name.removeSuffix(".txt"),
                title = title,
                content = body,
            )
        }
        ?.sortedByDescending { it.id }
        ?: emptyList()
} catch (_: Exception) {
    emptyList()
}

private fun saveNotes(context: Context, notes: List<Note>) {
    try {
        val dir = notesDir(context)
        // Remove notes that no longer exist
        val keepIds = notes.map { it.id }.toSet()
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".txt")
            if (id !in keepIds) f.delete()
        }
        // Write each note
        notes.forEach { n ->
            val f = File(dir, "${n.id}.txt")
            f.writeText("${n.title}\n${n.content}")
        }
    } catch (_: Exception) {
        // Best-effort persistence.
    }
}

/**
 * Debounced save — when the user types, we
 * cancel the previous save job and schedule a
 * new one 600ms in the future. The lambda
 * receives the updated notes list.
 *
 * Returns the new [Job] (or null if the scope
 * is cancelled). Caller replaces its own
 * [saveJob] state.
 */
private fun scheduleSave(
    context: Context,
    scope: CoroutineScope,
    currentJob: Job?,
    selectedId: String,
    notes: List<Note>,
    title: String,
    content: String,
    onNotesUpdate: (List<Note>) -> Unit,
): Job? {
    currentJob?.cancel()
    return scope.launch {
        delay(600)
        val updated = notes.map { n ->
            if (n.id == selectedId) n.copy(title = title, content = content)
            else n
        }
        saveNotes(context, updated)
        onNotesUpdate(updated)
    }
}

/**
 * PHASE 121 — fallback body for an external app launcher.
 * Normally the user never sees this body — the dock handler
 * fires the Intent in [launchExternal] before opening a
 * window. The body is here so the registry has a complete
 * iconKey → body map and a defensive placeholder if a window
 * is opened with an external iconKey for any reason.
 */
@Composable
private fun ExternalAppBody(appName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Text(
            text = appName,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "External app — launched from the dock. " +
                "Reopen the dock and tap the $appName icon to launch again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================== Hilt bridge ==============================

/**
 * Hilt EntryPoint to fetch the [WindowContentRegistry] from a
 * Composable-only context. The registry is @Singleton + @Inject,
 * so the EntryPoint just looks it up from the application context.
 *
 * We use this pattern (instead of a @Composable) because the
 * desktop shell is instantiated from MainActivity's
 * NavHost composable, which doesn't have a Hilt-injectable
 * wrapper. The EntryPoint bridge is the canonical workaround.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface WindowContentRegistryEntryPoint {
    fun windowContentRegistry(): WindowContentRegistry
}

/**
 * Get a [WindowContentRegistry] from a non-Hilt [Context].
 * Throws if Hilt isn't initialized (which would only happen
 * in unit tests with a mocked Application).
 */
private fun contentRegistryFor(context: Context): WindowContentRegistry {
    val app = context.applicationContext
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        app,
        WindowContentRegistryEntryPoint::class.java,
    )
    return entryPoint.windowContentRegistry()
}

/**
 * PHASE 121 — Composable helper for resolving the
 * [WindowContentRegistry] from any UI tree. The registry is
 * a Hilt-managed singleton; we go through the EntryPoint
 * bridge so callers don't have to pass the registry through
 * every parent composable explicitly.
 *
 * The result is `remember`-ed on the application context, so
 * recompositions don't repeatedly hit the EntryPoint machinery.
 */
@Composable
fun rememberWindowContentRegistry(): WindowContentRegistry {
    val context = LocalContext.current
    return remember(context) { contentRegistryFor(context) }
}
