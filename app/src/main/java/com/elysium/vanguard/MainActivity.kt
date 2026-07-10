@file:SuppressLint("UnsafeOptInUsageError")

package com.elysium.vanguard

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.elysium.vanguard.features.filemanager.FileManagerViewModel
import com.elysium.vanguard.features.filemanager.FileManagerEvent
import com.elysium.vanguard.features.player.NativeMediaPlayer
import com.elysium.vanguard.features.viewer.HighFidelityImageViewer
import com.elysium.vanguard.features.viewer.IntegratedDocumentViewer
import com.elysium.vanguard.ui.theme.TitanTheme
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import kotlinx.coroutines.launch
import com.elysium.vanguard.core.util.FileOpenerUtil
import com.elysium.vanguard.core.saf.SafTreeManager
import javax.inject.Inject

/**
 * PROJECT TITAN: ELYSIUM VANGUARD
 * The entry point for the next-generation sovereign file manager.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * PHASE 8.3 — Hilt-injected SAF tree manager. We hold it at the
     * Activity level so the picker callback (registered in onCreate via
     * [registerForActivityResult]) can call [SafTreeManager.onTreePicked]
     * before any screen reads the granted URI.
     */
    @Inject lateinit var safTreeManager: SafTreeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkAndRequestStorageRoot()

        setContent {
            val fileManagerViewModel: FileManagerViewModel = hiltViewModel()
            val galleryViewModel: com.elysium.vanguard.features.gallery.GalleryViewModel = hiltViewModel()
            val musicHubViewModel: com.elysium.vanguard.features.player.MusicHubViewModel = hiltViewModel()
            val navController = rememberNavController()

            TitanTheme {
                NavHost(
                    navController = navController,
                    startDestination = "splash"
                ) {
                    composable("splash") {
                        com.elysium.vanguard.features.splash.SplashScreen(
                            onNavigateToDashboard = {
                                navController.navigate("dashboard") {
                                    popUpTo("splash") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("dashboard") {
                        com.elysium.vanguard.features.dashboard.DashboardScreen(
                            onNavigateToStorage = { navController.navigate("file_manager") },
                            onNavigateToGallery = { navController.navigate("gallery") },
                            onNavigateToMusic = { navController.navigate("music_hub") },
                            onNavigateToRuntime = { navController.navigate("runtime") },
                            onNavigateToTerminal = { navController.navigate("terminal") }
                        )
                    }
                    composable("file_manager") {
                        com.elysium.vanguard.features.filemanager.FileManagerScreen(
                            viewModel = fileManagerViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToVault = { navController.navigate("vault") },
                            onNavigateToTrash = { navController.navigate("trash") },
                            onNavigateToSearch = { navController.navigate("search") },
                            onNavigateToDuplicates = { navController.navigate("duplicates") },
                            onNavigateToAnalyzer = { navController.navigate("analyzer") },
                            onNavigateToServer = { navController.navigate("local_server") },
                            onNavigateToSftp = { navController.navigate("sftp_server") },
                            onNavigateToDualPane = { navController.navigate("dual_pane") },
                            onNavigateToOcr = { navController.navigate("ocr") },
                            onNavigateToAutoTag = { navController.navigate("auto_tag") }
                        )
                    }
                    composable("local_server") {
                        com.elysium.vanguard.features.server.LocalServerScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("sftp_server") {
                        com.elysium.vanguard.features.sftp.SftpScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("dual_pane") {
                        com.elysium.vanguard.features.dualpane.DualPaneScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("ocr") {
                        com.elysium.vanguard.features.ocr.OcrScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("auto_tag") {
                        com.elysium.vanguard.features.tagging.AutoTagScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // PHASE 9.6.1 — Sovereign Linux runtime entry point.
                    // First working delivery of an in-app terminal backed
                    // by a child /system/bin/sh process.
                    composable("terminal") {
                        com.elysium.vanguard.features.runtime.terminal.TerminalScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // PHASE 9.6.3 — Distro-rooted terminal. Same screen,
                    // but the VM resolves a launcher against the named
                    // distro and builds the shell command line through
                    // it. The plain `terminal` route stays for the
                    // 9.6.1 local-shell fallback.
                    composable(
                        route = "terminal_distro/{distroId}",
                        arguments = listOf(
                            navArgument("distroId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        @Suppress("UNUSED_VARIABLE")
                        val unused = backStackEntry.arguments
                        com.elysium.vanguard.features.runtime.terminal.TerminalScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // PHASE 9.6.2 — Sovereign runtime catalog (install
                    // distros, manage installed rootfs).
                    composable("runtime") {
                        com.elysium.vanguard.features.runtime.RuntimeScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTerminal = { navController.navigate("terminal") },
                            onOpenDistro = { distroId ->
                                val encoded = URLEncoder.encode(distroId, StandardCharsets.UTF_8.toString())
                                navController.navigate("terminal_distro/$encoded")
                            },
                            onInspectDistro = { distroId ->
                                val encoded = URLEncoder.encode(distroId, StandardCharsets.UTF_8.toString())
                                navController.navigate("runtime_inspect/$encoded")
                            },
                            onOpenDesktop = { distroId ->
                                val encoded = URLEncoder.encode(distroId, StandardCharsets.UTF_8.toString())
                                navController.navigate("runtime_desktop/$encoded")
                            },
                            onCustomRootfs = { navController.navigate("runtime_custom") }
                        )
                    }
                    // PHASE 9.6.3.2 — Inspect a single installed distro
                    // (Files / OS / Packages / Snapshots tabs).
                    composable(
                        route = "runtime_inspect/{distroId}",
                        arguments = listOf(
                            navArgument("distroId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        @Suppress("UNUSED_VARIABLE")
                        val unused = backStackEntry.arguments
                        com.elysium.vanguard.features.runtime.inspect.RuntimeInspectScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // PHASE 9.6.3.2 — Custom rootfs URL install.
                    composable("runtime_custom") {
                        com.elysium.vanguard.features.runtime.custom.RuntimeCustomScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // PHASE 9.6.5 — Linux desktop (VNC stub + app launcher catalog).
                    composable(
                        route = "runtime_desktop/{distroId}",
                        arguments = listOf(
                            navArgument("distroId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        @Suppress("UNUSED_VARIABLE")
                        val unused = backStackEntry.arguments
                        com.elysium.vanguard.features.runtime.desktop.LinuxDesktopScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "metadata/{key}/{name}",
                        arguments = listOf(
                            navArgument("key") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        // The FileMetadataViewModel reads "key" and "name" from SavedStateHandle,
                        // which Compose Navigation populates from these nav arguments.
                        @Suppress("UNUSED_VARIABLE")
                        val unused = backStackEntry.arguments  // documented; Hilt reads from SavedStateHandle
                        com.elysium.vanguard.features.metadata.FileMetadataScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("vault") {
                        com.elysium.vanguard.features.vault.VaultScreen(
                            onBack = { navController.popBackStack() },
                            onOpenFile = { _, tmp -> handleOpenFile(navController, tmp.absolutePath, "*/*") },
                            onNavigateToMetadata = { key: String, name: String ->
                                val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.toString())
                                val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.toString())
                                navController.navigate("metadata/$encodedKey/$encodedName")
                            }
                        )
                    }
                    composable("trash") {
                        com.elysium.vanguard.features.trash.TrashScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("search") {
                        com.elysium.vanguard.features.search.SearchScreen(
                            onBack = { navController.popBackStack() },
                            onResultClick = { path -> handleOpenFile(navController, path, "*/*") }
                        )
                    }
                    composable("duplicates") {
                        com.elysium.vanguard.features.duplicates.DuplicatesScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("analyzer") {
                        com.elysium.vanguard.features.analyzer.StorageAnalyzerScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "conflicts/{sources}/{dest}",
                        arguments = listOf(
                            navArgument("sources") { type = NavType.StringType },
                            navArgument("dest") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        @Suppress("UNUSED_VARIABLE")
                        val unused = backStackEntry.arguments
                        com.elysium.vanguard.features.conflict.ConflictResolutionScreen(
                            onBack = { navController.popBackStack() },
                            onApplied = { _ -> navController.popBackStack() }
                        )
                    }
                    composable("smart_folders") {
                        com.elysium.vanguard.features.smartfolders.SmartFoldersScreen(
                            onBack = { navController.popBackStack() },
                            onOpenFolder = { folder ->
                                navController.navigate("smart_folder/${folder.id}")
                            }
                        )
                    }
                    composable(
                        route = "smart_folder/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType })
                    ) { backStackEntry ->
                        // id arrives in the SavedStateHandle via Hilt; the VM reads it directly.
                        @Suppress("UNUSED_VARIABLE")
                        val unused = backStackEntry.arguments
                        com.elysium.vanguard.features.smartfolders.SmartFolderResultsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenFile = { path -> handleOpenFile(navController, path, "*/*") }
                        )
                    }
                    composable("gallery") {
                        com.elysium.vanguard.features.gallery.GalleryScreen(
                            viewModel = galleryViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToAlbum = { albumName ->
                                val encodedName = URLEncoder.encode(albumName, StandardCharsets.UTF_8.toString())
                                navController.navigate("album_detail/$encodedName")
                            }
                        )
                    }
                    composable(
                        route = "player/{path}/{mimeType}",
                        arguments = listOf(
                            navArgument("path") { type = NavType.StringType },
                            navArgument("mimeType") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", StandardCharsets.UTF_8.toString())
                        val mimeType = URLDecoder.decode(backStackEntry.arguments?.getString("mimeType") ?: "", StandardCharsets.UTF_8.toString())
                        NativeMediaPlayer(
                            filePath = path,
                            mimeType = mimeType,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "viewer_image/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", StandardCharsets.UTF_8.toString())
                        HighFidelityImageViewer(
                            filePath = path,
                            onEdit = { navController.navigate("editor_image/${URLEncoder.encode(path, StandardCharsets.UTF_8.toString())}") },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "editor_image/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", StandardCharsets.UTF_8.toString())
                        com.elysium.vanguard.features.viewer.BasicImageEditor(
                            filePath = path,
                            onSave = { navController.popBackStack() },
                            onCancel = { navController.popBackStack() }
                        )
                    }
                    composable("music_hub") {
                        com.elysium.vanguard.features.player.MusicHubScreen(
                            viewModel = musicHubViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "viewer_music/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", StandardCharsets.UTF_8.toString())
                        // We can transition to music_hub and auto-play the track
                        LaunchedEffect(path) {
                            val track = musicHubViewModel.songs.value.find { it.path == path }
                            if (track != null) {
                                musicHubViewModel.playTrack(track)
                            } else {
                                // Basic fallback for external files not yet in library
                                musicHubViewModel.playTrack(com.elysium.vanguard.features.player.MusicTrack(
                                    id = System.currentTimeMillis(),
                                    name = File(path).name,
                                    path = path,
                                    mimeType = "audio/*",
                                    album = "Unknown",
                                    artist = "Unknown",
                                    duration = 0,
                                    dateModified = 0
                                ))
                            }
                            navController.navigate("music_hub") {
                                popUpTo("viewer_music/{path}") { inclusive = true }
                            }
                        }
                    }
                    composable(
                        route = "viewer_pdf/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", StandardCharsets.UTF_8.toString())
                        com.elysium.vanguard.features.viewer.SovereignPdfViewer(
                            filePath = path,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "viewer_doc/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", StandardCharsets.UTF_8.toString())
                        IntegratedDocumentViewer(
                            filePath = path,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "viewer_elysium/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", StandardCharsets.UTF_8.toString())
                        com.elysium.vanguard.features.viewer.ElysiumDocumentViewer(
                            file = java.io.File(path),
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "editor_text/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", StandardCharsets.UTF_8.toString())
                        com.elysium.vanguard.features.editor.TextEditorScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "editor_md/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val path = URLDecoder.decode(backStackEntry.arguments?.getString("path") ?: "", StandardCharsets.UTF_8.toString())
                        com.elysium.vanguard.features.editor.MarkdownEditorScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "editor_crdt/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        com.elysium.vanguard.features.crdteditor.CrdtDocumentEditorScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "album_detail/{albumName}",
                        arguments = listOf(navArgument("albumName") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val albumName = URLDecoder.decode(backStackEntry.arguments?.getString("albumName") ?: "", StandardCharsets.UTF_8.toString())
                        com.elysium.vanguard.features.gallery.AlbumDetailScreen(
                            albumName = albumName,
                            viewModel = galleryViewModel,
                            onBack = { navController.popBackStack() },
                            onMediaClick = { media ->
                                galleryViewModel.openMedia(media)
                            }
                        )
                    }
                }
                
                LaunchedEffect(Unit) {
                    // Collect FileManager Events
                    launch {
                        fileManagerViewModel.events.collect { event ->
                            when (event) {
                                is FileManagerEvent.OpenFile -> {
                                    handleOpenFile(navController, event.file.path, event.file.mimeType)
                                }
                                is FileManagerEvent.ShareFile -> {
                                    FileOpenerUtil.shareFile(this@MainActivity, File(event.file.path))
                                }
                                is FileManagerEvent.Snackbar -> {
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        event.message,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    // Collect Gallery Events
                    launch {
                        galleryViewModel.events.collect { event ->
                            when (event) {
                                is com.elysium.vanguard.features.gallery.GalleryEvent.OpenMedia -> {
                                    handleOpenFile(navController, event.media.path, event.media.mimeType)
                                }
                                is com.elysium.vanguard.features.gallery.GalleryEvent.ShareMedia -> {
                                    FileOpenerUtil.shareFile(this@MainActivity, File(event.media.path))
                                }
                                is com.elysium.vanguard.features.gallery.GalleryEvent.EditMedia -> {
                                    FileOpenerUtil.editFile(this@MainActivity, File(event.media.path))
                                }
                                is com.elysium.vanguard.features.gallery.GalleryEvent.SetWallpaper -> {
                                    FileOpenerUtil.setWallpaper(this@MainActivity, File(event.media.path))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleOpenFile(navController: androidx.navigation.NavController, path: String, mimeType: String) {
        val file = File(path)
        if (!file.exists()) return

        // RE-IDENTIFY MIME TYPE FOR ACCURACY (Fixes mislabeled WhatsApp documents)
        val detectedMime = if (mimeType == "application/octet-stream" || mimeType == "*/*" || mimeType == "resource/folder") {
             FileOpenerUtil.getMimeTypeFromMagicBytes(file)
        } else mimeType

        val encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8.toString())
        val encodedMime = URLEncoder.encode(detectedMime, StandardCharsets.UTF_8.toString())
        
        val fileName = path.substringAfterLast("/")
        
        when {
            detectedMime.startsWith("audio/") -> {
                navController.navigate("viewer_music/$encodedPath")
            }
            detectedMime.startsWith("video/") -> {
                navController.navigate("player/$encodedPath/$encodedMime")
            }
            detectedMime.startsWith("image/") -> {
                navController.navigate("viewer_image/$encodedPath")
            }
            detectedMime == "application/pdf" -> {
                navController.navigate("viewer_pdf/$encodedPath")
            }
            detectedMime.startsWith("text/") || 
            detectedMime == "application/vnd.oasis.opendocument.text" ||
            detectedMime == "text/csv" ||
            detectedMime == "application/rtf" ||
            detectedMime == "application/msword" ||
            detectedMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            detectedMime == "application/vnd.ms-excel" ||
            detectedMime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            detectedMime == "application/vnd.ms-powerpoint" ||
            detectedMime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
            (detectedMime == "application/octet-stream" && fileName.startsWith("doc-", ignoreCase = true)) ||
            path.endsWith(".csv", ignoreCase = true) ||
            path.endsWith(".rtf", ignoreCase = true) ||
            path.endsWith(".html", ignoreCase = true) ||
            path.endsWith(".htm", ignoreCase = true) ||
            path.endsWith(".doc", ignoreCase = true) ||
            path.endsWith(".docx", ignoreCase = true) ||
            path.endsWith(".xls", ignoreCase = true) ||
            path.endsWith(".xlsx", ignoreCase = true) ||
            path.endsWith(".ppt", ignoreCase = true) ||
            path.endsWith(".pptx", ignoreCase = true) ||
            path.endsWith(".epub", ignoreCase = true) ||
            path.endsWith(".mobi", ignoreCase = true) ||
            path.endsWith(".txt", ignoreCase = true) -> {
                navController.navigate("viewer_doc/$encodedPath")
            }
            // Extension-based audio fallback
            path.endsWith(".mp3", true) || path.endsWith(".aac", true) || 
            path.endsWith(".m4p", true) || path.endsWith(".flac", true) || 
            path.endsWith(".wav", true) || path.endsWith(".ogg", true) -> {
                navController.navigate("viewer_music/$encodedPath")
            }
            else -> {
                val context = this
                try {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, detectedMime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    // ONLY use chooser as a last resort for unknown types
                    context.startActivity(Intent.createChooser(intent, "Open with"))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * PHASE 8.3 — SAF picker.
     *
     * Replaces the deprecated `MANAGE_EXTERNAL_STORAGE` flow (which Play
     * Store blocks and which only works with the permission declared in
     * the manifest, which we don't). We ask the user to grant us a folder
     * via the system SAF picker. The granted URI is persistable (survives
     * reboots) and scopes all file operations to that tree.
     *
     * Pre-Android 11: standard READ/WRITE_EXTERNAL_STORAGE permissions are
     * still the only option, so we keep that flow for those devices.
     */
    private val safPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // SafTreeManager.onTreePicked updates its StateFlow. The
            // FileManagerViewModel observes that flow (see init {}) and
            // automatically reloads the root — no direct call needed here.
            safTreeManager.onTreePicked(uri)
        }
    }

    private fun checkAndRequestStorageRoot() {
        // Check if a SAF tree is already granted. If so, the file manager
        // is ready to use. This works on every API level we support.
        if (safTreeManager.hasUsableTree) return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+: prompt the user to grant a folder via SAF. We
            // do NOT request MANAGE_EXTERNAL_STORAGE; the SAF tree is the
            // modern, Play-Store-friendly way to access user files.
            showSafPickerPrompt()
        } else {
            // Android 10 and below: legacy permissions.
            val permissions = mutableListOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val toRequest = permissions.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (toRequest.isNotEmpty()) {
                androidx.core.app.ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1001)
            }
        }
    }

    private fun showSafPickerPrompt() {
        // Use the platform AlertDialog — `MainActivity` is a
        // `ComponentActivity` themed as `Theme.Material.NoActionBar`
        // (AOSP Material, not AppCompat / MaterialComponents), so
        // `androidx.appcompat.app.AlertDialog.Builder` throws at
        // `.show()` with "You need to use a Theme.AppCompat theme".
        // The platform dialog has no theme requirements and works
        // against the activity's existing window decor.
        android.app.AlertDialog.Builder(this)
            .setTitle("Connect a folder")
            .setMessage(
                "Elysium Vanguard needs access to your files. " +
                    "Pick a folder (e.g. Documents, Downloads) and the app will " +
                    "work only inside that folder. You can disconnect any time " +
                    "from Settings."
            )
            .setPositiveButton("Pick folder") { _, _ ->
                safPickerLauncher.launch(null)
            }
            .setNegativeButton("Use app folder only") { _, _ ->
                // User declined. App still works with its own external files dir.
            }
            .setCancelable(false)
            .show()
    }
}
