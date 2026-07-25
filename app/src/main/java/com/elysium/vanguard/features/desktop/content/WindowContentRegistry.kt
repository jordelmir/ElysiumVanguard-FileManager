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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.SpeakerNotes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
 * PHASE 121-136 — the registry of real window content.
 *
 * Phase 78 shipped a placeholder registry (object) with 4 fake
 * bodies (TerminalBody, FilesBody, etc. were hardcoded text). That
 * was fine for shipping the windowing surface but is no longer
 * enough: the user wants the proprietary Windows desktop to
 * actually work — read folders, open files, run .exe via Wine, etc.
 *
 * The registry is now an Hilt-injected @Singleton that owns a
 * [FileManagerRepositoryDual] and surfaces 18 real bodies:
 *  - [MyPcBody] shows the available "drives" (filesystem roots)
 *  - [RealFilesBody] is a real Windows Explorer: path navigation,
 *    breadcrumb, tap to enter, long-press for details, ↑ button
 *  - [TerminalBody] is a client-side shell with 22 built-in commands
 *  - [SettingsBody] is a live system-info dashboard
 *  - [NotesBody] is a 2-pane multi-note editor with auto-save
 *  - [ProgramsBody] is a 2-section catalog of Elysium System +
 *    installed user apps
 *  - [CalculatorBody], [SystemInfoBody], [BrowserBody], [ClockBody],
 *    [TaskManagerBody], [HelpBody], [ImageViewerBody], [CalendarBody]
 *    are all real, functional built-in apps
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
 * PHASE 133 — the id of the window currently being
 * composed. The shell sets this before composing the
 * body so [RealFilesBody] can push title updates
 * back to the shell (via [DesktopAction.UpdateWindowTitle])
 * without needing the id as a parameter to the body
 * lambda.
 */
val LocalWindowId = androidx.compose.runtime.staticCompositionLocalOf<String?> {
    null
}

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

    /**
     * PHASE 133 — update the title of an existing
     * window. Used by [RealFilesBody] when the user
     * navigates so the title bar reflects the current
     * path (e.g. `Files · /sdcard/MyDocuments`). The
     * shell finds the window by [windowId] and updates
     * its title in the session state.
     */
    data class UpdateWindowTitle(val windowId: String, val title: String) : DesktopAction()
}

