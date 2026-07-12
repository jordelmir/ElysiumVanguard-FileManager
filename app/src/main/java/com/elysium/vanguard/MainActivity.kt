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
import com.elysium.vanguard.ui.theme.ElysiumTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
     * PHASE 8.3 — Hilt-injected SAF tree manager. Kept for OPTIONAL advanced
     * use (the user can still grant a scoped tree from Settings → Advanced),
     * but no longer gates the app at first launch.
     */
    @Inject lateinit var safTreeManager: SafTreeManager
    @Inject lateinit var paletteManager: com.elysium.vanguard.core.palette.PaletteManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // PHASE 10.2: no more SAF picker prompt. The app launches into the
        // dashboard / file manager immediately. If Android 11+ is hiding
        // /sdcard behind MANAGE_EXTERNAL_STORAGE, the FileManagerScreen
        // shows a single "Grant full access" button that deep-links to
        // Settings → Special access → All files access. That's the only
        // screen the user can be sent to from inside the app.

        setContent {
            val fileManagerViewModel: FileManagerViewModel = hiltViewModel()
            val galleryViewModel: com.elysium.vanguard.features.gallery.GalleryViewModel = hiltViewModel()
            val musicHubViewModel: com.elysium.vanguard.features.player.MusicHubViewModel = hiltViewModel()
            val navController = rememberNavController()
            // PHASE 10.8 — the live palette from PaletteManager
            // (a Hilt singleton). The M3 colorScheme inside
            // ElysiumTheme is rebuilt whenever the user picks
            // a new color in the customization screen, so every
            // M3 widget (Button, TextField, etc.) reacts
            // immediately.
            val palette by paletteManager.current.collectAsState()

            ElysiumTheme(palette = palette) {
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
                            onNavigateToTerminal = { navController.navigate("terminal") },
                            onNavigateToWord = { navController.navigate("editor_word_new") },
                            onNavigateToSheet = { navController.navigate("editor_sheet_new") },
                            onNavigateToColors = { navController.navigate("color_customization") }
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
                    // Linux workspace: a capability-accurate graphical route
                    // that never substitutes a fake VNC bitmap for Linux.
                    composable(
                        route = "runtime_desktop/{distroId}",
                        arguments = listOf(
                            navArgument("distroId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val distroId = backStackEntry.arguments?.getString("distroId").orEmpty()
                        com.elysium.vanguard.features.runtime.desktop.LinuxDesktopScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTerminal = {
                                val encoded = URLEncoder.encode(distroId, StandardCharsets.UTF_8.toString())
                                navController.navigate("terminal_distro/$encoded")
                            }
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
                    // PHASE 10.5 — Elysium Word editor. The full Word
                    // clone: font, typography, spacing, alignment,
                    // lists, headings, block quotes, code blocks,
                    // tables, .docx import/export.
                    composable(
                        route = "editor_word/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        com.elysium.vanguard.features.word.WordEditorScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // PHASE 10.5 — Elysium Word editor for a fresh
                    // document (no path).
                    composable("editor_word_new") {
                        com.elysium.vanguard.features.word.WordEditorScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // PHASE 10.6 — Elysium Sheet editor. The full
                    // Excel clone: cells, formulas, formatting, charts,
                    // multiple sheets, .xlsx import/export.
                    composable(
                        route = "editor_sheet/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { backStackEntry ->
                        com.elysium.vanguard.features.sheet.SheetEditorScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("editor_sheet_new") {
                        com.elysium.vanguard.features.sheet.SheetEditorScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // PHASE 10.8 — Color customization. Lets the user
                    // change primary/secondary/tertiary/quaternary
                    // colors live, pick from 5 visual styles (NEON,
                    // PHOSPHORESCENT, METALLIC, COMBINED, DIFFUSED),
                    // and save the result as a named palette.
                    composable("color_customization") {
                        com.elysium.vanguard.features.customization.ColorCustomizationScreen(
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
     * PHASE 10.2 — the only "restriction" left is the OS-level All-files-access
     * toggle on Android 11+. We deep-link the user straight to that screen
     * so they can flip it on in one tap. No SAF picker, no first-launch
     * dialog, no permission prompt.
     *
     * `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` is documented
     * (API 30+) and works on every device we ship to. If the OEM hid the
     * screen (rare), we fall back to the app's generic settings page.
     */
    fun openAllFilesAccessSettings() {
        val primary = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            android.net.Uri.parse("package:${packageName}")
        )
        val fallback = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${packageName}")
        )
        try {
            startActivity(primary)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivity(fallback)
            } catch (_: android.content.ActivityNotFoundException) {
                // OEM is hostile. Nothing we can do - at least we tried.
            }
        }
    }

    /**
     * True when the app holds the OS-level "All files access" toggle.
     * On API < 30 the answer is always true (legacy storage works out of
     * the box with `requestLegacyExternalStorage="true"` in the manifest).
     */
    fun hasFullStorageAccess(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return true
        }
        return try {
            @Suppress("DEPRECATION")
            android.os.Environment.isExternalStorageManager()
        } catch (_: Throwable) {
            // Some emulators throw - treat as not-granted and surface the
            // Settings deep-link from the UI.
            false
        }
    }
}
