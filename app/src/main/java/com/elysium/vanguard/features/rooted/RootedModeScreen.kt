package com.elysium.vanguard.features.rooted

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elysium.vanguard.core.runtime.distros.launcher.CgroupSpec
import com.elysium.vanguard.core.runtime.distros.launcher.NamespaceSpec
import com.elysium.vanguard.core.runtime.distros.launcher.RootProvider
import com.elysium.vanguard.core.runtime.distros.launcher.RootStatus

/**
 * PHASE 102 — the **rooted mode settings** screen.
 *
 * Shown from the Runtime / Distro settings menu when
 * the user wants to opt in to the `unshare + chroot +
 * cgexec` launcher. The screen is deliberately
 * "developer-flavored" — the user is opting in to a
 * feature that requires `su` and modifies the device's
 * process tree.
 *
 * The screen renders four sections:
 *
 *  1. **Status** — what the [RootedModeProbe] sees
 *     right now (rooted? which provider? unshare?
 *     cgexec? cgroup v2?).
 *  2. **Toggle** — the master "Rooted Mode" switch.
 *  3. **Namespaces** — the opt-in `user` namespace
 *     (gated on kernel `unprivileged_userns_clone`).
 *  4. **Cgroups** — the opt-in cgroup v2 limits
 *     (gated on the probe's `canHonorCgroupSpec`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootedModeScreen(
    onBack: () -> Unit,
    viewModel: RootedModeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rooted Mode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-probe")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusSection(state.status)

            MasterToggleCard(
                enabled = state.rootedModeEnabled,
                canEnable = state.status?.canLaunchRooted == true,
                onToggle = { viewModel.onRootedModeToggle(it) },
            )

            NamespaceCard(
                spec = state.namespaceSpec,
                kernelSupportsUserNs = state.status?.unprivilegedUserNsClone == true,
                onUserNsToggle = { viewModel.onUserNamespaceToggle(it) },
            )

            CgroupCard(
                spec = state.cgroupSpec,
                cgroupV2 = state.status?.cgroupVersion == 2,
                cgexecAvailable = state.status?.cgexecAvailable == true,
                onApplyBackground = { viewModel.onApplyBackgroundCgroup() },
                onReset = { viewModel.onResetCgroup() },
            )
        }
    }
}

@Composable
private fun StatusSection(status: RootStatus?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Device status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (status == null) {
                Text("Probing...", style = MaterialTheme.typography.bodyMedium)
            } else {
                StatusRow("Rooted", status.isRooted.toString(),
                    highlight = status.isRooted)
                StatusRow("Provider", status.provider.displayName())
                StatusRow("unshare(1)", status.unshareAvailable.toString(),
                    highlight = status.unshareAvailable)
                StatusRow("cgexec(1)", status.cgexecAvailable.toString(),
                    highlight = status.cgexecAvailable)
                StatusRow("cgroup v2", status.cgroupVersion?.toString() ?: "unknown",
                    highlight = status.cgroupVersion == 2)
                StatusRow("user ns", status.unprivilegedUserNsClone?.toString() ?: "unknown",
                    highlight = status.unprivilegedUserNsClone == true)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Diagnostics: ${status.diagnostics}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    highlight: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MasterToggleCard(
    enabled: Boolean,
    canEnable: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (canEnable) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Enable Rooted Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (canEnable) {
                        "Distros will launch in a true chroot + namespace + cgroup sandbox."
                    } else {
                        "Device is not rooted or unshare(1) is missing."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = canEnable,
            )
        }
    }
}

@Composable
private fun NamespaceCard(
    spec: NamespaceSpec,
    kernelSupportsUserNs: Boolean,
    onUserNsToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Namespaces",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "mount, pid, network, ipc, uts, cgroup are always on (full sandbox).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "user namespace",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (kernelSupportsUserNs) {
                            "kernel allows it (will be enabled)"
                        } else {
                            "kernel blocks it (will be auto-disabled)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (kernelSupportsUserNs) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = spec.user,
                    onCheckedChange = onUserNsToggle,
                )
            }
        }
    }
}

@Composable
private fun CgroupCard(
    spec: CgroupSpec,
    cgroupV2: Boolean,
    cgexecAvailable: Boolean,
    onApplyBackground: () -> Unit,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Cgroup v2 limits",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (cgroupV2 && cgexecAvailable) {
                    "Controllers: ${spec.controllerList().ifEmpty { "(none — no limits applied)" }}"
                } else {
                    "Device does not support cgroup v2 + cgexec; spec will be refused at launch."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onApplyBackground,
                    modifier = Modifier.weight(1f),
                ) { Text("Background") }
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                ) { Text("No limits") }
            }
            if (spec != CgroupSpec.NONE && spec != CgroupSpec.BACKGROUND) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Custom spec (advanced)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun RootProvider.displayName(): String = when (this) {
    RootProvider.NONE -> "none"
    RootProvider.MAGISK -> "Magisk"
    RootProvider.KERNEL_SU -> "KernelSU"
    RootProvider.APATCH -> "APatch"
    RootProvider.GENERIC_SU -> "generic su"
    RootProvider.UNKNOWN -> "unknown"
}
