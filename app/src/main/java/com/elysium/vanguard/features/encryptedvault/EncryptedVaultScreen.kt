package com.elysium.vanguard.features.encryptedvault

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.encryption.EncryptedVault
import com.elysium.vanguard.ui.theme.TitanColors
import java.io.File

/**
 * Encrypted Vault screen — Solid Explorer style AES-256 encrypted vault.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptedVaultScreen(
    onNavigateBack: () -> Unit,
    viewModel: EncryptedVaultViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }
    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "ENCRYPTED VAULT",
                            color = TitanColors.NeonCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (state.isUnlocked) {
                                "Unlocked · AES-256-GCM"
                            } else {
                                "Locked"
                            },
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (state.isUnlocked) {
                        IconButton(onClick = { viewModel.lock() }) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Lock vault",
                                tint = TitanColors.QuantumPink
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black
    ) { padding ->
        if (state.isUnlocked) {
            UnlockedVaultContent(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
        } else {
            LockedVaultContent(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun LockedVaultContent(
    viewModel: EncryptedVaultViewModel,
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = 0.dp
) {
    var mode by remember { mutableStateOf(VaultMode.Create) }
    var vaultName by remember { mutableStateOf("vault.elysv") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    // PHASE 10.4 — collect instead of reading .value in composition so the
    // vault form actually recomposes when the StateFlow emits (lint E:
    // StateFlowValueCalledInComposition).
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = TitanColors.NeonCyan, modifier = Modifier.size(80.dp))
            Text("ENCRYPTED VAULT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text("AES-256-GCM · PBKDF2-HMAC-SHA256 · 100k iterations", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(16.dp))

        // Mode selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VaultModeButton(
                text = "CREATE",
                selected = mode == VaultMode.Create,
                onClick = { mode = VaultMode.Create }
            )
            VaultModeButton(
                text = "OPEN",
                selected = mode == VaultMode.Open,
                onClick = { mode = VaultMode.Open }
            )
        }

        Spacer(Modifier.height(16.dp))

        // Vault name (create mode)
        if (mode == VaultMode.Create) {
            OutlinedTextField(
                value = vaultName,
                onValueChange = { vaultName = it },
                label = { Text("VAULT NAME", color = TitanColors.NeonCyan.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TitanColors.NeonCyan,
                    unfocusedBorderColor = TitanColors.NeonCyan.copy(alpha = 0.3f),
                    cursorColor = TitanColors.NeonCyan
                ),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = FontFamily.Monospace)
            )
        }

        // Password
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("PASSWORD", color = TitanColors.NeonCyan.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TitanColors.NeonCyan,
                unfocusedBorderColor = TitanColors.NeonCyan.copy(alpha = 0.3f),
                cursorColor = TitanColors.NeonCyan
            ),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = FontFamily.Monospace)
        )

        // Confirm password
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("CONFIRM PASSWORD", color = TitanColors.NeonCyan.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TitanColors.NeonCyan,
                unfocusedBorderColor = TitanColors.NeonCyan.copy(alpha = 0.3f),
                cursorColor = TitanColors.NeonCyan
            ),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = FontFamily.Monospace)
        )

        // Password strength indicator
        if (password.isNotEmpty()) {
            PasswordStrengthIndicator(password)
        }

        Spacer(Modifier.height(8.dp))

        // Action button
        Button(
            onClick = {
                if (password.isNotEmpty() && password == confirmPassword && password.length >= 8) {
                    val vaultDir = File(viewModel.context.filesDir, "vaults")
                    vaultDir.mkdirs()
                    val vaultFile = File(vaultDir, vaultName)
                    if (password.length >= 8) {
                        viewModel.createVault(File(viewModel.context.filesDir, "vaults/${password.hashCode()}.elysv"), password.toCharArray())
                    }
                }
            },
            enabled = !state.isLoading && password.isNotEmpty() && password == confirmPassword && password.length >= 8,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (password.isEmpty() || password != confirmPassword || password.length < 8) Color.White.copy(alpha = 0.2f) else TitanColors.NeonCyan,
                contentColor = Color.Black
            )
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("WORKING...", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            } else {
                Icon(if (mode == VaultMode.Create) Icons.Default.Archive else Icons.Default.LockOpen, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text(if (mode == VaultMode.Create) "CREATE VAULT" else "OPEN VAULT", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun VaultModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) TitanColors.NeonCyan.copy(alpha = 0.2f) else Color.Transparent,
            contentColor = if (selected) TitanColors.NeonCyan else Color.White.copy(alpha = 0.6f)
        )
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun PasswordStrengthIndicator(password: String) {
    val strength = when {
        password.length < 8 -> 0
        password.length < 12 -> 1
        password.length < 16 -> 2
        else -> 3
    }
    val colors = listOf(
        TitanColors.NeonRed,
        TitanColors.NeonOrange,
        TitanColors.NeonYellow,
        TitanColors.RadioactiveGreen
    )
    val labels = listOf("WEAK", "FAIR", "GOOD", "STRONG")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        (0..3).forEach { i ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(if (i <= strength) colors[i] else Color.White.copy(alpha = 0.1f))
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        labels[strength.coerceIn(0, 3)],
        color = colors[strength.coerceIn(0, 3)],
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace
    )
}

enum class VaultMode { Create, Open }

@Composable
private fun UnlockedVaultContent(
    viewModel: EncryptedVaultViewModel,
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = 0.dp
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Header with vault info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("VAULT CONTENTS", color = TitanColors.NeonCyan.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("0 items", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Empty state
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                Text("VAULT IS EMPTY", color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Drag files here or use the add button", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { /* show file picker */ },
                    colors = ButtonDefaults.buttonColors(containerColor = TitanColors.NeonCyan, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("ADD FILES", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}