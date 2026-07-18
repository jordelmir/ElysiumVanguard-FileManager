package com.elysium.vanguard.core.runtime.agent

import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 57 — the natural-language parser.
 *
 * The parser is a stateless function from a
 * [NaturalLanguageGoal] to a [ParserOutcome]
 * (a parsed [AgentPlan] or a typed rejection).
 * The parser is rule-based: each rule is a
 * small `when` over keywords in the input
 * text. The rule table is the source of
 * truth for Phase 57; a future phase adds an
 * LLM-based parser that the user explicitly
 * enables.
 *
 * The parser is OUR intellectual property.
 * No third-party LLM, no API key, no network
 * call. The rule table is auditable: a user
 * with a "why did the agent install Debian
 * for this goal?" question can read the
 * table and answer it.
 *
 * ## Rule table
 *
 * Each rule is a small `when` over the
 * normalised input text (lowercased, trimmed,
 * whitespace-collapsed). The rules are tried
 * in priority order; the first match wins.
 *
 * - `install <distro>` → `InstallDistro`
 *   (MEDIUM).
 * - `create windows` / `run windows` /
 *   `set up windows` →
 *   `CreateWindowsEnvironment` (MEDIUM).
 * - `snapshot <workspace>` → `CreateSnapshot`
 *   (MEDIUM).
 * - `rollback <workspace>` →
 *   `RollbackToSnapshot` with the latest
 *   snapshot (HIGH).
 * - `build <toolchain> <command>` →
 *   `RunBuild` (LOW).
 * - `run <command>` → `RunCommand` (HIGH).
 * - A goal that matches no rule returns
 *   `ParserOutcome.Unparseable`.
 *
 * The parser supports English + Spanish
 * keywords (the two languages the user
 * uses). A future phase adds more.
 */