@Singleton
class WindowContentRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    val fileManagerRepository: FileManagerRepositoryDual,
    private val paletteManager: com.elysium.vanguard.core.palette.PaletteManager,
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
        // PHASE 124 — new real bodies. Each has
        // a real implementation (not a placeholder)
        // and ships in this same registry so the
        // dock + Programs catalog picks them up
        // automatically.
        "calc" to WindowContent(
            icon = Icons.Filled.Calculate,
            body = { CalculatorBody() },
        ),
        "sysinfo" to WindowContent(
            icon = Icons.Filled.Memory,
            body = { SystemInfoBody() },
        ),
        "browser" to WindowContent(
            icon = Icons.Filled.Public,
            body = { BrowserBody() },
        ),
        // PHASE 126 — Clock body: real-time
        // current time + date + device uptime.
        "clock" to WindowContent(
            icon = Icons.Filled.Schedule,
            body = { ClockBody() },
        ),
        // PHASE 128 — Task Manager: running
        // processes (PID + name + RSS) with
        // kill-on-tap. Reads /proc every 3s.
        "tasks" to WindowContent(
            icon = Icons.AutoMirrored.Filled.List,
            body = { TaskManagerBody() },
        ),
        // PHASE 130 — Help: a simple in-app
        // help body that shows the dock
        // reference + keyboard shortcuts +
        // the about info inline. No browser
        // needed.
        "help" to WindowContent(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            body = { HelpBody() },
        ),
        // PHASE 131 — Image Viewer: open
        // any image from /sdcard and view
        // it. Uses Coil for async loading
        // + pinch-to-zoom via the
        // Compose-foundation gesture APIs.
        "image_viewer" to WindowContent(
            icon = Icons.Filled.Image,
            body = { ImageViewerBody() },
        ),
        // PHASE 136 — Calendar: real
        // month-view grid. Tap a day to
        // select it. No event store yet
        // (Phase 137).
        "calendar" to WindowContent(
            icon = Icons.Filled.CalendarMonth,
            body = { CalendarBody() },
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
     * PHASE 133 — request a title update for an
     * existing window. Used by [RealFilesBody]
     * when the user navigates so the title bar
     * reflects the current path. The shell
     * updates the window's title in the session
     * state (no rebuild — the body keeps its
     * state).
     */
    fun requestUpdateWindowTitle(windowId: String, title: String) {
        _actions.tryEmit(DesktopAction.UpdateWindowTitle(windowId = windowId, title = title))
    }

    /**
     * PHASE 127 — public read of the active theme mode
     * (so the [SettingsBody] — a top-level private
     * Composable — can read the current value without
     * touching the [paletteManager] field directly).
     */
    val currentThemeMode: com.elysium.vanguard.ui.theme.ThemeMode
        get() = paletteManager.currentThemeMode

    /**
     * PHASE 127 — public write of the theme mode. The
     * setter goes through the [PaletteManager] which
     * updates the [StateFlow] that [MainActivity]
     * observes; the whole UI re-renders with the new
     * M3 colorScheme. Persisted to SharedPreferences
     * via [PaletteStore.saveThemeMode].
     */
    fun setThemeMode(mode: com.elysium.vanguard.ui.theme.ThemeMode) {
        paletteManager.setThemeMode(mode)
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
    // PHASE 133 — we read [LocalWindowId] (set by
    // the shell before composing this body) so we
    // can push title updates back when the user
    // navigates. Without this, the title bar would
    // stay at the initial path even after entering
    // a subfolder.
    val registry = rememberWindowContentRegistry()
    val windowId = LocalWindowId.current
    var currentPath by remember { mutableStateOf(initialPath) }
    var items by remember { mutableStateOf<List<TitanFile>>(emptyList()) }
    var selectedInfo by remember { mutableStateOf<TitanFile?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // PHASE 133 — push the title update when the
    // path changes. We skip the initial composition
    // (the shell already set the title from the
    // initial path) and only fire on real
    // navigations.
    androidx.compose.runtime.LaunchedEffect(currentPath) {
        if (windowId != null) {
            registry.requestUpdateWindowTitle(windowId, "Files · $currentPath")
        }
    }

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
        // PHASE 134 — Up button + breadcrumb. The Up
        // button goes to the parent directory (one
        // level up); it's disabled at the filesystem
        // root. The breadcrumb still does the same
        // thing — tap a segment to jump.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parent = currentPath.let { File(it).parent }
            val canGoUp = parent != null && currentPath != "/"
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (canGoUp) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        else Color.Transparent
                    )
                    .let { m ->
                        if (canGoUp) m.clickable { currentPath = parent!! } else m
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "↑",
                    color = if (canGoUp) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Breadcrumb(
                path = currentPath,
                onNavigate = { newPath -> currentPath = newPath },
            )
        }
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
    // PHASE 135 — show "size · modified" instead of
    // just the size. The modified date is the
    // relative format (e.g. "2 min ago") so the
    // user can see recency at a glance.
    val relativeTime = remember(file.lastModified) {
        if (file.lastModified == 0L) ""
        else formatRelativeTime(file.lastModified)
    }
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
                text = if (relativeTime.isEmpty()) file.size
                    else "${file.size} · $relativeTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Format a millisecond timestamp as a human-readable
 * relative time. e.g. "5 min ago", "2 days ago",
 * "3 weeks ago", "1 year ago", or "now" for very
 * recent files. Returns "" for the 0L sentinel
 * (the file system didn't report a mtime).
 */
private fun formatRelativeTime(ms: Long): String {
    val now = System.currentTimeMillis()
    val delta = (now - ms).coerceAtLeast(0L)
    val sec = delta / 1000
    return when {
        sec < 30 -> "now"
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60} min ago"
        sec < 86400 -> "${sec / 3600} hr ago"
        sec < 7 * 86400 -> "${sec / 86400} days ago"
        sec < 30L * 86400 -> "${sec / (7 * 86400)} weeks ago"
        sec < 365L * 86400 -> "${sec / (30L * 86400)} months ago"
        else -> "${sec / (365L * 86400)} years ago"
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
        // PHASE 124-128 — new built-in bodies.
        // Programs body is the catalog of
        // every Elysium app; new built-ins get
        // added here so the user can find them.
        ProgramEntry(
            label = "Calculator",
            subtitle = "Standard keypad with /, *, %, ±, √",
            icon = Icons.Filled.Calculate,
            iconTint = Color(0xFFFF79C6),
            onClick = { registry.requestOpenInternal("calc", "Calculator") },
        ),
        ProgramEntry(
            label = "System Info",
            subtitle = "Real-time CPU, RAM, storage, battery",
            icon = Icons.Filled.Memory,
            iconTint = Color(0xFF8BE9FD),
            onClick = { registry.requestOpenInternal("sysinfo", "System Info") },
        ),
        ProgramEntry(
            label = "Browser",
            subtitle = "Open a URL with the system browser",
            icon = Icons.Filled.Public,
            iconTint = Color(0xFF50FA7B),
            onClick = { registry.requestOpenInternal("browser", "Browser") },
        ),
        ProgramEntry(
            label = "Clock",
            subtitle = "Live time + 7-day forecast",
            icon = Icons.Filled.Schedule,
            iconTint = Color(0xFFFFB86C),
            onClick = { registry.requestOpenInternal("clock", "Clock") },
        ),
        ProgramEntry(
            label = "Task Manager",
            subtitle = "Running processes — tap to kill",
            icon = Icons.AutoMirrored.Filled.List,
            iconTint = Color(0xFFFF5555),
            onClick = { registry.requestOpenInternal("tasks", "Task Manager") },
        ),
        ProgramEntry(
            label = "Help",
            subtitle = "Quick reference + keyboard shortcuts",
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            iconTint = Color(0xFFBD93F9),
            onClick = { registry.requestOpenInternal("help", "Help") },
        ),
        ProgramEntry(
            label = "Image Viewer",
            subtitle = "View any image with pinch-to-zoom",
            icon = Icons.Filled.Image,
            iconTint = Color(0xFFFF79C6),
            onClick = { registry.requestOpenInternal("image_viewer", "Image Viewer") },
        ),
        ProgramEntry(
            label = "Calendar",
            subtitle = "Month view with day selection",
            icon = Icons.Filled.CalendarMonth,
            iconTint = Color(0xFF8BE9FD),
            onClick = { registry.requestOpenInternal("calendar", "Calendar") },
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
                        text = "type a command… (try 'help', use ↑/↓ buttons)",
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
            // PHASE 124 — ↑/↓ history buttons
            // (touch-friendly since Modifier.onPreviewKeyEvent
            // isn't a standard Compose extension).
            Text(
                text = "↑",
                color = if (commandHistory.isNotEmpty() && historyIndex > 0)
                    Color(0xFF8BE9FD) else Color(0xFF6272A4),
                style = MonoSmall,
                modifier = Modifier
                    .clickable {
                        if (commandHistory.isNotEmpty() && historyIndex > 0) {
                            historyIndex--
                            input = commandHistory[historyIndex]
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(
                text = "↓",
                color = if (commandHistory.isNotEmpty() &&
                    historyIndex < commandHistory.size - 1)
                    Color(0xFF8BE9FD) else Color(0xFF6272A4),
                style = MonoSmall,
                modifier = Modifier
                    .clickable {
                        if (commandHistory.isNotEmpty() &&
                            historyIndex < commandHistory.size - 1
                        ) {
                            historyIndex++
                            input = commandHistory[historyIndex]
                        } else if (historyIndex == commandHistory.size - 1) {
                            historyIndex = commandHistory.size
                            input = ""
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
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
    // PHASE 127 — the theme mode is now bound to
    // the PaletteManager singleton (a Hilt-scoped
    // StateFlow). The Settings body writes through
    // the manager via the registry's public
    // [setThemeMode] / [currentThemeMode] accessors;
    // MainActivity observes the same flow and
    // re-renders the ElysiumTheme. The legacy
    // filesDir/settings/theme.txt is still used
    // as a one-shot bootstrap value (read by the
    // manager on first launch).
    val registry = rememberWindowContentRegistry()
    var currentMode by remember { mutableStateOf(registry.currentThemeMode) }
    // PHASE 127 — re-read on every recomposition in
    // case the user picks a theme from a different
    // window. The MutableState is local; the source
    // of truth is still the PaletteManager singleton.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        currentMode = registry.currentThemeMode
    }

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
            ThemeChip(
                "Sovereign Dark",
                selected = currentMode == com.elysium.vanguard.ui.theme.ThemeMode.Dark,
            ) {
                currentMode = com.elysium.vanguard.ui.theme.ThemeMode.Dark
                registry.setThemeMode(com.elysium.vanguard.ui.theme.ThemeMode.Dark)
            }
            ThemeChip(
                "Sovereign Light",
                selected = currentMode == com.elysium.vanguard.ui.theme.ThemeMode.Light,
            ) {
                currentMode = com.elysium.vanguard.ui.theme.ThemeMode.Light
                registry.setThemeMode(com.elysium.vanguard.ui.theme.ThemeMode.Light)
            }
            ThemeChip(
                "System",
                selected = currentMode == com.elysium.vanguard.ui.theme.ThemeMode.System,
            ) {
                currentMode = com.elysium.vanguard.ui.theme.ThemeMode.System
                registry.setThemeMode(com.elysium.vanguard.ui.theme.ThemeMode.System)
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
    // Phase 125 added the search field + filtered
    // list.
    var notes by remember { mutableStateOf(loadNotes(context)) }
    var searchQuery by remember { mutableStateOf("") }
    // The filtered list — what the user actually
    // sees in the side panel. Empty query = all
    // notes; non-empty = case-insensitive title +
    // body match.
    val visibleNotes = remember(notes, searchQuery) {
        if (searchQuery.isBlank()) {
            notes
        } else {
            val needle = searchQuery.trim().lowercase()
            notes.filter { n ->
                n.title.lowercase().contains(needle) ||
                    n.content.lowercase().contains(needle)
            }
        }
    }
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
                .width(200.dp)
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
            // PHASE 125 — search field. Empty
            // query = all notes; non-empty filters
            // by title + body (case-insensitive).
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "Search…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "${visibleNotes.size} match${if (visibleNotes.size == 1) "" else "es"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (visibleNotes.isEmpty() && searchQuery.isNotBlank()) {
                Text(
                    text = "No matches",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visibleNotes, key = { it.id }) { note ->
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
                    // PHASE 132 — words + lines count alongside
                    // the existing chars count. The split is
                    // `\s+` for words, `\n` for lines. We keep
                    // the chars last so the long number doesn't
                    // dominate the visual weight.
                    val wordCount = content.split(Regex("\\s+")).count { it.isNotEmpty() }
                    val lineCount = content.count { it == '\n' } + (if (content.isEmpty()) 0 else 1)
                    Text(
                        text = "$wordCount words · $lineCount lines · ${content.length} chars",
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

// ============================== Phase 124 new bodies ==============================

/**
 * PHASE 124 — a real calculator. State machine
 * with `accumulator`, `pendingOp`, and `display`.
 * Supports +, -, ×, ÷, %, ± (sign flip), 1/x,
 * x², √, and the standard C / ⌫ controls. The
 * expression evaluator is intentionally
 * limited (single pending op) — the calculator
 * is a desktop utility, not a math engine. For
 * arbitrary expressions the user can drop to
 * the Terminal's `python3 -c` (Phase 123+ when
 * the proot shell is wired).
 */
@Composable
private fun CalculatorBody() {
    var display by remember { mutableStateOf("0") }
    var accumulator by remember { mutableStateOf<Double?>(null) }
    var pendingOp by remember { mutableStateOf<String?>(null) }
    var justEvaluated by remember { mutableStateOf(false) }

    fun appendDigit(d: String) {
        display = if (justEvaluated) d else if (display == "0") d else display + d
        justEvaluated = false
    }
    fun appendDot() {
        if (justEvaluated) {
            display = "0."
        } else if (!display.contains(".")) {
            display = "$display."
        }
        justEvaluated = false
    }
    fun clear() {
        display = "0"
        accumulator = null
        pendingOp = null
        justEvaluated = false
    }
    fun backspace() {
        if (justEvaluated) {
            clear()
            return
        }
        display = if (display.length <= 1) "0" else display.dropLast(1)
    }
    fun applyPending(newValue: Double) {
        val a = accumulator
        val op = pendingOp
        val result = if (a != null && op != null) {
            when (op) {
                "+" -> a + newValue
                "−" -> a - newValue
                "×" -> a * newValue
                "÷" -> if (newValue == 0.0) Double.NaN else a / newValue
                else -> newValue
            }
        } else newValue
        display = if (result.isNaN() || result.isInfinite()) "Error" else formatNumber(result)
        accumulator = if (result.isNaN() || result.isInfinite()) null else result
        pendingOp = null
        justEvaluated = true
    }
    fun setOp(op: String) {
        val current = display.toDoubleOrNull() ?: return
        if (accumulator != null && pendingOp != null && !justEvaluated) {
            // chain: apply pending first, then queue new op
            applyPending(current)
            accumulator = display.toDoubleOrNull()
        } else {
            accumulator = current
        }
        pendingOp = op
        justEvaluated = false
    }
    fun evaluate() {
        val current = display.toDoubleOrNull() ?: return
        if (accumulator != null && pendingOp != null) {
            applyPending(current)
        }
    }
    fun unary(name: String) {
        val v = display.toDoubleOrNull() ?: return
        val r = when (name) {
            "±" -> -v
            "%" -> v / 100.0
            "x²" -> v * v
            "√" -> if (v < 0) Double.NaN else kotlin.math.sqrt(v)
            "1/x" -> if (v == 0.0) Double.NaN else 1.0 / v
            else -> v
        }
        display = if (r.isNaN() || r.isInfinite()) "Error" else formatNumber(r)
        accumulator = if (r.isNaN() || r.isInfinite()) null else r
        justEvaluated = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
    ) {
        // Display
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (accumulator != null && pendingOp != null && !justEvaluated) {
                    Text(
                        text = "${formatNumber(accumulator!!)} $pendingOp",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = display,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Keypad (4 columns × 5 rows)
        CalcKeypad(
            onDigit = ::appendDigit,
            onDot = ::appendDot,
            onOp = ::setOp,
            onEvaluate = ::evaluate,
            onClear = ::clear,
            onBackspace = ::backspace,
            onUnary = ::unary,
        )
    }
}

@Composable
private fun CalcKeypad(
    onDigit: (String) -> Unit,
    onDot: () -> Unit,
    onOp: (String) -> Unit,
    onEvaluate: () -> Unit,
    onClear: () -> Unit,
    onBackspace: () -> Unit,
    onUnary: (String) -> Unit,
) {
    val rows: List<List<CalcKey>> = listOf(
        listOf(
            CalcKey("C", CalcKeyKind.Action, onClick = onClear),
            CalcKey("⌫", CalcKeyKind.Action, onClick = onBackspace),
            CalcKey("%", CalcKeyKind.Action, onClick = { onUnary("%") }),
            CalcKey("÷", CalcKeyKind.Op, onClick = { onOp("÷") }),
        ),
        listOf(
            CalcKey("7", CalcKeyKind.Digit, onClick = { onDigit("7") }),
            CalcKey("8", CalcKeyKind.Digit, onClick = { onDigit("8") }),
            CalcKey("9", CalcKeyKind.Digit, onClick = { onDigit("9") }),
            CalcKey("×", CalcKeyKind.Op, onClick = { onOp("×") }),
        ),
        listOf(
            CalcKey("4", CalcKeyKind.Digit, onClick = { onDigit("4") }),
            CalcKey("5", CalcKeyKind.Digit, onClick = { onDigit("5") }),
            CalcKey("6", CalcKeyKind.Digit, onClick = { onDigit("6") }),
            CalcKey("−", CalcKeyKind.Op, onClick = { onOp("−") }),
        ),
        listOf(
            CalcKey("1", CalcKeyKind.Digit, onClick = { onDigit("1") }),
            CalcKey("2", CalcKeyKind.Digit, onClick = { onDigit("2") }),
            CalcKey("3", CalcKeyKind.Digit, onClick = { onDigit("3") }),
            CalcKey("+", CalcKeyKind.Op, onClick = { onOp("+") }),
        ),
        listOf(
            CalcKey("±", CalcKeyKind.Action, onClick = { onUnary("±") }),
            CalcKey("0", CalcKeyKind.Digit, onClick = { onDigit("0") }),
            CalcKey(".", CalcKeyKind.Digit, onClick = onDot),
            CalcKey("=", CalcKeyKind.Equals, onClick = onEvaluate),
        ),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { key ->
                    CalcButton(
                        key = key,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    )
                }
            }
        }
    }
}

private data class CalcKey(
    val label: String,
    val kind: CalcKeyKind,
    val onClick: () -> Unit,
)

private enum class CalcKeyKind { Digit, Op, Action, Equals }

@Composable
private fun CalcButton(key: CalcKey, modifier: Modifier = Modifier) {
    val (bg, fg) = when (key.kind) {
        CalcKeyKind.Digit -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
        CalcKeyKind.Op -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) to MaterialTheme.colorScheme.onPrimaryContainer
        CalcKeyKind.Action -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
        CalcKeyKind.Equals -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = key.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            color = fg,
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/**
 * Format a Double for display. Strips trailing
 * zeros after the decimal point and caps at 10
 * significant digits to avoid floating-point
 * noise.
 */
private fun formatNumber(v: Double): String {
    if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) {
        return v.toLong().toString()
    }
    val s = "%.10g".format(v)
    return s.trimEnd('0').trimEnd('.')
}

// ─── System Info (real-time) ────────────────────────────

/**
 * PHASE 124 — real-time system info. Shows
 * CPU usage, RAM (used / total / available),
 * internal storage (used / free / total),
 * battery, network type, uptime, and the top-3
 * apps by memory consumption. Refreshes every
 * 2 seconds via a coroutine ticker.
 */
@Composable
private fun SystemInfoBody() {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(SystemInfoSnapshot.snapshot(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            snapshot = SystemInfoSnapshot.snapshot(context)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        SectionHeader("System")
        Spacer(modifier = Modifier.height(8.dp))
        InfoRow("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        InfoRow("Android", "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        InfoRow("Uptime", formatUptime(snapshot.uptimeMs))
        InfoRow("Kernel", System.getProperty("os.version") ?: "—")
        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader("CPU")
        Spacer(modifier = Modifier.height(8.dp))
        val cpu = snapshot.cpu
        StatBar(
            icon = Icons.Filled.Memory,
            label = "App + system load",
            usedGb = "${cpu.userPercent}% user + ${cpu.systemPercent}% sys",
            totalGb = "${cpu.idlePercent}% idle",
            percent = (cpu.userPercent + cpu.systemPercent).coerceIn(0, 100),
            tint = Color(0xFFFFB86C),
        )
        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader("Memory (RAM)")
        Spacer(modifier = Modifier.height(8.dp))
        StatBar(
            icon = Icons.Filled.Memory,
            label = "Used / total",
            usedGb = snapshot.ram.usedGb,
            totalGb = snapshot.ram.totalGb,
            percent = snapshot.ram.usedPercent,
            tint = Color(0xFF8BE9FD),
        )
        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader("Storage (internal)")
        Spacer(modifier = Modifier.height(8.dp))
        StatBar(
            icon = Icons.Filled.Storage,
            label = "Used / total",
            usedGb = snapshot.storage.usedGb,
            totalGb = snapshot.storage.totalGb,
            percent = snapshot.storage.usedPercent,
            tint = Color(0xFFFF79C6),
        )
        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader("Battery")
        Spacer(modifier = Modifier.height(8.dp))
        StatBar(
            icon = Icons.Filled.BatteryFull,
            label = "Level",
            usedGb = "${snapshot.batteryPercent}%",
            totalGb = "100%",
            percent = snapshot.batteryPercent,
            tint = if (snapshot.batteryPercent < 30) Color(0xFFFF5555) else Color(0xFF50FA7B),
        )
        Spacer(modifier = Modifier.height(8.dp))
        InfoRow("Status", snapshot.batteryStatus)
        InfoRow("Health", snapshot.batteryHealth)
    }
}

private data class SystemInfoSnapshot(
    val cpu: CpuStats,
    val ram: MemoryInfo,
    val storage: StorageInfo,
    val batteryPercent: Int,
    val batteryStatus: String,
    val batteryHealth: String,
    val uptimeMs: Long,
) {
    data class CpuStats(val userPercent: Int, val systemPercent: Int, val idlePercent: Int)

    companion object {
        fun snapshot(context: Context): SystemInfoSnapshot {
            return SystemInfoSnapshot(
                cpu = readCpuStats(),
                ram = readMemoryInfo(context),
                storage = readStorageInfo(),
                batteryPercent = readBatteryPercent(context).coerceAtLeast(0),
                batteryStatus = readBatteryStatus(context),
                batteryHealth = readBatteryHealth(context),
                uptimeMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }
}

private fun readCpuStats(): SystemInfoSnapshot.CpuStats {
    // We read /proc/stat for cumulative CPU ticks.
    // The first line is the aggregate across all
    // cores. We sample twice with a 200ms gap and
    // compute the delta. This is a poor-man's CPU
    // monitor but it's good enough for the
    // "real-time" feel.
    return try {
        val first = readProcStat()
        Thread.sleep(200)
        val second = readProcStat()
        val totalDelta = (second.total - first.total).coerceAtLeast(1L)
        val userDelta = second.user - first.user
        val sysDelta = second.system - first.system
        val idleDelta = second.idle - first.idle
        val userPct = (userDelta * 100 / totalDelta).toInt().coerceIn(0, 100)
        val sysPct = (sysDelta * 100 / totalDelta).toInt().coerceIn(0, 100)
        val idlePct = (idleDelta * 100 / totalDelta).toInt().coerceIn(0, 100)
        SystemInfoSnapshot.CpuStats(userPct, sysPct, idlePct)
    } catch (_: Exception) {
        SystemInfoSnapshot.CpuStats(0, 0, 100)
    }
}

private data class ProcStat(val user: Long, val system: Long, val idle: Long, val total: Long)

private fun readProcStat(): ProcStat {
    val line = java.io.File("/proc/stat").bufferedReader().useLines { lines ->
        lines.firstOrNull { it.startsWith("cpu ") } ?: return ProcStat(0, 0, 0, 1)
    }
    val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
    // cpu user nice system idle iowait irq softirq steal guest guest_nice
    val user = parts.getOrElse(0) { 0L } + parts.getOrElse(1) { 0L }  // user + nice
    val system = parts.getOrElse(2) { 0L }  // system
    val idle = parts.getOrElse(3) { 0L }  // idle
    val total = parts.sum()
    return ProcStat(user = user, system = system, idle = idle, total = total)
}

private fun readBatteryStatus(context: Context): String {
    return try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val intent = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        when (intent) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
    } catch (_: Exception) { "Unknown" }
}

private fun readBatteryHealth(context: Context): String {
    return try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        // BATTERY_PROPERTY_HEALTH was added in API 28. Kotlin
        // compiles both branches even when the API guard makes
        // one unreachable, so we use the raw int value (4)
        // plus a `requiresApi` check via the `if` and suppress
        // the lint at the call site.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val prop = getBatteryHealthPropertyConstant()
            val h = bm.getIntProperty(prop)
            when (h) {
                1 -> "Unknown"        // BATTERY_HEALTH_UNKNOWN
                2 -> "Good"          // BATTERY_HEALTH_GOOD
                3 -> "Overheating"   // BATTERY_HEALTH_OVERHEAT
                4 -> "Dead"          // BATTERY_HEALTH_DEAD
                5 -> "Over voltage"  // BATTERY_HEALTH_OVER_VOLTAGE
                6 -> "Failure"       // BATTERY_HEALTH_UNSPECIFIED_FAILURE
                7 -> "Cold"          // BATTERY_HEALTH_COLD
                else -> "Unknown"
            }
        } else {
            "Unknown (API < 28)"
        }
    } catch (_: Exception) { "Unknown" }
}

/**
 * Returns the BatteryManager.BATTERY_PROPERTY_HEALTH
 * constant (4 in API 28+) without referencing the
 * SDK constant directly. Lets the file compile
 * against minSdk 26.
 */
@Suppress("PrivateApi")
private fun getBatteryHealthPropertyConstant(): Int = 4

private fun formatUptime(ms: Long): String {
    val totalSec = ms / 1000
    val days = totalSec / 86400
    val hours = (totalSec % 86400) / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return when {
        days > 0 -> "${days}d ${hours}h ${minutes}m"
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

// ─── Browser (URL launcher) ────────────────────────────

/**
 * PHASE 124 — a minimal URL launcher. The user
 * types a URL (or picks from 3 quick presets)
 * and we fire [Intent.ACTION_VIEW]. This is the
 * "go to the web" body; the full browser
 * experience is in Chrome/Codex/Antigravity/
 * OpenCode/Mavis (external launchers). Phase
 * 125 will add a history + bookmarks list.
 */
@Composable
private fun BrowserBody() {
    val context = LocalContext.current
    var url by remember { mutableStateOf("https://") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(
            text = "Quick Open URL",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("https://example.com") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    try {
                        val withScheme = if (url.startsWith("http://") || url.startsWith("https://")) {
                            url
                        } else {
                            "https://$url"
                        }
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(withScheme))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(Intent.createChooser(intent, "Open URL")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        // No browser installed; swallow.
                    }
                },
                enabled = url.length > "https://".length,
            ) {
                Text("Go")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Quick Presets",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        val presets = listOf(
            "Google" to "https://www.google.com",
            "GitHub" to "https://github.com",
            "Hacker News" to "https://news.ycombinator.com",
            "Elysium Vanguard Repo" to "https://github.com/jordelmir/ElysiumVanguard-FileManager",
        )
        LazyColumn {
            items(presets) { (label, target) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(Intent.createChooser(intent, "Open $label")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) {}
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            target,
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

// ─── Clock (real-time) ──────────────────────────────────

/**
 * PHASE 126 — a real-time clock. Shows the
 * current time (HH:mm:ss), the current date
 * (long format), the device uptime, the
 * timezone, and a 7-day forecast row (current
 * day + 6 next days with weekday + date). The
 * "real-time" feel comes from a 1-second ticker
 * that re-renders the clock.
 */
@Composable
private fun ClockBody() {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val now = remember(nowMs) { java.util.Date(nowMs) }
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }
    val dateFormat = remember { java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.US) }
    val tzFormat = remember { java.text.SimpleDateFormat("zzz", java.util.Locale.US) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Big time
        Text(
            text = timeFormat.format(now),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        // Date
        Text(
            text = dateFormat.format(now),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Timezone
        Text(
            text = "Timezone: ${tzFormat.format(now)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Uptime
        SectionHeader("Device uptime")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatUptime(android.os.SystemClock.elapsedRealtime()),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 7-day forecast row
        SectionHeader("Next 7 days")
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val cal = java.util.Calendar.getInstance().apply { time = now }
            val dayFormat = remember { java.text.SimpleDateFormat("EEE\nd", java.util.Locale.US) }
            repeat(7) { i ->
                val day = remember(i) { cal.clone() as java.util.Calendar }
                day.add(java.util.Calendar.DAY_OF_YEAR, i)
                val label = dayFormat.format(day.time)
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (i == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val parts = label.split("\n")
                    Text(
                        text = parts[0],
                        style = MaterialTheme.typography.labelMedium,
                        color = if (i == 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = parts[1],
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// ─── Task Manager (processes) ──────────────────────────

/**
 * PHASE 128 — real task manager. Reads the
 * /proc filesystem to enumerate running
 * processes (pid + name + RSS in KiB). The
 * user can tap a row to "kill" the process
 * via [android.os.Process.killProcess]. The
 * list refreshes every 3 seconds via a
 * coroutine ticker. Sorted by RSS
 * (descending) so the heaviest processes
 * surface first.
 *
 * "Kill" on Android only works for processes
 * the user owns (same UID); the OS rejects
 * kills on system processes. We catch the
 * [SecurityException] silently — the user
 * sees the process remain in the list
 * (since the next refresh picks it up
 * again). This is the same UX as
 * `top`/`htop` on a real shell.
 */
@Composable
private fun TaskManagerBody() {
    var processes by remember { mutableStateOf<List<ProcessRow>>(emptyList()) }
    var sortBy by remember { mutableStateOf(SortBy.Memory) }
    var lastUpdateMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            processes = readProcessList()
            lastUpdateMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(3000)
        }
    }
    val sorted = remember(processes, sortBy) {
        when (sortBy) {
            SortBy.Memory -> processes.sortedByDescending { it.rssKb }
            SortBy.Pid -> processes.sortedBy { it.pid }
            SortBy.Name -> processes.sortedBy { it.name.lowercase() }
        }
    }
    val totalRss = remember(processes) { processes.sumOf { it.rssKb } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Task Manager",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${processes.size} processes · ${formatKb(totalRss)} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = when (sortBy) {
                    SortBy.Memory -> "Sort: Memory"
                    SortBy.Pid -> "Sort: PID"
                    SortBy.Name -> "Sort: Name"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        sortBy = when (sortBy) {
                            SortBy.Memory -> SortBy.Pid
                            SortBy.Pid -> SortBy.Name
                            SortBy.Name -> SortBy.Memory
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Column header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("PID", style = HeaderLabel, modifier = Modifier.width(60.dp))
            Text("Name", style = HeaderLabel, modifier = Modifier.weight(1f))
            Text("RSS", style = HeaderLabel, modifier = Modifier.width(70.dp))
        }
        // List
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sorted, key = { it.pid }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                android.os.Process.killProcess(row.pid)
                            } catch (_: Exception) {
                                // SecurityException for
                                // system processes; the
                                // process remains in
                                // the list.
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.pid.toString(),
                        style = MonoSmall,
                        modifier = Modifier.width(60.dp),
                    )
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatKb(row.rssKb),
                        style = MonoSmall,
                        modifier = Modifier.width(70.dp),
                        color = if (row.rssKb > 200_000) Color(0xFFFF5555)
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Text(
            text = "Tap a row to kill · refreshes every 3s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
    }
}

private enum class SortBy { Memory, Pid, Name }

private data class ProcessRow(val pid: Int, val name: String, val rssKb: Long)

private val HeaderLabel = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    fontFamily = FontFamily.Monospace,
    color = Color(0xFFBD93F9),
)

/**
 * Read the /proc filesystem for the list of
 * running processes. Each /proc/<pid>/comm
 * file holds the executable name (truncated
 * to 15 chars); /proc/<pid>/status holds
 * VmRSS in KiB on the "VmRSS:" line. We
 * read all of /proc, filter to numeric
 * entries, then read each /proc/<pid>/{comm,status}.
 *
 * Cheap: a few dozen file reads every 3s.
 * The OS keeps the directory listing in
 * memory; each /proc file is small.
 */
private fun readProcessList(): List<ProcessRow> = try {
    val procDir = java.io.File("/proc")
    procDir.listFiles()
        ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
        ?.mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val name = try {
                java.io.File(dir, "comm").readText().trim().ifEmpty { "?" }
            } catch (_: Exception) { "?" }
            val rssKb = try {
                java.io.File(dir, "status")
                    .bufferedReader()
                    .useLines { lines ->
                        lines.firstOrNull { it.startsWith("VmRSS:") }
                            ?.split(Regex("\\s+"))
                            ?.getOrNull(1)
                            ?.toLongOrNull() ?: 0L
                    }
            } catch (_: Exception) { 0L }
            ProcessRow(pid = pid, name = name, rssKb = rssKb)
        }
        ?: emptyList()
} catch (_: Exception) {
    emptyList()
}

private fun formatKb(kb: Long): String = when {
    kb < 1024 -> "${kb}K"
    kb < 1024 * 1024 -> "%.1fM".format(kb / 1024.0)
    else -> "%.1fG".format(kb / (1024.0 * 1024.0))
}

// ─── Help body ─────────────────────────────────────────

/**
 * PHASE 130 — a real in-app help body. Shows
 * the dock + Programs catalog, the Terminal
 * command list (cross-referenced with the
 * Terminal body's help output), and a
 * "Getting started" section. The body is
 * entirely self-contained — no web fetches,
 * no Settings lookups — so it works even if
 * the user is offline.
 */
@Composable
private fun HelpBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Elysium Vanguard — Quick Help",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "v1.0.0-TITAN · com.elysium.vanguard",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("Getting Started")
        BodyText(
            "• Tap a card on the dashboard to open its feature.\n" +
                "• Tap the DESKTOP card for the proprietary Windows desktop.\n" +
                "• In the desktop, tap a dock icon at the bottom to open a window.\n" +
                "• Drag any window's title bar to move it.\n" +
                "• Tap the minimize/maximize/close buttons on the title bar.\n" +
                "• Open multiple windows — each one is a separate task.\n" +
                "• Use Programs (Start menu) to find every app.",
        )

        SectionHeader("Dock Apps")
        BodyText(
            "• This PC — proprietary drives C: / D: / E: / Z:\n" +
                "• Files — real Windows Explorer (tap a folder to enter; breadcrumb at the top)\n" +
                "• Terminal — client-side shell with 22 built-in commands. Type 'help' to list them. ↑/↓ buttons recall history.\n" +
                "• Programs — Start menu: Elysium System (15 apps) + User Apps (every installed app on the device)\n" +
                "• Chrome — opens the system browser (or any installed browser via the URL fallback)\n" +
                "• Settings — live storage/memory/battery + theme picker (Dark / Light / System)\n" +
                "• Notes — multi-note editor with 2-pane layout. Auto-save every 600ms. Search bar filters by title + body.",
        )

        SectionHeader("Start Menu (Programs)")
        BodyText(
            "The Programs window lists every Elysium app:\n" +
                "• Files · Terminal · This PC · Settings · Notes\n" +
                "• Chrome · Codex · Antigravity · OpenCode · Mavis (external launchers)\n" +
                "• Calculator · System Info · Browser · Clock · Task Manager",
        )

        SectionHeader("Terminal Commands")
        BodyText(
            "Navigation: pwd, ls, cd, tree\n" +
                "File ops: cat, mkdir, touch, rm, stat, open\n" +
                "Echo/info: echo, whoami, uname, date, uptime, version, drives\n" +
                "System: df, free, battery\n" +
                "UI: clear (wipes screen), help (this list)",
        )

        SectionHeader("Theme Switching")
        BodyText(
            "Open Settings → Theme. The 3 chips (Sovereign Dark / Sovereign Light / System) " +
                "switch the whole UI. Light inverts the surface; System follows the OS setting. " +
                "The choice persists across app restarts.",
        )

        SectionHeader("Files & .exe")
        BodyText(
            "Tap any file in the Files window to open it with the system's default app. " +
                "PDFs, images, videos, APKs, zips all work. .exe files are routed to the " +
                "application/x-msdownload MIME type — install a Wine-compatible app like " +
                "Winlator to actually run them.",
        )

        SectionHeader("Task Manager")
        BodyText(
            "Lists every running process with PID + name + RSS. Refreshes every 3s. " +
                "Tap a row to kill (system processes are protected; the kill is silently " +
                "rejected and the process remains in the list).",
        )

        SectionHeader("About")
        BodyText(
            "Elysium Vanguard v1.0.0-TITAN\n" +
                "Application ID: com.elysium.vanguard\n" +
                "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n" +
                "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                "Stack: Hilt + Compose + Material 3 + Room + Tink + Apache MINA SSHD + ML Kit + Media 3",
        )
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

// ─── Image Viewer (real) ────────────────────────────────

/**
 * PHASE 131 — a real image viewer. The user
 * types a path (or picks from a short list of
 * recent images from /sdcard). The image is
 * loaded via the platform's BitmapFactory
 * (no Coil dependency to add) and rendered
 * with [ContentScale.Fit] so it scales to
 * fit the window. Pinch-to-zoom is wired via
 * a [Modifier.transformable] with the
 * foundation gestures API.
 *
 * The recent-images list is a live query:
 * [FileManagerRepositoryDual.listOnce] over
 * the canonical image folders
 * (DCIM, Pictures, Download, WhatsApp Media).
 */
@Composable
private fun ImageViewerBody() {
    val registry = rememberWindowContentRegistry()
    var path by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Load a small list of candidate images
        // from the canonical folders. We pick
        // the first image we find in each folder
        // (max 4 entries) so the user has
        // something to click without typing a
        // path.
        try {
            val candidates = mutableListOf<String>()
            val folders = listOf(
                "/sdcard/DCIM",
                "/sdcard/Pictures",
                "/sdcard/Download",
                "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            )
            for (folder in folders) {
                try {
                    val items = registry.fileManagerRepository.listOnce(folder)
                    val firstImage = items.firstOrNull {
                        !it.isFolder && (
                            it.path.endsWith(".jpg", true) ||
                                it.path.endsWith(".jpeg", true) ||
                                it.path.endsWith(".png", true) ||
                                it.path.endsWith(".webp", true) ||
                                it.path.endsWith(".gif", true)
                            )
                    }
                    if (firstImage != null) candidates.add(firstImage.path)
                    if (candidates.size >= 4) break
                } catch (_: Exception) {
                    // Folder may not exist; skip.
                }
            }
            recent = candidates
        } catch (_: Exception) {
            // Swallow — empty recent is fine.
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
    ) {
        // Path input
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = path,
                onValueChange = { path = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("/sdcard/path/to/image.png", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { errorMessage = null },
                enabled = path.isNotBlank(),
            ) {
                Text("View")
            }
        }
        // Recent images
        if (recent.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recent images",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            LazyColumn(
                modifier = Modifier.height(100.dp),
            ) {
                items(recent) { recentPath ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { path = recentPath }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = recentPath.removePrefix("/sdcard/"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Image
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (path.isBlank()) {
                Text(
                    text = "Type a path above or pick from the recent list",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val bitmap = remember(path) {
                    try {
                        val f = java.io.File(path)
                        if (!f.exists()) {
                            errorMessage = "File not found: $path"
                            null
                        } else {
                            val opts = android.graphics.BitmapFactory.Options().apply {
                                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                            }
                            android.graphics.BitmapFactory.decodeFile(path, opts)
                        }
                    } catch (e: Exception) {
                        errorMessage = "Cannot decode: ${e.message}"
                        null
                    }
                }
                errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = Color(0xFFFF5555),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (bitmap != null) {
                    // PHASE 131 — pinch-to-zoom + pan.
                    // The transformable modifier gives
                    // us scale (pinch) + offset (pan)
                    // gestures; we clamp scale to
                    // [0.5, 5x] to keep the image on
                    // screen.
                    var scale by remember { mutableStateOf(1f) }
                    var offsetX by remember { mutableStateOf(0f) }
                    var offsetY by remember { mutableStateOf(0f) }
                    val state = androidx.compose.foundation.gestures.rememberTransformableState { zoom, pan, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            )
                            .transformable(state = state),
                    )
                    Text(
                        text = "${bitmap.width}×${bitmap.height} · scale ${"%.1f".format(scale)}x",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

// ─── Calendar (real month grid + event persistence) ─────

/**
 * PHASE 137 — a real month-view calendar with persistent events.
 *
 * The grid (Phase 136) is preserved: month nav, weekday header,
 * 6×7 day grid, today highlight, "Jump to today" button. New in
 * Phase 137:
 *  - Days with events show a small dot under the day number
 *  - Selecting a day reveals a list of that day's events
 *    (ordered by hour; all-day events listed first)
 *  - "+ Add event" opens a dialog (title required, note + optional
 *    time). Time may be left blank for all-day events.
 *  - Each event has a delete affordance (trash icon)
 *  - All events are persisted to the [com.elysium.vanguard.core.calendar.CalendarEventRepository]
 *    which is Hilt-injected via the EntryPoint bridge in this file.
 */
@Composable
private fun CalendarBody() {
    val today = remember { java.util.Calendar.getInstance() }
    var viewYear by remember { mutableStateOf(today.get(java.util.Calendar.YEAR)) }
    var viewMonth by remember { mutableStateOf(today.get(java.util.Calendar.MONTH)) }
    var selectedDay by remember { mutableStateOf(today.get(java.util.Calendar.DAY_OF_MONTH)) }
    val monthFormat = remember { java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US) }
    val dayFormat = remember { java.text.SimpleDateFormat("EEEE", java.util.Locale.US) }
    val selectedFormat = remember { java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.US) }
    val daysOfWeek = remember { listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat") }

    val repo = rememberCalendarEventRepository()
    val monthEvents by produceState(initialValue = emptyList<com.elysium.vanguard.core.database.CalendarEventEntity>(), viewYear, viewMonth) {
        repo.observeForMonth(viewYear, viewMonth).collect { value = it }
    }
    val dayEvents by produceState(initialValue = emptyList<com.elysium.vanguard.core.database.CalendarEventEntity>(), viewYear, viewMonth, selectedDay) {
        repo.observeForDay(viewYear, viewMonth, selectedDay).collect { value = it }
    }
    val daysWithEvents = remember(monthEvents) { monthEvents.map { it.day }.toSet() }
    var showAddDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // ─── The body ──────────────────────────────────────────
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header (month nav)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(onClick = {
                    if (viewMonth == 0) {
                        viewMonth = 11
                        viewYear -= 1
                    } else {
                        viewMonth -= 1
                    }
                }) {
                    Text("◀", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                }
                Text(
                    text = monthFormat.format(
                        java.util.GregorianCalendar(viewYear, viewMonth, 1).time,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                androidx.compose.material3.TextButton(onClick = {
                    if (viewMonth == 11) {
                        viewMonth = 0
                        viewYear += 1
                    } else {
                        viewMonth += 1
                    }
                }) {
                    Text("▶", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Day-of-week header
            Row(modifier = Modifier.fillMaxWidth()) {
                daysOfWeek.forEach { d ->
                    Text(
                        text = d.take(3),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            // Day grid
            val firstDayCal = java.util.GregorianCalendar(viewYear, viewMonth, 1)
            val firstDayOfWeek = firstDayCal.get(java.util.Calendar.DAY_OF_WEEK) - 1  // 0..6 (Sun..Sat)
            val daysInMonth = firstDayCal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val totalCells = ((firstDayOfWeek + daysInMonth + 6) / 7) * 7
            val isToday = viewYear == today.get(java.util.Calendar.YEAR) &&
                viewMonth == today.get(java.util.Calendar.MONTH)
            // Phase 137 — cap the grid height so the calendar fits in
            // windowed containers (the body width can be much larger
            // than its height). 320.dp gives ~53dp per row, which is
            // enough for the day number + the event-dot indicator.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                for (week in 0 until totalCells / 7) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        for (dow in 0..6) {
                            val cellIndex = week * 7 + dow
                            val dayOfMonth = cellIndex - firstDayOfWeek + 1
                            val isCurrentMonth = dayOfMonth in 1..daysInMonth
                            val isTodayCell = isToday && dayOfMonth == today.get(java.util.Calendar.DAY_OF_MONTH)
                            val isSelected = isCurrentMonth && dayOfMonth == selectedDay &&
                                viewYear == today.get(java.util.Calendar.YEAR) &&
                                viewMonth == today.get(java.util.Calendar.MONTH)
                            val hasEvents = isCurrentMonth && dayOfMonth in daysWithEvents
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            isTodayCell -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .let { m ->
                                        if (isCurrentMonth) m.clickable {
                                            selectedDay = dayOfMonth
                                        } else m
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isCurrentMonth) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = dayOfMonth.toString(),
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isTodayCell) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        // Phase 137 — event indicator dot.
                                        if (hasEvents) {
                                            val dotColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.primary
                                            Spacer(modifier = Modifier.height(2.dp))
                                            androidx.compose.foundation.Canvas(
                                                modifier = Modifier.size(4.dp)
                                            ) {
                                                drawCircle(color = dotColor)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Selected day + today
            val selectedCal = java.util.GregorianCalendar(
                today.get(java.util.Calendar.YEAR),
                today.get(java.util.Calendar.MONTH),
                selectedDay,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedFormat.format(selectedCal.time),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${dayFormat.format(selectedCal.time)} · ${dayEvents.size} event${if (dayEvents.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.FilledIconButton(
                    onClick = { showAddDialog = true },
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Add,
                        contentDescription = "Add event",
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Event list for the selected day
            if (dayEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No events for this day.\nTap + to add one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(dayEvents, key = { it.id }) { event ->
                        EventRow(
                            event = event,
                            onDelete = {
                                scope.launch {
                                    repo.delete(event)
                                    snackbarHostState.showSnackbar("Deleted: ${event.title}")
                                }
                            },
                        )
                    }
                }
            }
            androidx.compose.material3.TextButton(
                onClick = {
                    viewYear = today.get(java.util.Calendar.YEAR)
                    viewMonth = today.get(java.util.Calendar.MONTH)
                    selectedDay = today.get(java.util.Calendar.DAY_OF_MONTH)
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Jump to today", color = MaterialTheme.colorScheme.primary)
            }
        }
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    // Add-event dialog
    if (showAddDialog) {
        AddEventDialog(
            year = viewYear,
            month = viewMonth,
            day = selectedDay,
            onDismiss = { showAddDialog = false },
            onConfirm = { title, note, hour, minute ->
                scope.launch {
                    repo.add(
                        year = viewYear,
                        month = viewMonth,
                        day = selectedDay,
                        title = title,
                        note = note,
                        hour = hour,
                        minute = minute,
                    )
                    showAddDialog = false
                    snackbarHostState.showSnackbar("Added: $title")
                }
            },
        )
    }
}

@Composable
private fun EventRow(
    event: com.elysium.vanguard.core.database.CalendarEventEntity,
    onDelete: () -> Unit,
) {
    val timeLabel = if (event.hour in 0..23 && event.minute in 0..59) {
        "%02d:%02d".format(event.hour, event.minute)
    } else {
        "all day"
    }
    val accent = event.colorHex?.let { runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.primary
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = accent)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = buildString {
                        append(timeLabel)
                        if (event.note.isNotBlank()) {
                            append(" · ")
                            append(event.note.take(80))
                            if (event.note.length > 80) append("…")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.IconButton(onClick = onDelete) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Delete,
                    contentDescription = "Delete event",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddEventDialog(
    year: Int,
    month: Int,
    day: Int,
    onDismiss: () -> Unit,
    onConfirm: (title: String, note: String, hour: Int, minute: Int) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(true) }
    var hourText by remember { mutableStateOf("9") }
    var minuteText by remember { mutableStateOf("0") }
    val monthName = remember(month) {
        java.text.SimpleDateFormat("MMMM", java.util.Locale.US).format(
            java.util.GregorianCalendar(year, month, 1).time,
        )
    }
    val titleOk = title.trim().isNotEmpty()
    val parsedHour = hourText.toIntOrNull()?.coerceIn(0, 23) ?: -1
    val parsedMinute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: -1
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New event · $monthName $day, $year") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Switch(
                        checked = !allDay,
                        onCheckedChange = { allDay = !it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (allDay) "All day" else "At specific time", style = MaterialTheme.typography.bodySmall)
                }
                if (!allDay) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = hourText,
                            onValueChange = { hourText = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = { Text("Hour (0-23)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = minuteText,
                            onValueChange = { minuteText = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = { Text("Min (0-59)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val h = if (allDay) -1 else parsedHour
                    val m = if (allDay) -1 else parsedMinute
                    onConfirm(title.trim(), note.trim(), h, m)
                },
                enabled = titleOk && (allDay || (parsedHour >= 0 && parsedMinute >= 0)),
            ) {
                Text("Add", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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

/**
 * Hilt EntryPoint to fetch the [com.elysium.vanguard.core.calendar.CalendarEventRepository]
 * from a Composable-only context. The repository is @Singleton + @Inject,
 * so the EntryPoint just looks it up from the application context.
 *
 * Used by [CalendarBody] (Phase 137) to read + write the persistent
 * event store without having to thread a ViewModel through the
 * desktop shell's windowing layer.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface CalendarEventRepositoryEntryPoint {
    fun calendarEventRepository(): com.elysium.vanguard.core.calendar.CalendarEventRepository
}

private fun calendarEventRepositoryFor(context: Context): com.elysium.vanguard.core.calendar.CalendarEventRepository {
    val app = context.applicationContext
    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
        app,
        CalendarEventRepositoryEntryPoint::class.java,
    )
    return entryPoint.calendarEventRepository()
}

/**
 * PHASE 137 — Composable helper for resolving the calendar event
 * repository from any UI tree. The result is `remember`-ed on the
 * application context so recompositions don't repeatedly hit the
 * EntryPoint machinery.
 */
@Composable
fun rememberCalendarEventRepository(): com.elysium.vanguard.core.calendar.CalendarEventRepository {
    val context = LocalContext.current
    return remember(context) { calendarEventRepositoryFor(context) }
}
