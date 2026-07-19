package com.elysium.vanguard.features.desktop.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Phase 78 — the registry of mock window
 * content. The real apps (terminal, file
 * manager, settings) are not yet wired into
 * the desktop shell; Phase 78 ships a
 * **placeholder** content set so the
 * windowing surface can be demoed
 * end-to-end.
 *
 * The registry maps a [iconKey] (the
 * [com.elysium.vanguard.features.desktop.model.DesktopWindow.iconKey]
 * field) to a `(@Composable () -> Unit)`
 * that renders the window's body.
 *
 * The registry is a `Map<String,
 * WindowContent>` where
 * [WindowContent] carries both the
 * body composable AND the icon vector
 * (so the dock + the title bar share
 * one source of truth for the icon).
 *
 * **Future phases** replace the placeholder
 * bodies with real apps (the terminal
 * becomes a real proot session; the file
 * manager becomes a real file manager;
 * the settings app becomes a real
 * settings app). The shell itself does
 * not change.
 */
data class WindowContent(
    val icon: ImageVector,
    val body: @Composable () -> Unit,
)

object WindowContentRegistry {

    /**
     * The map of `iconKey → WindowContent`.
     * The keys are stable: the dock item +
     * the window both reference the same
     * key, so the dock's icon matches the
     * window's title bar icon.
     */
    val byIconKey: Map<String, WindowContent> = mapOf(
        "terminal" to WindowContent(
            icon = Icons.Filled.Terminal,
            body = { TerminalBody() },
        ),
        "files" to WindowContent(
            icon = Icons.Filled.Folder,
            body = { FilesBody() },
        ),
        "settings" to WindowContent(
            icon = Icons.Filled.Settings,
            body = { SettingsBody() },
        ),
        "notes" to WindowContent(
            icon = Icons.Filled.Description,
            body = { NotesBody() },
        ),
    )

    /**
     * Resolve a [iconKey] to a
     * [WindowContent]. Returns a
     * placeholder when the key is unknown
     * (so a window with an unexpected
     * `iconKey` renders something instead
     * of crashing).
     */
    fun resolve(iconKey: String): WindowContent = byIconKey[iconKey]
        ?: WindowContent(
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
}

// --- Placeholder bodies ---

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
            text = "Type 'help' to discover the platform.",
            color = Color(0xFF6272A4),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FilesBody() {
    val items = listOf(
        "📁 /workspaces",
        "📁 /snapshots",
        "📁 /marketplace",
        "📄 README.md",
        "📄 PHASE_78.md",
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
            text = "Phase 78 — Real Desktop Shell shipped. " +
                "The windowing surface is now a real Compose " +
                "windowing implementation, not a text list.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
