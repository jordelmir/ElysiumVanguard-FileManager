package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.compiler.Compilation
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.revision.VehicleDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneManifestTest {

    private val generator = SceneManifestGenerator()

    private val sampleDefinition = VehicleDefinition(
        projectId = ProjectId.random(),
        name = "Urban One",
        parameters = linkedMapOf(
            "powertrain.type" to "Electric",
            "body.style" to "Compact",
            "battery.kwh" to "40",
        ),
    )

    private val sampleCompilation = Compilation(
        contentHash = ContentHash.of("compilation:abc"),
    )

    @Test
    fun `generator produces manifest with one component per parameter`() {
        val manifest = generator.generate(sampleCompilation, sampleDefinition)
        assertEquals(3, manifest.components.size)
    }

    @Test
    fun `generator produces manifest with three lod placeholders`() {
        val manifest = generator.generate(sampleCompilation, sampleDefinition)
        assertEquals(3, manifest.lods.size)
        assertEquals(listOf(0, 1, 2), manifest.lods.map { it.level })
    }

    @Test
    fun `generator defaults to parametric functional level`() {
        val manifest = generator.generate(sampleCompilation, sampleDefinition)
        assertEquals(RepresentationLevel.PARAMETRIC_FUNCTIONAL, manifest.representationLevel)
    }

    @Test
    fun `generator accepts custom representation level`() {
        val manifest = generator.generate(
            compilation = sampleCompilation,
            definition = sampleDefinition,
            representationLevel = RepresentationLevel.CONCEPTUAL,
        )
        assertEquals(RepresentationLevel.CONCEPTUAL, manifest.representationLevel)
    }

    @Test
    fun `manifest content hash is non-null and 64 chars`() {
        val manifest = generator.generate(sampleCompilation, sampleDefinition)
        assertNotNull(manifest.contentHash)
        assertEquals(64, manifest.contentHash.value.length)
    }

    @Test
    fun `manifest content hash is deterministic for same inputs`() {
        val a = generator.generate(sampleCompilation, sampleDefinition)
        val b = generator.generate(sampleCompilation, sampleDefinition)
        assertEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `manifest content hash differs for different representation levels`() {
        val a = generator.generate(sampleCompilation, sampleDefinition, RepresentationLevel.PARAMETRIC_FUNCTIONAL)
        val b = generator.generate(sampleCompilation, sampleDefinition, RepresentationLevel.CONCEPTUAL)
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `component labels are human readable`() {
        val manifest = generator.generate(sampleCompilation, sampleDefinition)
        val labels = manifest.components.map { it.label }
        assertTrue("expected Powertrain / Type in labels, got $labels", labels.contains("Powertrain / Type"))
        assertTrue("expected Body / Style in labels, got $labels", labels.contains("Body / Style"))
        assertTrue("expected Battery / Kwh in labels, got $labels", labels.contains("Battery / Kwh"))
    }
}
