package com.elysium.vanguard.core.runtime.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PHASE 108 — JVM tests for the **new** rules
 * the Phase 108 NL parser added:
 *
 *  - `configure <runtime>` → [AgentAction.ConfigureRuntime]
 *  - `create shortcut [a <app>]` /
 *    `crea acceso directo` → [AgentAction.CreateShortcut]
 *  - `publish <capsule>` → [AgentAction.PublishCapsule]
 *  - Multi-action goals (split on `,` / `;` /
 *    ` and then ` / ` luego `) → a plan with
 *    multiple actions in order.
 *
 * The existing [NaturalLanguageParserTest] covers
 * the Phase 57 rules; this file covers only the
 * new Phase 108 rules + the multi-action split.
 */
class NaturalLanguageParserPhase108Test {

    private val parser = NaturalLanguageParser(
        idGenerator = { "test-plan-id" },
        clock = { 1_700_000_000_000L }
    )

    // ====================================================================
    // ConfigureRuntime rule
    // ====================================================================

    @Test
    fun `configure Vulkan maps to ConfigureRuntime with enable operation`() {
        val outcome = parser.parse(NaturalLanguageGoal("configure Vulkan"))
        outcome as ParserOutcome.Parsed
        assertEquals(1, outcome.plan.actions.size)
        val action = outcome.plan.actions.first() as AgentAction.ConfigureRuntime
        assertEquals("VULKAN", action.runtime)
        assertEquals("enable", action.operation)
        assertEquals(RiskLevel.LOW, outcome.plan.riskLevel)
    }

    @Test
    fun `configura Vulkan (Spanish) maps to ConfigureRuntime enable`() {
        val outcome = parser.parse(NaturalLanguageGoal("configura Vulkan"))
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.ConfigureRuntime
        assertEquals("VULKAN", action.runtime)
        assertEquals("enable", action.operation)
    }

    @Test
    fun `disable Vulkan maps to ConfigureRuntime with disable operation`() {
        val outcome = parser.parse(NaturalLanguageGoal("disable Vulkan"))
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.ConfigureRuntime
        assertEquals("VULKAN", action.runtime)
        assertEquals("disable", action.operation)
    }

    @Test
    fun `deshabilita DXVK (Spanish disable) maps to ConfigureRuntime disable`() {
        val outcome = parser.parse(NaturalLanguageGoal("deshabilita DXVK"))
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.ConfigureRuntime
        assertEquals("DXVK", action.runtime)
        assertEquals("disable", action.operation)
    }

    @Test
    fun `setup proot maps to ConfigureRuntime enable (English setup)`() {
        val outcome = parser.parse(NaturalLanguageGoal("setup proot"))
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.ConfigureRuntime
        assertEquals("PROOT", action.runtime)
        assertEquals("enable", action.operation)
    }

    // ====================================================================
    // CreateShortcut rule
    // ====================================================================

    @Test
    fun `create shortcut maps to CreateShortcut with default target`() {
        val outcome = parser.parse(NaturalLanguageGoal("create shortcut"))
        outcome as ParserOutcome.Parsed
        assertEquals(1, outcome.plan.actions.size)
        val action = outcome.plan.actions.first() as AgentAction.CreateShortcut
        // The default target is "default" when no
        // app is named.
        assertEquals("default", action.targetAppId)
        assertEquals(RiskLevel.LOW, outcome.plan.riskLevel)
    }

    @Test
    fun `crea acceso directo maps to CreateShortcut (Spanish)`() {
        val outcome = parser.parse(NaturalLanguageGoal("crea acceso directo"))
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.CreateShortcut
        assertEquals("default", action.targetAppId)
    }

    @Test
    fun `create shortcut to blender maps to CreateShortcut with blender target`() {
        val outcome = parser.parse(NaturalLanguageGoal("create shortcut to blender"))
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.CreateShortcut
        assertEquals("blender", action.targetAppId)
        // Display name defaults to the target.
        assertEquals("blender", action.displayName)
    }

    @Test
    fun `crea acceso directo a Telegram maps to CreateShortcut (Spanish with target)`() {
        val outcome = parser.parse(NaturalLanguageGoal("crea acceso directo a Telegram"))
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.CreateShortcut
        assertEquals("telegram", action.targetAppId)
    }

    // ====================================================================
    // PublishCapsule rule
    // ====================================================================

    @Test
    fun `publish com example myapp maps to PublishCapsule stable`() {
        val outcome = parser.parse(
            NaturalLanguageGoal("publish com.example.myapp")
        )
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.PublishCapsule
        assertEquals("com.example.myapp", action.capsuleId)
        assertEquals("stable", action.targetChannel)
    }

    @Test
    fun `publica com example myapp to beta maps to PublishCapsule beta`() {
        val outcome = parser.parse(
            NaturalLanguageGoal("publica com.example.myapp to beta")
        )
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.PublishCapsule
        assertEquals("com.example.myapp", action.capsuleId)
        assertEquals("beta", action.targetChannel)
    }

    @Test
    fun `submit com example myapp to internal maps to PublishCapsule internal`() {
        val outcome = parser.parse(
            NaturalLanguageGoal("submit com.example.myapp to internal")
        )
        outcome as ParserOutcome.Parsed
        val action = outcome.plan.actions.first() as AgentAction.PublishCapsule
        assertEquals("internal", action.targetChannel)
    }

    // ====================================================================
    // Multi-action goals (PHASE 108's main feature)
    // ====================================================================

