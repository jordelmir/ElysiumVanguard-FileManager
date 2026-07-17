package com.elysium.vanguard.foundry.fixture

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.revision.VehicleDefinition

/**
 * Test fixture: a compact electric `VehicleDefinition`.
 *
 * The fixture is the canonical "valid" definition for Phase 1
 * integration tests. The definition is deterministic: every
 * field is hard-coded so the same `(projectId)` produces the
 * same `canonicalForm()` and therefore the same
 * `Compilation.contentHash`.
 *
 * The fixture lives in `src/test/` (not `src/main/`) because
 * production code should never reference a hard-coded
 * `VehicleDefinition` — production definitions come from
 * user input (Phase 2: the DSL parser).
 */
object VehicleDefinitionFixture {

    /**
     * A compact electric vehicle with a 40 kWh battery.
     *
     * Parameters:
     *   - `powertrain.type = Electric`
     *   - `body.style = Compact`
     *   - `battery.kwh = 40`
     *   - `range.km = 250`
     *   - `top.speed.kph = 150`
     */
    fun validCompactElectricVehicleDefinition(
        projectId: ProjectId,
    ): VehicleDefinition = VehicleDefinition(
        projectId = projectId,
        name = "Urban One",
        parameters = linkedMapOf(
            "powertrain.type" to "Electric",
            "body.style" to "Compact",
            "battery.kwh" to "40",
            "range.km" to "250",
            "top.speed.kph" to "150",
        ),
    )
}
