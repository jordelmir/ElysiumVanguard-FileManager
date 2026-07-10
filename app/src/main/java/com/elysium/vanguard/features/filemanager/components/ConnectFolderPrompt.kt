package com.elysium.vanguard.features.filemanager.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 8.8 — Empty / no-SAF-tree state.
 *
 * Shown when the user hasn't granted a SAF tree yet, or when the granted
 * permission was revoked. Provides a single primary action ("Pick a
 * folder") that opens the system SAF picker.
 *
 * We deliberately do NOT include a "Use MANAGE_EXTERNAL_STORAGE" CTA — the
 * SAF flow is the recommended path for every Android version we support.
 */
@Composable
fun ConnectFolderPrompt(
    onPickFolder: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050810))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = TitanColors.NeonCyan,
                modifier = Modifier.size(72.dp)
            )
            Text(
                text = "Connect a folder",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Pick a folder to get started. The app works only " +
                    "inside folders you grant, so your data stays under " +
                    "your control. You can disconnect or change folders " +
                    "anytime from the menu.",
                color = TitanColors.NeonCyan.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onPickFolder,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TitanColors.NeonCyan,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Pick folder", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Avoid an unused-import warning by pulling FontWeight into the file scope.
// (Compose's TextStyle import is implicit but FontWeight isn't.)
private val FontWeight = androidx.compose.ui.text.font.FontWeight