    @Test
    fun `comma splits a goal into multiple actions`() {
        val outcome = parser.parse(
            NaturalLanguageGoal("install debian, configure Vulkan, create shortcut")
        )
        outcome as ParserOutcome.Parsed
        assertEquals(
            "expected 3 actions (Install + ConfigureRuntime + CreateShortcut)",
            3, outcome.plan.actions.size
        )
        assertTrue("first action must be InstallDistro",
            outcome.plan.actions[0] is AgentAction.InstallDistro)
        assertTrue("second action must be ConfigureRuntime",
            outcome.plan.actions[1] is AgentAction.ConfigureRuntime)
        assertTrue("third action must be CreateShortcut",
            outcome.plan.actions[2] is AgentAction.CreateShortcut)
    }

    @Test
    fun `semicolon splits a goal into multiple actions`() {
        val outcome = parser.parse(
            NaturalLanguageGoal("install debian; configure Vulkan; create shortcut")
        )
        outcome as ParserOutcome.Parsed
        assertEquals(3, outcome.plan.actions.size)
    }

    @Test
    fun `and then splits a goal into multiple actions (English explicit multi-step)`() {
        val outcome = parser.parse(
            NaturalLanguageGoal("install debian and then configure Vulkan and then create shortcut")
        )
        outcome as ParserOutcome.Parsed
        assertEquals(3, outcome.plan.actions.size)
    }

    @Test
    fun `y luego splits a goal into multiple actions (Spanish explicit multi-step)`() {
        val outcome = parser.parse(
            NaturalLanguageGoal("instala debian y luego configura Vulkan y luego crea acceso directo")
        )
        outcome as ParserOutcome.Parsed
        assertEquals(3, outcome.plan.actions.size)
        assertTrue("first action must be InstallDistro",
            outcome.plan.actions[0] is AgentAction.InstallDistro)
        assertTrue("second action must be ConfigureRuntime",
            outcome.plan.actions[1] is AgentAction.ConfigureRuntime)
        assertTrue("third action must be CreateShortcut",
            outcome.plan.actions[2] is AgentAction.CreateShortcut)
    }

    @Test
    fun `multi-action plan riskLevel is the max of all sub-actions`() {
        // The vision's example: "instala blender, configura vulkan, crea acceso directo".
        // InstallDistro = MEDIUM, ConfigureRuntime = LOW,
        // CreateShortcut = LOW. Plan risk = MEDIUM.
        val outcome = parser.parse(
            NaturalLanguageGoal("install blender, configure Vulkan, create shortcut")
        )
        outcome as ParserOutcome.Parsed
        assertEquals(RiskLevel.MEDIUM, outcome.plan.riskLevel)
    }

    @Test
    fun `multi-action plan with one HIGH-risk action has HIGH risk`() {
        // The rollback action is HIGH-risk. A
        // multi-action plan that includes it
        // upgrades the whole plan to HIGH.
        val outcome = parser.parse(
            NaturalLanguageGoal("snapshot ws-1, rollback ws-1, create shortcut")
        )
        outcome as ParserOutcome.Parsed
        assertEquals(RiskLevel.HIGH, outcome.plan.riskLevel)
        assertEquals(3, outcome.plan.actions.size)
    }

    @Test
    fun `multi-action plan fails when one sub-clause is unparseable`() {
        // The middle sub-clause is gibberish.
        // The whole plan must fail (we don't
        // silently drop unparseable sub-clauses).
        val outcome = parser.parse(
            NaturalLanguageGoal("install debian, xyzzy nonsense blah, create shortcut")
        )
        outcome as ParserOutcome.Unparseable
        assertTrue(
            "error must mention the failing sub-clause: ${outcome.reason}",
            outcome.reason.contains("xyzzy")
        )
    }

    @Test
    fun `empty sub-clauses from trailing commas are dropped`() {
        val outcome = parser.parse(
            NaturalLanguageGoal("install debian,, configure Vulkan,")
        )
        outcome as ParserOutcome.Parsed
        // Trailing empty sub-clauses are dropped;
        // 2 real actions remain.
        assertEquals(2, outcome.plan.actions.size)
    }

    @Test
    fun `multi-action plan preserves the order of sub-clauses`() {
        val outcome = parser.parse(
            NaturalLanguageGoal("create shortcut, install debian, configure Vulkan")
        )
        outcome as ParserOutcome.Parsed
        assertEquals(3, outcome.plan.actions.size)
        // Order is preserved (the parser does NOT
        // reorder by risk or by some other criterion).
        assertTrue("first action must be CreateShortcut",
            outcome.plan.actions[0] is AgentAction.CreateShortcut)
        assertTrue("second action must be InstallDistro",
            outcome.plan.actions[1] is AgentAction.InstallDistro)
        assertTrue("third action must be ConfigureRuntime",
            outcome.plan.actions[2] is AgentAction.ConfigureRuntime)
    }

    @Test
    fun `single-action goal still works (regression check)`() {
        // Phase 57's single-action behavior must
        // not be broken by the multi-action split.
        val outcome = parser.parse(NaturalLanguageGoal("install debian"))
        outcome as ParserOutcome.Parsed
        assertEquals(1, outcome.plan.actions.size)
        assertTrue(outcome.plan.actions.first() is AgentAction.InstallDistro)
    }

    @Test
    fun `unparseable single goal is still unparseable (regression check)`() {
        val outcome = parser.parse(NaturalLanguageGoal("gibberish nonsense blah"))
        outcome as ParserOutcome.Unparseable
    }
}
