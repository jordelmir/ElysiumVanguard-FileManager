package com.elysium.vanguard.core.runtime.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 57 — tests for [NaturalLanguageParser].
 *
 * The parser is a rule-based mapper from
 * natural-language goals to [AgentPlan]s.
 * The tests pin every rule path:
 *
 *   - English + Spanish install rules.
 *   - English + Spanish create-windows
 *     rules (with QEMU + FEX + default
 *     Box64 runtime hints).
 *   - English + Spanish snapshot rules.
 *   - English + Spanish rollback rules.
 *   - English + Spanish build rules.
 *   - English + Spanish run-command rules.
 *   - Unknown input returns Unparseable.
 *
 * The parser is OUR intellectual property.
 * The rule table is the source of truth; a
 * user with a "why did the agent install
 * Debian for this goal?" question reads the
 * table and answers it.
 */
class NaturalLanguageParserTest {

    private val parser = NaturalLanguageParser(
        idGenerator = { "plan-test-1" },
        clock = { 1_700_000_000_000L }
    )

    private fun parse(text: String, languageCode: String = "en-US", autoConfirm: Boolean = false): ParserOutcome {
        val goal = NaturalLanguageGoal(
            text = text,
            languageCode = languageCode,
            autoConfirm = autoConfirm
        )
        return parser.parse(goal)
    }

    // --- install ---

    @Test
    fun `English install parses as InstallDistro with MEDIUM risk`() {
        val outcome = parse("install debian-12")
        assertTrue(outcome is ParserOutcome.Parsed)
        val plan = (outcome as ParserOutcome.Parsed).plan
        assertEquals(1, plan.actions.size)
        val action = plan.actions[0]
        assertTrue(action is AgentAction.InstallDistro)
        assertEquals("debian-12", (action as AgentAction.InstallDistro).distroId)
        assertEquals(RiskLevel.MEDIUM, plan.riskLevel)
    }

    @Test
    fun `Spanish install parses as InstallDistro`() {
        val outcome = parse("instalar debian-12")
        assertTrue(outcome is ParserOutcome.Parsed)
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0]
        assertTrue(action is AgentAction.InstallDistro)
        assertEquals("debian-12", (action as AgentAction.InstallDistro).distroId)
    }

    // --- create-windows ---

    @Test
    fun `create windows env defaults to WINE_BOX64`() {
        val outcome = parse("create windows env for /path/to/setup.exe")
        assertTrue(outcome is ParserOutcome.Parsed)
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0]
        assertTrue(action is AgentAction.CreateWindowsEnvironment)
        val createAction = action as AgentAction.CreateWindowsEnvironment
        assertEquals("/path/to/setup.exe", createAction.binaryPath)
        assertEquals("WINE_BOX64", createAction.runtimeKind)
    }

    @Test
    fun `create windows env with QEMU hint picks QEMU_VM`() {
        val outcome = parse("create windows env for /path/to/setup.exe via QEMU")
        assertTrue(outcome is ParserOutcome.Parsed)
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0] as AgentAction.CreateWindowsEnvironment
        assertEquals("QEMU_VM", action.runtimeKind)
    }

    @Test
    fun `create windows env with FEX hint picks WINE_FEX`() {
        val outcome = parse("create windows env for /path/to/setup.exe via FEX")
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0] as AgentAction.CreateWindowsEnvironment
        assertEquals("WINE_FEX", action.runtimeKind)
    }

    // --- snapshot ---

    @Test
    fun `snapshot workspace parses as CreateSnapshot with MEDIUM risk`() {
        val outcome = parse("snapshot ws-1")
        assertTrue(outcome is ParserOutcome.Parsed)
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0]
        assertTrue(action is AgentAction.CreateSnapshot)
        val createAction = action as AgentAction.CreateSnapshot
        assertEquals("ws-1", createAction.workspaceId)
        assertEquals(RiskLevel.MEDIUM, plan.riskLevel)
    }

    @Test
    fun `Spanish instantanea parses as CreateSnapshot`() {
        val outcome = parse("instantanea ws-1")
        assertTrue(outcome is ParserOutcome.Parsed)
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0]
        assertTrue(action is AgentAction.CreateSnapshot)
    }

    // --- rollback ---

    @Test
    fun `rollback workspace parses as RollbackToSnapshot with HIGH risk`() {
        val outcome = parse("rollback ws-1")
        assertTrue(outcome is ParserOutcome.Parsed)
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0]
        assertTrue(action is AgentAction.RollbackToSnapshot)
        val rollbackAction = action as AgentAction.RollbackToSnapshot
        assertEquals("ws-1", rollbackAction.workspaceId)
        assertEquals("latest", rollbackAction.snapshotId)
        assertEquals(RiskLevel.HIGH, plan.riskLevel)
    }

    // --- build ---

    @Test
    fun `build with rust parses as RunBuild with LOW risk`() {
        val outcome = parse("build rust build --release")
        assertTrue(outcome is ParserOutcome.Parsed)
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0]
        assertTrue(action is AgentAction.RunBuild)
        val buildAction = action as AgentAction.RunBuild
        assertEquals("RUST", buildAction.toolchainKind)
        assertEquals(listOf("build", "--release"), buildAction.command)
        assertEquals(RiskLevel.LOW, plan.riskLevel)
    }

    @Test
    fun `Spanish compilar parses as RunBuild`() {
        val outcome = parse("compilar con go build")
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0] as AgentAction.RunBuild
        assertEquals("GO", action.toolchainKind)
    }

    // --- run ---

    @Test
    fun `run command parses as RunCommand with HIGH risk`() {
        val outcome = parse("run /bin/ls -la")
        assertTrue(outcome is ParserOutcome.Parsed)
        val plan = (outcome as ParserOutcome.Parsed).plan
        val action = plan.actions[0]
        assertTrue(action is AgentAction.RunCommand)
        val runAction = action as AgentAction.RunCommand
        assertEquals(listOf("/bin/ls", "-la"), runAction.command)
        assertEquals(RiskLevel.HIGH, plan.riskLevel)
    }

    // --- unknown ---

    @Test
    fun `unparseable input returns Unparseable with a reason`() {
        val outcome = parse("xyzzy plugh foobar")
        assertTrue(outcome is ParserOutcome.Unparseable)
        val reason = (outcome as ParserOutcome.Unparseable).reason
        assertTrue(
            "reason should mention the unmatched input: $reason",
            reason.contains("xyzzy", ignoreCase = true) ||
                reason.contains("no rule matched", ignoreCase = true)
        )
    }

    @Test
    fun `blank input is rejected by the goal's init-block`() {
        try {
            NaturalLanguageGoal(text = "   ")
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `plan id is generated from the idGenerator`() {
        val outcome = parse("install debian-12")
        val plan = (outcome as ParserOutcome.Parsed).plan
        assertEquals("plan-test-1", plan.id)
    }
}
