package com.elysium.vanguard.features.viewer

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.vanguard.core.office.CsvParser
import com.elysium.vanguard.core.office.ElysiumDeck
import com.elysium.vanguard.core.office.ElysiumDocument
import com.elysium.vanguard.core.office.ElysiumWordRenderer
import java.io.File

/**
 * PHASE 9.8.5 — Compose viewer for Elysium's native document format
 * (`.elysium.word`, `.elysium.sheet`, `.elysium.deck`).
 *
 * Reads the ZIP container, decodes the payload by kind, and renders
 * it with a kind-appropriate Compose layout. Intentionally minimal —
 * we don't try to be Office; we just need the documents to open.
 *
 * Phase 9.8.5 — first build; intentionally minimal.
 */
@Composable
fun ElysiumDocumentViewer(file: File, onBack: () -> Unit) {
    val doc = remember(file.path) {
        runCatching { ElysiumDocument.fromBytes(file.readBytes()) }.getOrNull()
    }
    if (doc == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Failed to read ${file.name}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "This file is not a valid .elysium.* document.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${file.nameWithoutExtension}  ·  ${doc.kind.name}",
                style = MaterialTheme.typography.titleMedium
            )
        }
        when (doc.kind) {
            ElysiumDocument.Kind.WORD -> WordPane(doc)
            ElysiumDocument.Kind.SHEET -> SheetPane(doc)
            ElysiumDocument.Kind.DECK -> DeckPane(doc)
        }
    }
}

@Composable
private fun WordPane(doc: ElysiumDocument) {
    val annotated: AnnotatedString = remember(doc) {
        ElysiumWordRenderer.render(doc.body, doc.style)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(text = annotated)
    }
}

@Composable
private fun SheetPane(doc: ElysiumDocument) {
    val sheet = remember(doc) {
        runCatching { CsvParser.parse(doc.body) }.getOrNull()
    }
    if (sheet == null || sheet.rows == 0) {
        Text(
            text = "Empty sheet.",
            modifier = Modifier.padding(16.dp)
        )
        return
    }
    val maxCols = sheet.cols
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        for (rowIdx in 0 until sheet.rows) {
            val row = sheet.cells[rowIdx]
            Row(modifier = Modifier.fillMaxWidth()) {
                for (colIdx in 0 until maxCols) {
                    val cell = row.getOrNull(colIdx) ?: ""
                    val isHeader = rowIdx == 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isHeader) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        BasicText(
                            text = cell,
                            style = TextStyle(
                                fontSize = if (isHeader) 13.sp else 12.sp,
                                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckPane(doc: ElysiumDocument) {
    val deck = remember(doc) {
        runCatching { ElysiumDeck.fromJson(doc.body) }.getOrNull()
    }
    if (deck == null || deck.slides.isEmpty()) {
        Text(
            text = "This deck document is unreadable.",
            modifier = Modifier.padding(16.dp)
        )
        return
    }
    var currentIndex by remember { mutableStateOf(0) }
    val safeIndex = currentIndex.coerceIn(0, deck.slides.size - 1)
    val slide = deck.slides[safeIndex]
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { currentIndex = (safeIndex - 1).coerceAtLeast(0) }) {
                Icon(Icons.Default.NavigateBefore, contentDescription = "Previous slide")
            }
            Text(
                text = "Slide ${safeIndex + 1} / ${deck.slides.size}",
                style = MaterialTheme.typography.titleSmall
            )
            IconButton(
                onClick = {
                    currentIndex = (safeIndex + 1).coerceAtMost(deck.slides.size - 1)
                }
            ) {
                Icon(Icons.Default.NavigateNext, contentDescription = "Next slide")
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = slide.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(12.dp))
                Text(text = slide.body, style = MaterialTheme.typography.bodyLarge)
                val notes = slide.notes
                if (!notes.isNullOrBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Notes: $notes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private val File.nameWithoutExtension: String
    get() {
        val name = this.name
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }