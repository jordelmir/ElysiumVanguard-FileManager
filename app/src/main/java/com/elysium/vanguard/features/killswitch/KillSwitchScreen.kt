package com.elysium.vanguard.features.killswitch

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.security.KillSwitchResult
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * Phase 100 — the **kill switch** screen.
 *
 * The screen walks the user through a
 * 3-step confirm flow before triggering
 * the irreversible wipe. The flow is
 * deliberately cumbersome — a kill
 * switch is a one-way operation; the
 * friction is the feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KillSwitchScreen(
    onBack: () -> Unit,
    onCompleted: () -> Unit = {},
    viewModel: KillSwitchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        containerColor = TitanColors.AbsoluteBlack,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "KILL SWITCH",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TitanColors.NeonRed,
                        fontSize = 18.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.step == KillSwitchStep.READY_TO_EXECUTE) {
                            viewModel.onBack()
                        } else if (state.step == KillSwitchStep.READY_TO_CONFIRM) {
                            viewModel.onBack()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TitanColors.CarbonGray
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (state.step) {
                KillSwitchStep.INITIAL -> InitialStep(
                    confirmText = state.confirmText,
                    reason = state.reason,
                    onConfirmTextChange = viewModel::onConfirmTextChange,
                    onReasonChange = viewModel::onReasonChange,
                )
                KillSwitchStep.READY_TO_CONFIRM -> ReadyToConfirmStep(
                    reason = state.reason,
                    onContinue = viewModel::onContinue,
                    onBack = viewModel::onBack,
                )
                KillSwitchStep.READY_TO_EXECUTE -> ReadyToExecuteStep(
                    isExecuting = state.isExecuting,
                    lastResult = state.lastResult,
                    onExecute = viewModel::execute,
                    onBack = viewModel::onBack,
                )
                KillSwitchStep.COMPLETED -> CompletedStep(
                    onDismiss = {
                        viewModel.reset()
                        onCompleted()
                    }
                )
            }
        }
    }
}

/**
 * The initial step. The user reads the
 * warning, types `WIPE`, and provides a
 * reason.
 */
@Composable
private fun InitialStep(
    confirmText: String,
    reason: String,
    onConfirmTextChange: (String) -> Unit,
    onReasonChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
    Spacer(modifier = Modifier.height(16.dp))
    // Warning icon.
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(TitanColors.NeonRed.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Dangerous,
            contentDescription = null,
            tint = TitanColors.NeonRed,
            modifier = Modifier.size(56.dp),
        )
    }
    Text(
        text = "EMERGENCY WIPE",
        color = TitanColors.NeonRed,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
    )
    Text(
        text = "This action is IRREVERSIBLE. Every installed distro, " +
            "every session, every workspace, every secret, and every " +
            "log entry will be permanently destroyed. The platform " +
            "will be reset to a clean state.",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 14.sp,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = reason,
        onValueChange = onReasonChange,
        label = { Text("Reason (recorded in audit log)", color = Color.White.copy(alpha = 0.7f)) },
        placeholder = { Text("e.g. device lost / device decommissioned", color = Color.White.copy(alpha = 0.4f)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = TitanColors.CarbonGray,
            unfocusedContainerColor = TitanColors.CarbonGray,
            focusedIndicatorColor = TitanColors.NeonRed,
            unfocusedIndicatorColor = Color.White.copy(alpha = 0.3f),
        ),
    )
    OutlinedTextField(
        value = confirmText,
        onValueChange = onConfirmTextChange,
        label = {
            Text(
                "Type WIPE to confirm",
                color = Color.White.copy(alpha = 0.7f),
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
        ),
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = TitanColors.CarbonGray,
            unfocusedContainerColor = TitanColors.CarbonGray,
            focusedIndicatorColor = TitanColors.NeonRed,
            unfocusedIndicatorColor = Color.White.copy(alpha = 0.3f),
        ),
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = {},
        enabled = false,
        colors = ButtonDefaults.buttonColors(
            containerColor = TitanColors.NeonRed.copy(alpha = 0.2f),
            disabledContainerColor = TitanColors.NeonRed.copy(alpha = 0.2f),
            contentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(
            text = "CONTINUE",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
    }
}

/**
 * The "ready to confirm" step. The user
 * has typed `WIPE` + a reason. The screen
 * shows a summary + a Continue button.
 */
@Composable
private fun ReadyToConfirmStep(
    reason: String,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "READY TO WIPE",
        color = TitanColors.NeonRed,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
    )
    Text(
        text = "The wipe will destroy:",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 14.sp,
    )
    Spacer(modifier = Modifier.height(8.dp))
    WipeInventoryItem("Every installed Linux distro")
    WipeInventoryItem("Every Windows VM spec + state")
    WipeInventoryItem("Every workspace (config + mount + env)")
    WipeInventoryItem("Every network rule")
    WipeInventoryItem("Every secret (Tink vault)")
    WipeInventoryItem("Every audit + diagnostic event")
    Spacer(modifier = Modifier.height(16.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TitanColors.CarbonGray)
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = "REASON",
                color = Color.White.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = reason,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.height(56.dp),
        ) {
            Text("BACK", fontFamily = FontFamily.Monospace)
        }
        Button(
            onClick = onContinue,
            colors = ButtonDefaults.buttonColors(
                containerColor = TitanColors.NeonRed,
                contentColor = Color.White,
            ),
            modifier = Modifier.weight(2f).height(56.dp),
        ) {
            Text(
                "CONTINUE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The "ready to execute" step. The user
 * pressed Continue. A second confirm is
 * required: the EXECUTE button.
 */
@Composable
private fun ReadyToExecuteStep(
    isExecuting: Boolean,
    lastResult: KillSwitchResult?,
    onExecute: () -> Unit,
    onBack: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "FINAL CONFIRMATION",
        color = TitanColors.NeonRed,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
    )
    Text(
        text = "Pressing EXECUTE will run the kill switch NOW. " +
            "There is no undo. The platform will need to be " +
            "re-initialized on next launch.",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 14.sp,
    )
    if (lastResult is KillSwitchResult.Failure) {
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(TitanColors.NeonRed.copy(alpha = 0.2f))
                .padding(12.dp),
        ) {
            Text(
                text = "ERROR: ${lastResult.message}",
                color = TitanColors.NeonRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onBack,
            enabled = !isExecuting,
            modifier = Modifier.height(64.dp),
        ) {
            Text("BACK", fontFamily = FontFamily.Monospace)
        }
        Button(
            onClick = onExecute,
            enabled = !isExecuting,
            colors = ButtonDefaults.buttonColors(
                containerColor = TitanColors.NeonRed,
                contentColor = Color.White,
            ),
            modifier = Modifier.weight(2f).height(64.dp),
        ) {
            if (isExecuting) {
                Text(
                    "WIPING...",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(
                    "EXECUTE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * The completed step. The kill switch ran
 * successfully. The user is shown a success
 * indicator + a "Done" button to navigate
 * back.
 */
@Composable
private fun CompletedStep(onDismiss: () -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(Color(0xFF4CAF50).copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(56.dp),
        )
    }
    Text(
        text = "WIPE COMPLETE",
        color = Color(0xFF4CAF50),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
    )
    Text(
        text = "All runtime data has been destroyed. The platform " +
            "is now in a clean state. Re-initialize on next " +
            "launch to set up the runtime again.",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 14.sp,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF4CAF50),
            contentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(
            "DONE",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * A single line in the wipe-inventory list.
 */
@Composable
private fun WipeInventoryItem(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = TitanColors.NeonRed,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
        )
    }
}
