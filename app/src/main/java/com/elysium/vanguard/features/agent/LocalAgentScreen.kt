package com.elysium.vanguard.features.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.runtime.agent.AgentPlan
import com.elysium.vanguard.core.runtime.agent.RiskLevel

/**
 * Phase 73 — the screen for the rule-based
 * Vanguard AI ("Local Agent").
 *
 * The screen is a thin shell over
 * [LocalAgentViewModel]:
 *
 *  - A text field at the bottom for the user's
 *    goal.
 *  - The latest exchange (user text + assistant
 *    response) above the input.
 *  - When the parser produces a HIGH-risk plan
 *    without user confirmation, a "Confirm"
 *    button shows up so the user can approve.
 *  - When the executor's outcome is
 *    `Success` / `Failure` / `Refused`, the
 *    response is shown as plain text.
 *
 * The screen is intentionally minimal — the
 * gateway-based
 * [com.elysium.vanguard.features.commandcore.AgentCommandScreen]
 * is the rich, full-featured AI surface (chat
 * history, tool approval, settings). The local
 * rule-based agent is the "no-LLM-needed,
 * on-device, type-a-goal-and-watch-it-act"
 * surface — fast, auditable, and proprietary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAgentScreen(
    onBack: () -> Unit,
    viewModel: LocalAgentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "LOCAL AGENT",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            "Rule-based • on-device • no external LLM",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // The intro card (shown once on first
            // load).
            if (state.exchange.userText.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        text = state.intro,
                        modifier = Modifier.padding(16.dp),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // The exchange.
            if (state.exchange.userText.isNotEmpty() || state.exchange.assistantText.isNotEmpty()) {
                ExchangeView(
                    userText = state.exchange.userText,
                    assistantText = state.exchange.assistantText,
                    proposedPlan = state.exchange.proposedPlan,
                    onConfirm = { plan -> viewModel.confirmExecution(plan) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))

            // The input + send button.
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("e.g. install debian-stable") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            viewModel.submit(input)
                            input = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ExchangeView(
    userText: String,
    assistantText: String,
    proposedPlan: AgentPlan?,
    onConfirm: (AgentPlan) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
    ) {
        if (userText.isNotEmpty()) {
            UserMessageBubble(userText)
            Spacer(Modifier.height(8.dp))
        }
        if (assistantText.isNotEmpty()) {
            AssistantMessageBubble(assistantText)
        }
        if (proposedPlan != null && !proposedPlan.goal.autoConfirm) {
            Spacer(Modifier.height(8.dp))
            ConfirmationCard(
                plan = proposedPlan,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun UserMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AssistantMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ConfirmationCard(
    plan: AgentPlan,
    onConfirm: (AgentPlan) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (plan.riskLevel) {
                RiskLevel.LOW -> Color(0xFF1B5E20).copy(alpha = 0.2f)
                RiskLevel.MEDIUM -> Color(0xFFE65100).copy(alpha = 0.2f)
                RiskLevel.HIGH -> Color(0xFFB71C1C).copy(alpha = 0.2f)
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = when (plan.riskLevel) {
                RiskLevel.LOW -> Color(0xFF66BB6A)
                RiskLevel.MEDIUM -> Color(0xFFFFA726)
                RiskLevel.HIGH -> Color(0xFFEF5350)
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${plan.riskLevel.name} RISK PLAN",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = when (plan.riskLevel) {
                    RiskLevel.LOW -> Color(0xFF81C784)
                    RiskLevel.MEDIUM -> Color(0xFFFFB74D)
                    RiskLevel.HIGH -> Color(0xFFE57373)
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = plan.describe(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { onConfirm(plan) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (plan.riskLevel) {
                            RiskLevel.LOW -> Color(0xFF2E7D32)
                            RiskLevel.MEDIUM -> Color(0xFFE65100)
                            RiskLevel.HIGH -> Color(0xFFB71C1C)
                        }
                    )
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Confirm",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Confirm", fontSize = 12.sp)
                }
            }
        }
    }
}
