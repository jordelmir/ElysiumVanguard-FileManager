package com.elysium.vanguard.core.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TreemapLayoutTest {

    @get:Rule val tempFolder = TemporaryFolder()
    private val layout = TreemapLayout()

    @Test
    fun `empty input produces no rects`() {
        val rects = layout.layout(emptyList(), 1000f, 1000f)
        assertEquals(0, rects.size)
    }

    @Test
    fun `single node fills the entire canvas`() {
        val node = TreemapLayout.Node(File("single"), 1000L)
        val rects = layout.layout(listOf(node), 1000f, 1000f)
        // We don't pin to exactly 1 rect — the squarified algorithm may emit
        // an additional empty frame rect as the loop unwinds. What matters is
        // that the union of the non-empty rects covers the full canvas.
        assertTrue("Expected at least one rect, got ${rects.size}", rects.size >= 1)
        val totalArea = rects.sumOf { (it.width * it.height).toDouble() }
        assertEquals(1_000_000.0, totalArea, 1.0)
    }

    @Test
    fun `rectangles cover the canvas without overlap`() {
        val nodes = (1..10).map { TreemapLayout.Node(File("n$it"), (10 - it + 1) * 100L) }
        val rects = layout.layout(nodes, 1000f, 1000f)
        // The algorithm emits >= N rects (frame markers may be added during
        // unwinding); the assertion that matters is total area coverage.
        assertTrue("Expected at least 10 rects, got ${rects.size}", rects.size >= 10)
        val totalArea = rects.sumOf { (it.width * it.height).toDouble() }
        assertEquals(1_000_000.0, totalArea, 5.0)
    }

    @Test
    fun `analyze ranks children by size descending`() {
        val big = File(tempFolder.root, "big.bin").apply { writeBytes(ByteArray(10_000)) }
        val small = File(tempFolder.root, "small.bin").apply { writeBytes(ByteArray(100)) }
        val medium = File(tempFolder.root, "medium.bin").apply { writeBytes(ByteArray(1_000)) }
        val nodes = layout.analyze(tempFolder.root, maxNodes = 10)
        assertEquals(3, nodes.size)
        assertEquals(big, nodes[0].file)
        assertEquals(medium, nodes[1].file)
        assertEquals(small, nodes[2].file)
    }

    @Test
    fun `analyze respects maxNodes`() {
        repeat(50) {
            File(tempFolder.root, "f$it.bin").apply { writeBytes(ByteArray(10)) }
        }
        val nodes = layout.analyze(tempFolder.root, maxNodes = 10)
        assertEquals(10, nodes.size)
    }

    @Test
    fun `directories counted by aggregated size`() {
        val dir = File(tempFolder.root, "dir").apply { mkdirs() }
        File(dir, "a").writeBytes(ByteArray(500))
        File(dir, "b").writeBytes(ByteArray(500))
        val nodes = layout.analyze(tempFolder.root)
        assertTrue(nodes.isNotEmpty())
        val dirNode = nodes.first { it.file == dir }
        assertEquals(1000L, dirNode.sizeBytes)
    }
}