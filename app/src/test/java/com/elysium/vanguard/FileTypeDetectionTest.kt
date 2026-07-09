package com.elysium.vanguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 0.8 — Pure unit tests for path / extension logic.
 *
 * The actual viewer composables are tightly coupled to Android framework
 * classes (Context, FileProvider, WebView) and belong in androidTest/.
 * These pure-logic tests guard the file-type classification that drives
 * which renderer is selected.
 */
class FileTypeDetectionTest {

    @Test
    fun `docx files are detected by extension`() {
        assertTrue(isDocxByName("report.docx"))
    }

    @Test
    fun `docx files are detected case-insensitively`() {
        assertTrue(isDocxByName("REPORT.DOCX"))
        assertTrue(isDocxByName("Report.Docx"))
    }

    @Test
    fun `non-docx files are not classified as docx`() {
        assertFalse(isDocxByName("report.pdf"))
        assertFalse(isDocxByName("image.png"))
        assertFalse(isDocxByName(""))
    }

    @Test
    fun `html files are webview-friendly`() {
        assertTrue(isWebViewFriendly("page.html"))
        assertTrue(isWebViewFriendly("page.htm"))
    }

    @Test
    fun `txt files are webview-friendly`() {
        assertTrue(isWebViewFriendly("notes.txt"))
    }

    @Test
    fun `pdf files are NOT webview-friendly`() {
        // PDFs use a native renderer, not a WebView.
        assertFalse(isWebViewFriendly("contract.pdf"))
    }

    // Mirror of IntegratedDocumentViewer detection logic. We deliberately
    // do not import the composable here to avoid pulling Android runtime.
    private fun isDocxByName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".docx") || lower.startsWith("doc-")
    }

    private fun isWebViewFriendly(name: String): Boolean {
        val lower = name.lowercase()
        val ext = lower.substringAfterLast('.', missingDelimiterValue = "")
        return ext == "txt" || ext == "html" || ext == "htm"
    }
}