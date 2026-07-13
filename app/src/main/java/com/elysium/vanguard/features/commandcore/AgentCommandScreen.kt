package com.elysium.vanguard.features.commandcore

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.ai.AgentToolCall
import com.elysium.vanguard.ui.theme.TitanColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentCommandScreen(
    onBack: () -> Unit,
    viewModel: AgentCommandViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var endpoint by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint) }
    var gatewayToken by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var selectedAction by remember { mutableStateOf<AgentToolCall?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(TitanColors.AbsoluteBlack)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "COMMAND CORE",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp
                            )
                            Text(
                                "LOCAL-FIRST · APPROVAL-GATED",
                                color = TitanColors.NeonCyan.copy(alpha = 0.8f),
                                fontSize = 9.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        Icon(Icons.Default.Security, "Protected gateway", tint = TitanColors.RadioactiveGreen)
                        Spacer(Modifier.width(12.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            }
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 16.dp)
            ) {
                GatewayConfigurationCard(
                    endpoint = endpoint,
                    token = gatewayToken,
                    isConfigured = state.isConfigured,
                    status = state.gatewayStatus,
                    onEndpointChanged = { endpoint = it },
                    onTokenChanged = { gatewayToken = it },
                    onSave = {
                        viewModel.saveGateway(endpoint, gatewayToken)
                        gatewayToken = ""
                    },
                    onClear = viewModel::clearGateway
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.messages) { message -> CommandMessageCard(message) }
                    if (state.isWorking) {
                        item { CoreStatusCard("Command Core is reasoning over the scoped workspace…") }
                    }
                    if (state.proposedActions.isNotEmpty()) {
                        item { Text("PROPOSED ACTIONS", color = TitanColors.NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                        items(state.proposedActions) { action ->
                            ProposedActionCard(action = action, onClick = { selectedAction = action })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Ask Command Core") },
                        maxLines = 4,
                        enabled = state.isConfigured && !state.isWorking,
                        colors = commandFieldColors()
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.send(prompt); prompt = "" },
                        enabled = prompt.isNotBlank() && state.isConfigured && !state.isWorking,
                        colors = ButtonDefaults.buttonColors(containerColor = TitanColors.NeonCyan, contentColor = Color.Black),
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Icon(Icons.Default.Send, "Send")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Command Core") },
            text = { Text(error) },
            confirmButton = {
                Button(onClick = viewModel::dismissError) { Text("OK") }
            },
            containerColor = Color(0xFF080F13),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f)
        )
    }
    selectedAction?.let { action ->
        ActionReviewDialog(action = action, onDismiss = { selectedAction = null })
    }
}

@Composable
private fun GatewayConfigurationCard(
    endpoint: String,
    token: String,
    isConfigured: Boolean,
    status: String,
    onEndpointChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, TitanColors.NeonCyan.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF041316))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = TitanColors.NeonCyan)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("PROTECTED GATEWAY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(status, color = if (status == "CONNECTED" || status == "OK") TitanColors.RadioactiveGreen else TitanColors.NeonCyan, fontSize = 10.sp, letterSpacing = 1.sp)
                }
                if (isConfigured) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.DeleteOutline, "Clear gateway token", tint = TitanColors.NeonRed)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Gateway URL") },
                supportingText = { Text("HTTPS remotely · localhost HTTP only through adb reverse") },
                colors = commandFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (isConfigured) "Gateway token (leave blank to retain)" else "Gateway token") },
                visualTransformation = PasswordVisualTransformation(),
                colors = commandFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSave,
                enabled = token.isNotBlank() || isConfigured,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(containerColor = TitanColors.RadioactiveGreen, contentColor = Color.Black)
            ) { Text("SAVE SECURELY", fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun CommandMessageCard(message: CommandCoreMessage) {
    val isCore = message.author == CommandCoreAuthor.CORE
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isCore) Color(0xFF07171A) else Color(0xFF17100A))
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                if (isCore) "COMMAND CORE" else "YOU",
                color = if (isCore) TitanColors.NeonCyan else TitanColors.NeonYellow,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(message.text, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp)
        }
    }
}

@Composable
private fun CoreStatusCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF08131E))) {
        Text(text, modifier = Modifier.padding(13.dp), color = TitanColors.NeonCyan, fontSize = 12.sp)
    }
}

@Composable
private fun ProposedActionCard(action: AgentToolCall, onClick: () -> Unit) {
    val color = if (action.requiresApproval) TitanColors.NeonYellow else TitanColors.RadioactiveGreen
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0D12))
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(action.name.replace('_', ' ').uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    if (action.requiresApproval) "REQUIRES EXPLICIT APPROVAL" else "READ-ONLY INSPECTION",
                    color = color,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp
                )
            }
            Text("REVIEW", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ActionReviewDialog(action: AgentToolCall, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.name.replace('_', ' ').uppercase()) },
        text = {
            Column {
                Text(if (action.requiresApproval) "No mutation has run. This request requires explicit approval before the local execution layer may act." else "This is a read-only inspection request.")
                Spacer(Modifier.height(10.dp))
                Text(action.argumentsJson, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("CLOSE REVIEW") } },
        containerColor = Color(0xFF080F13),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.84f)
    )
}

@Composable
private fun commandFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TitanColors.NeonCyan,
    unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
    focusedLabelColor = TitanColors.NeonCyan,
    unfocusedLabelColor = Color.White.copy(alpha = 0.58f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = TitanColors.NeonCyan
)
