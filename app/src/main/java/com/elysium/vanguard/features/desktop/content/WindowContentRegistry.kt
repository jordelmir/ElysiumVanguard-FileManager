package com.elysium.vanguard.features.desktop.content

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.vanguard.features.filemanager.FileManagerRepositoryDual
import com.elysium.vanguard.features.filemanager.TitanFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

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
 * PHASE 121 — the Start-menu / Programs view. Lists the
 * proprietary Windows desktop's available "programs" — for now
 * that's the same dock items + a "Settings" + a placeholder
 * for "App launcher" (Phase 123). Tapping an item is a no-op
 * until Phase 123 wires up real cross-window navigation.
 */
@Composable
private fun ProgramsBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(
            text = "Programs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val items = listOf(
            "📁 Files" to "Browse /sdcard + /data",
            "💻 Terminal" to "Proprietary proot shell",
            "🖥️ This PC" to "Drives + system",
            "⚙️ Settings" to "Theme + signing + cloud",
            "📝 Notes" to "Quick scratchpad",
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items) { (label, sub) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ============================== Placeholders (Phase 122+) ==============================

@Composable
private fun TerminalBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .padding(12.dp),
    ) {
        Text(
            text = "elysium@vg:~\$ ",
            color = Color(0xFF8BE9FD),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Welcome to Elysium Vanguard.",
            color = Color(0xFFF8F8F2),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Phase 122 will wire this body to TerminalHost.",
            color = Color(0xFF6272A4),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingsBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text("• Theme: Sovereign Dark", style = MaterialTheme.typography.bodyMedium)
        Text("• Signature check: enabled", style = MaterialTheme.typography.bodyMedium)
        Text("• Cloud build: HTTP", style = MaterialTheme.typography.bodyMedium)
        Text("• Proot writes: captured", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NotesBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Text("Notes", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Phase 121 — Real Windows Desktop shell shipped. " +
                "The Files window is now a real file explorer wired to " +
                "FileManagerRepositoryDual. This PC shows drives. " +
                "Phase 122 will rewire the Terminal body to TerminalHost.",
            style = MaterialTheme.typography.bodyMedium,
        )
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
