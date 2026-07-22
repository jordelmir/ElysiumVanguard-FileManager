package com.elysium.vanguard.core.runtime.agent

import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 57 + 108 — the natural-language parser.
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
 * **Single-action rules** (Phase 57):
 * - `install <distro>` → `InstallDistro`
 *   (MEDIUM).
 * - `create windows` / `run windows` /
 *   `set up windows` →
 *   `CreateWindowsEnvironment` (MEDIUM).
 * - `snapshot <workspace>` → `CreateSnapshot`
 *   (MEDIUM).
 * - `rollback <workspace>` →
 *   `RollbackToSnapshot` (MEDIUM).
 * - `build <toolchain> <command>` →
 *   `RunBuild` (LOW).
 * - `run <command>` → `RunCommand` (HIGH).
 *
 * **Phase 108 new rules**:
 * - `configure <runtime>` /
 *   `configura <runtime>` → `ConfigureRuntime`
 *   (LOW). The "configura Vulkan" example
 *   from the vision's gap list.
 * - `create shortcut` / `crea acceso directo`
 *   / `add to desktop` → `CreateShortcut`
 *   (LOW). The "crea acceso directo" example
 *   from the vision's gap list.
 * - `publish <capsule>` / `publica
 *   <capsule>` → `PublishCapsule` (LOW).
 *
 * **Multi-action goals** (Phase 108):
 * - The parser splits a goal on `,` or `;`
 *   or `and then` / `luego` / `y luego`.
 *   Each sub-clause is parsed independently.
 *   The resulting plan carries every
 *   sub-action in order.
 * - The plan's riskLevel is the MAX of all
 *   sub-actions' risk levels.
 * - If ANY sub-clause fails to parse, the
 *   whole plan fails (typed [ParserOutcome.Unparseable]).
 *   The user must rephrase; the parser does
 *   NOT silently drop an unparseable
 *   sub-clause.
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
        val subClauses = splitSubClauses(normalised)
        if (subClauses.isEmpty()) {
            return ParserOutcome.Unparseable(
                reason = "empty goal after normalisation"
            )
        }

        // PHASE 108 — multi-action goals. Parse
        // each sub-clause independently; combine
        // the actions into a single plan; the
        // risk is the max.
        val actions = ArrayList<AgentAction>(subClauses.size)
        val risks = ArrayList<RiskLevel>(subClauses.size)
        for (clause in subClauses) {
            val parsed = tryParseSingle(goal, clause)
            if (parsed == null) {
                return ParserOutcome.Unparseable(
                    reason = "no rule matched sub-clause: '$clause' " +
                        "(in: '${goal.text}')"
                )
            }
            actions += parsed.first
            risks += parsed.second
        }
        return ParserOutcome.Parsed(
            AgentPlan(
                id = idGenerator(),
                actions = actions,
                riskLevel = risks.max(),
                createdAtMs = clock(),
                goal = goal,
            )
        )
    }

    /**
     * PHASE 108 — split a normalised goal into
     * sub-clauses. The split rules:
     *
     *  - `,` (comma) — always a split
     *  - `;` (semicolon) — always a split
     *  - ` and then ` / ` y luego ` — split
     *    (the most explicit "multi-step" marker)
     *  - ` then ` / ` luego ` — split
     *    (shorter form)
     *  - ` and ` / ` y ` between verbs — NOT a
     *    split (e.g. "build and run" is one
     *    action with two sub-clauses joined
     *    by "and" — ambiguous; the parser
     *    prefers the longer "and then"
     *    marker).
     *
     * Empty sub-clauses (e.g. from a trailing
     * `,`) are dropped.
     */
    private fun splitSubClauses(normalised: String): List<String> {
        // Replace the explicit multi-step markers
        // with commas so the split is uniform.
        val marked = normalised
            .replace(Regex("\\s+and then\\s+"), ",")
            .replace(Regex("\\s+y luego\\s+"), ",")
            .replace(Regex("\\s+then\\s+"), ",")
            .replace(Regex("\\s+luego\\s+"), ",")
        return marked.split(Regex("[,;]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Try to parse a single sub-clause. Returns
     * the [AgentAction] + its [RiskLevel], or
     * null if no rule matched.
     */
    private fun tryParseSingle(
        goal: NaturalLanguageGoal,
        normalised: String
    ): Pair<AgentAction, RiskLevel>? {
        // English / Spanish install rule.
        val distroMatch = INSTALL_REGEX.find(normalised)
        if (distroMatch != null) {
            return AgentAction.InstallDistro(distroId = distroMatch.groupValues[1]) to RiskLevel.MEDIUM
        }

        // English / Spanish create-windows rule.
        val windowsMatch = WINDOWS_REGEX.find(normalised)
        if (windowsMatch != null) {
            val binary = windowsMatch.groupValues[1]
            val runtimeKind = when {
                normalised.contains("qemu") || normalised.contains("vm") -> "QEMU_VM"
                normalised.contains("fex") -> "WINE_FEX"
                else -> "WINE_BOX64"
            }
            return AgentAction.CreateWindowsEnvironment(
                binaryPath = binary,
                runtimeKind = runtimeKind
            ) to RiskLevel.MEDIUM
        }

        // English / Spanish snapshot rule.
        val snapshotMatch = SNAPSHOT_REGEX.find(normalised)
        if (snapshotMatch != null) {
            val workspaceId = snapshotMatch.groupValues[1]
            return AgentAction.CreateSnapshot(
                workspaceId = workspaceId,
                label = "agent-${idGenerator().take(8)}"
            ) to RiskLevel.MEDIUM
        }

        // English / Spanish rollback rule.
        val rollbackMatch = ROLLBACK_REGEX.find(normalised)
        if (rollbackMatch != null) {
            val workspaceId = rollbackMatch.groupValues[1]
            return AgentAction.RollbackToSnapshot(
                workspaceId = workspaceId,
                snapshotId = "latest"
            ) to RiskLevel.HIGH
        }

        // English / Spanish build rule.
        val buildMatch = BUILD_REGEX.find(normalised)
        if (buildMatch != null) {
            val toolchain = buildMatch.groupValues[1].uppercase()
            val command = buildMatch.groupValues[2]
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            return AgentAction.RunBuild(
                toolchainKind = toolchain,
                command = command
            ) to RiskLevel.LOW
        }

        // English / Spanish run-command rule.
        val runMatch = RUN_REGEX.find(normalised)
        if (runMatch != null) {
            val command = runMatch.groupValues[1]
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            return AgentAction.RunCommand(command = command) to RiskLevel.HIGH
        }

        // ====================================================================
        // PHASE 108 NEW RULES
        // ====================================================================

        // `create shortcut [a <app>` / `crea acceso directo [a <app>]` /
        // `add <app> to desktop` / `anade <app> al escritorio`.
        // The target app is optional (default: "default" — the executor
        // resolves it from the plan's other actions).
        val shortcutMatch = SHORTCUT_REGEX.find(normalised)
        if (shortcutMatch != null) {
            // The SHORTCUT_REGEX has two alternation
            // branches (English + Spanish), each
            // with its own capture group (group 1
            // and group 2). The matched branch's
            // group has the target; the other's
            // group is empty.
            val target = shortcutMatch.groupValues[1]
                .ifBlank { shortcutMatch.groupValues[2] }
                .ifBlank { "default" }
            val displayName = target
            return AgentAction.CreateShortcut(
                targetAppId = target,
                displayName = displayName,
            ) to RiskLevel.LOW
        }

        // `configure <runtime>` / `configura <runtime>` /
        // `setup <runtime>` / `habilita <runtime>` /
        // `enable <runtime>` / `deshabilita <runtime>` /
        // `disable <runtime>`.
        val configureMatch = CONFIGURE_REGEX.find(normalised)
        if (configureMatch != null) {
            val runtime = configureMatch.groupValues[1].uppercase()
            val operation = when {
                normalised.contains("disable") || normalised.contains("deshabilita")
                    || normalised.contains("off") -> "disable"
                else -> "enable"
            }
            return AgentAction.ConfigureRuntime(
                runtime = runtime,
                operation = operation,
            ) to RiskLevel.LOW
        }

        // `publish <capsule>` / `publica <capsule>` /
        // `submit <capsule>`.
        val publishMatch = PUBLISH_REGEX.find(normalised)
        if (publishMatch != null) {
            val capsule = publishMatch.groupValues[1]
            val channel = when {
                normalised.contains("beta") -> "beta"
                normalised.contains("internal") -> "internal"
                else -> "stable"
            }
            return AgentAction.PublishCapsule(
                capsuleId = capsule,
                targetChannel = channel,
            ) to RiskLevel.LOW
        }

        return null
    }

    private fun normalise(text: String): String =
        text.lowercase().trim().replace(Regex("\\s+"), " ")

    companion object {
        // English / Spanish install: "install
        // <distro>" / "instalar <distro>".
        // NOTE: "setup" is intentionally NOT in
        // the verb list — "setup proot" is
        // a configure-runtime action, not
        // an install (the previous design
        // confused "setup proot" with
        // InstallDistro("setup proot")).
        private val INSTALL_REGEX = Regex(
            "(?:install|instalar|instala)\\s+([a-z][a-z0-9._-]+)"
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

        // PHASE 108 — shortcut rule. The target
        // app is optional; the display name
        // defaults to the target.
        //
        // English:
        //   "create shortcut"
        //   "create shortcut to blender"
        //   "add blender to desktop"
        //   "add shortcut for blender"
        // Spanish:
        //   "crea acceso directo"
        //   "crea acceso directo a blender"
        //   "anade blender al escritorio"
        //   "anade acceso directo para blender"
        //
        // Structure (English): create/add <shortcut-noun> [preposition <target>] [preposition <desktop-noun>]
        // Structure (Spanish): crea/anade [un] <acceso-directo> [preposicion <target>] [preposicion <escritorio>]
        //
        // The target is a NON-EMPTY word; the
        // preposition is required if the target
        // is present. The desktop-noun is fully
        // optional. This avoids the regex
        // matching "crea acceso directo " with
        // target="" (an earlier version did that).
        private val SHORTCUT_REGEX = Regex(
            "(?:" +
                "(?:create|add)\\s+(?:shortcut|acceso directo|accesos?\\s+directos?)\\s*" +
                "(?:(?:to|for)\\s+([a-z][a-z0-9._-]+))?" +
                "|" +
                "(?:crea|anade|haz)\\s+(?:un\\s+)?(?:acceso\\s+directo|accesos?\\s+directos?)\\s*" +
                "(?:(?:a|para|de)\\s+([a-z][a-z0-9._-]+))?" +
            ")"
        )

        // PHASE 108 — configure-runtime rule.
        // "configure vulkan" / "configura
        // vulkan" / "setup dxvk" / "enable
        // proot" / "disable fex" / "habilita
        // vulkan" / "deshabilita fex".
        //
        // The runtime is captured; the
        // operation (enable/disable) is
        // derived from the verb in the input.
        private val CONFIGURE_REGEX = Regex(
            "(?:configure|configura|setup|set up|enable|disable|" +
                "habilita|deshabilita|activa|desactiva)\\s+" +
                "(vulkan|opengl|opengl es|dxvk|vkd3d|vkd3d-proton|" +
                "wine|fex|box64|proot|namespaced|namespaced launcher)"
        )

        // PHASE 108 — publish-capsule rule.
        // "publish com.example.myapp.arm64" /
        // "publica com.example.myapp" /
        // "submit com.example.myapp" /
        // "publish com.example.myapp to beta".
        private val PUBLISH_REGEX = Regex(
            "(?:publish|publica|submit|envia)\\s+" +
                "([a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+)"
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
