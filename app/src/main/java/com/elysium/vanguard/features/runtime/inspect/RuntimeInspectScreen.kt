package com.elysium.vanguard.features.runtime.inspect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.distros.RootfsIntrospectorSnapshot
import com.elysium.vanguard.core.runtime.distros.introspector.InstalledPackage
import com.elysium.vanguard.core.runtime.distros.introspector.OsRelease
import com.elysium.vanguard.core.runtime.distros.introspector.RootfsEntry
import com.elysium.vanguard.core.runtime.distros.snapshot.DistroSnapshot

/**
 * PHASE 9.6.3.2 — Inspect a single installed distro.
 *
 * Four tabs:
 *   - **Files**    : top-level rootfs entries (depth 3) from the introspector
 *   - **OS**       : `/etc/os-release` parsed
 *   - **Packages** : dpkg / apk / pacman list
 *   - **Snapshots**: clones we've made; tap to remove; tap "Snapshot" to create a new one
 *
 * The screen is intentionally a thin shell: state resolution lives in
 * [RuntimeInspectViewModel] so we never stall the UI thread reading the
 * filesystem.
 *
 * Phase 9.6.3.2 — first build; intentionally minimal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeInspectScreen(
    onBack: () -> Unit,
    viewModel: RuntimeInspectViewModel = hiltViewModel()
) {
    val installationState = viewModel.installation.collectAsState()
    val installation: DistroInstallation? = installationState.value
    val snapshotState = viewModel.snapshot.collectAsState()
    val snapshot: RootfsIntrospectorSnapshot? = snapshotState.value
    val snapshotsState = viewModel.snapshots.collectAsState()
    val snapshots: List<DistroSnapshot> = snapshotsState.value
    val selectedTabState = viewModel.selectedTab.collectAsState()
    val selectedTab = selectedTabState.value
    val isBusyState = viewModel.isBusy.collectAsState()
    val isBusy: Boolean = isBusyState.value

    Scaffold(
        containerColor = Color(0xFF0B0D10),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Inspect · ${installation?.distro?.displayName ?: "—"}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Color(0xFFE4E7EB)
                        )
                        Text(
                            text = snapshot?.summary ?: "loading…",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF8B949E)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFE4E7EB)
                        )
                    }
                },
                actions = {
                    if (selectedTab == 3) {
                        IconButton(onClick = { viewModel.captureSnapshot() }) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = "Create snapshot",
                                tint = Color(0xFFE4E7EB)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F1115)
                )
            )
        }
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF0F1115),
                contentColor = Color(0xFFE4E7EB)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { viewModel.setTab(0) },
                    text = { Text("Files", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { viewModel.setTab(1) },
                    text = { Text("OS", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { viewModel.setTab(2) },
                    text = { Text("Packages", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { viewModel.setTab(3) },
                    text = {
                        Text(
                            "Snapshots",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                )
            }

            if (snapshot == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (installation == null) {
                        Text(
                            "no distro installed at this id",
                            color = Color(0xFFFF6E6E),
                            fontFamily = FontFamily.Monospace
                        )
                    } else if (isBusy) {
                        CircularProgressIndicator(color = Color(0xFF61AFEF))
                    } else {
                        Text(
                            "unhealthy installation; re-install this distro",
                            color = Color(0xFFFF6E6E),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                return@Column
            }

            val snapshotValue = snapshot ?: return@Column

            when (selectedTab) {
                0 -> FilesTab(snapshotValue.entries)
                1 -> OsReleaseTab(snapshotValue.osRelease)
                2 -> PackagesTab(snapshotValue.packages)
                3 -> SnapshotsTab(
                    snapshots = snapshots,
                    onRemove = viewModel::removeSnapshot
                )
            }
        }
    }
}

@Composable
private fun FilesTab(entries: List<RootfsEntry>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { it.relativePath }) { entry ->
            EntryRow(entry)
        }
    }
}

@Composable
private fun EntryRow(entry: RootfsEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${if (entry.isDirectory) "📁" else if (entry.isSymlink) "🔗" else "📄"} ${entry.displayName}",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Color(0xFFE4E7EB)
            )
            Text(
                text = "path: ${entry.relativePath.ifEmpty { "/" }}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF8B949E),
                modifier = Modifier.padding(top = 2.dp)
            )
            if (!entry.isDirectory && entry.sizeBytes > 0) {
                Text(
                    text = "${entry.sizeBytes} B",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF8B949E)
                )
            }
        }
    }
}

@Composable
private fun OsReleaseTab(release: OsRelease) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "pretty name: ${release.prettyName ?: release.name ?: "—"}",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE4E7EB)
        )
        KeyValueLine("name", release.name)
        KeyValueLine("version", release.version)
        KeyValueLine("version_id", release.versionId)
        KeyValueLine("id", release.id)
        KeyValueLine("home_url", release.homeUrl)
    }
}

@Composable
private fun KeyValueLine(label: String, value: String?) {
    Text(
        text = "$label: ${value ?: "—"}",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Color(0xFF8B949E)
    )
}

@Composable
private fun PackagesTab(packages: List<InstalledPackage>) {
    if (packages.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "no package metadata available in this rootfs",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF8B949E)
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(packages, key = { it.name + (it.version ?: "") }) { pkg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = pkg.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE4E7EB)
                    )
                    if (!pkg.version.isNullOrBlank()) {
                        Text(
                            text = pkg.version,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF61AFEF)
                        )
                    }
                    if (!pkg.description.isNullOrBlank()) {
                        Text(
                            text = pkg.description,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF8B949E),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotsTab(
    snapshots: List<DistroSnapshot>,
    onRemove: (String) -> Unit
) {
    if (snapshots.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "no snapshots yet",
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8B949E)
                )
                Text(
                    "tap the 💾 icon above to capture",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(snapshots, key = { it.id }) { snap ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = snap.id,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE4E7EB),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "${snap.bytesCopied} B on disk · ${snap.sourceId}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF8B949E),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    IconButton(onClick = { onRemove(snap.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove snapshot",
                            tint = Color(0xFFFF6E6E)
                        )
                    }
                }
            }
        }
    }
}