class NaturalLanguageParser(
    private val idGenerator: () -> String = ::defaultIdGenerator,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Parse [goal] into a [ParserOutcome].
     * The parser is a pure function: same
     * input, same output, no side effects.
     */
    fun parse(goal: NaturalLanguageGoal): ParserOutcome {
        val normalised = normalise(goal.text)
        val plan = tryParse(goal, normalised)
        return plan?.let { ParserOutcome.Parsed(it) } ?: ParserOutcome.Unparseable(
            reason = "no rule matched the input: '${goal.text}'"
        )
    }

    private fun tryParse(
        goal: NaturalLanguageGoal,
        normalised: String
    ): AgentPlan? {
        // English / Spanish install rule.
        val distroMatch = INSTALL_REGEX.find(normalised)
        if (distroMatch != null) {
            return makePlan(
                goal = goal,
                actions = listOf(
                    AgentAction.InstallDistro(distroId = distroMatch.groupValues[1])
                ),
                risk = RiskLevel.MEDIUM
            )
        }

        // English / Spanish create-windows rule.
        val windowsMatch = WINDOWS_REGEX.find(normalised)
        if (windowsMatch != null) {
            val binary = windowsMatch.groupValues[1]
            // The runtime kind defaults to
            // WINE_BOX64 (the master vision's
            // priority Windows path); the user
            // can override via "via QEMU" or
            // "via FEX" hints.
            val runtimeKind = when {
                normalised.contains("qemu") || normalised.contains("vm") -> "QEMU_VM"
                normalised.contains("fex") -> "WINE_FEX"
                else -> "WINE_BOX64"
            }
            return makePlan(
                goal = goal,
                actions = listOf(
                    AgentAction.CreateWindowsEnvironment(
                        binaryPath = binary,
                        runtimeKind = runtimeKind
                    )
                ),
                risk = RiskLevel.MEDIUM
            )
        }

        // English / Spanish snapshot rule.
        val snapshotMatch = SNAPSHOT_REGEX.find(normalised)
        if (snapshotMatch != null) {
            val workspaceId = snapshotMatch.groupValues[1]
            return makePlan(
                goal = goal,
                actions = listOf(
                    AgentAction.CreateSnapshot(
                        workspaceId = workspaceId,
                        label = "agent-${idGenerator().take(8)}"
                    )
                ),
                risk = RiskLevel.MEDIUM
            )
        }

        // English / Spanish rollback rule.
        val rollbackMatch = ROLLBACK_REGEX.find(normalised)
        if (rollbackMatch != null) {
            val workspaceId = rollbackMatch.groupValues[1]
            // The agent does not know the
            // snapshot id; the executor picks
            // the latest. Phase 60+ adds a
            // planner that knows the snapshot
            // history.
            return makePlan(
                goal = goal,
                actions = listOf(
                    AgentAction.RollbackToSnapshot(
                        workspaceId = workspaceId,
                        snapshotId = "latest"
                    )
                ),
                risk = RiskLevel.HIGH
            )
        }

        // English / Spanish build rule.
        val buildMatch = BUILD_REGEX.find(normalised)
        if (buildMatch != null) {
            val toolchain = buildMatch.groupValues[1].uppercase()
            val command = buildMatch.groupValues[2]
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            return makePlan(
                goal = goal,
                actions = listOf(
                    AgentAction.RunBuild(
                        toolchainKind = toolchain,
                        command = command
                    )
                ),
                risk = RiskLevel.LOW
            )
        }

        // English / Spanish run-command rule.
        val runMatch = RUN_REGEX.find(normalised)
        if (runMatch != null) {
            val command = runMatch.groupValues[1]
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            return makePlan(
                goal = goal,
                actions = listOf(
                    AgentAction.RunCommand(command = command)
                ),
                risk = RiskLevel.HIGH
            )
        }

        return null
    }

    private fun makePlan(
        goal: NaturalLanguageGoal,
        actions: List<AgentAction>,
        risk: RiskLevel
    ): AgentPlan = AgentPlan(
        id = idGenerator(),
        actions = actions,
        riskLevel = risk,
        createdAtMs = clock(),
        goal = goal
    )

    private fun normalise(text: String): String =
        text.lowercase().trim().replace(Regex("\\s+"), " ")

    companion object {
        // English / Spanish install: "install
        // <distro>" / "instalar <distro>".
        private val INSTALL_REGEX = Regex(
            "(?:install|instalar|instala|setup)\\s+([a-z][a-z0-9._-]+)"
        )

        // English / Spanish create-windows:
        // "create windows env for <binary>"
        // / "create windows environment for
        // <binary>" / "run windows <binary>"
        // / "ejecutar windows <binary>".
        private val WINDOWS_REGEX = Regex(
            "(?:create|set up|run|ejecutar)\\s+(?:windows|win)\\s+(?:env(?:ironment)?|app(?:lication)?|binary)?(?:\\s+(?:for|de|para))?\\s*([/\\w.\\-]+)"
        )

        // English / Spanish snapshot:
        // "snapshot <workspace>" / "snap
        // <workspace>" / "instantanea
        // <workspace>".
        private val SNAPSHOT_REGEX = Regex(
            "(?:snapshot|snap|instantanea)\\s+([a-z][a-z0-9._-]+)"
        )

        // English / Spanish rollback:
        // "rollback <workspace>" /
        // "revertir <workspace>" / "restore
        // <workspace>".
        private val ROLLBACK_REGEX = Regex(
            "(?:rollback|revertir|restore)\\s+([a-z][a-z0-9._-]+)"
        )

        // English / Spanish build:
        // "build <toolchain> <command>".
        private val BUILD_REGEX = Regex(
            "(?:build|compile|compilar)\\s+(?:with|usando|con)?\\s*([a-z]+)\\s+(.+)"
        )

        // English / Spanish run: "run
        // <command>".
        private val RUN_REGEX = Regex(
            "(?:run|exec|ejecutar)\\s+(?!with\\s)(?!windows\\s)(?!build\\s)([\\w./\\-]+\\s+[\\w./\\-\\s]+)"
        )

        private val counter = AtomicInteger(0)

        /**
         * Default id generator. The id is
         * `plan-<systemTimeMs>-<counter>`.
         */
        fun defaultIdGenerator(): String =
            "plan-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
    }
}

/**
 * Phase 57 — the parser's outcome. The
 * parser returns either a parsed
 * [AgentPlan] or a typed rejection.
 *
 * - [Parsed] carries the [AgentPlan].
 * - [Unparseable] carries the human-
 *   readable reason. The user can
 *   rephrase the goal, or (future) opt
 *   into the LLM parser.
 */
sealed class ParserOutcome {
    data class Parsed(val plan: AgentPlan) : ParserOutcome()
    data class Unparseable(val reason: String) : ParserOutcome()
}
