package com.elysium.vanguard.foundry.core.compiler

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision
import com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.revision.VehicleDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilerTest {

    private val compiler = DeterministicVehicleCompiler()

    @Test
    fun `compile returns success for valid definition`() {
        val definition = sampleDefinition()
        val result = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        )
        assertTrue(result.isSuccess)
        val compilation = result.getOrThrow()
        assertEquals(64, compilation.contentHash.value.length)
        assertTrue(compilation.warnings.isEmpty())
    }

    @Test
    fun `compile is deterministic for same inputs`() {
        val definition = sampleDefinition()
        val a = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()
        val b = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()
        assertEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `compile differs for different catalog revision`() {
        val definition = sampleDefinition()
        val a = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()
        val b = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.08"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `compile differs for different compiler version`() {
        val definition = sampleDefinition()
        val a = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()
        val b = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.1"),
        ).getOrThrow()
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `compile differs for different definition name`() {
        val base = sampleDefinition()
        val renamed = base.copy(name = "Urban Two")
        val a = compiler.compile(
            definition = base,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()
        val b = compiler.compile(
            definition = renamed,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `compile differs for different definition parameters`() {
        val base = sampleDefinition()
        val modified = base.copy(parameters = base.parameters + ("powertrain.battery.kwh" to "60"))
        val a = compiler.compile(
            definition = base,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()
        val b = compiler.compile(
            definition = modified,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `compile is order-independent on parameters`() {
        // The canonical form sorts parameters; insertion order must not
        // affect the content hash.
        val projectId = ProjectId.random()
        val a = VehicleDefinition(
            projectId = projectId,
            name = "Urban One",
            parameters = linkedMapOf(
                "powertrain.type" to "Electric",
                "body.style" to "Compact",
                "battery.kwh" to "40",
            ),
        )
        val b = VehicleDefinition(
            projectId = projectId,
            name = "Urban One",
            parameters = linkedMapOf(
                "battery.kwh" to "40",
                "powertrain.type" to "Electric",
                "body.style" to "Compact",
            ),
        )
        val ha = compiler.compile(a, CatalogRevision("2026.07"), CompilerVersion("1.0.0")).getOrThrow()
        val hb = compiler.compile(b, CatalogRevision("2026.07"), CompilerVersion("1.0.0")).getOrThrow()
        assertEquals(ha.contentHash, hb.contentHash)
    }

    @Test
    fun `compile rejects empty parameters`() {
        val definition = VehicleDefinition(
            projectId = ProjectId.random(),
            name = "Empty",
            parameters = emptyMap(),
        )
        val result = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        )
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected VehicleDefinitionInvalid or CompilationNonDeterministic, got ${error?.javaClass}",
            error is FoundryError.VehicleDefinitionInvalid || error is FoundryError.CompilationNonDeterministic,
        )
    }

    @Test
    fun `compile rejects blank name`() {
        val definition = VehicleDefinition(
            projectId = ProjectId.random(),
            name = "  ",
            parameters = mapOf("powertrain.type" to "Electric"),
        )
        val result = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        )
        assertTrue(result.isFailure)
    }

    private fun sampleDefinition(): VehicleDefinition = VehicleDefinition(
        projectId = ProjectId.random(),
        name = "Urban One",
        parameters = linkedMapOf(
            "powertrain.type" to "Electric",
            "body.style" to "Compact",
            "battery.kwh" to "40",
        ),
    )
}
