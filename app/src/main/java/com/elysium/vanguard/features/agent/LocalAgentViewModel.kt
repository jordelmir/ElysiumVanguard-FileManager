package com.elysium.vanguard.features.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.agent.AgentAction
import com.elysium.vanguard.core.runtime.agent.AgentPlan
import com.elysium.vanguard.core.runtime.agent.ExecutionOutcome
import com.elysium.vanguard.core.runtime.agent.NaturalLanguageGoal
import com.elysium.vanguard.core.runtime.agent.NaturalLanguageParser
import com.elysium.vanguard.core.runtime.agent.ParserOutcome
import com.elysium.vanguard.core.runtime.agent.PlanExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 73 — the ViewModel for the rule-based
 * Vanguard AI ("Local Agent" route).
 *
 * The HTTP-gateway Command Core has its own
 * ViewModel
 * ([com.elysium.vanguard.features.commandcore.AgentCommandViewModel]).
 * This one is the **rule-based, our-IP, no
 * third-party LLM** path that the master vision
 * section 8 calls "the agent that operates the
 * platform":
 *
 * > "Instalar una distro. Resolver dependencias.
 * > Crear un entorno Windows. Seleccionar Wine,
 * > Box64, FEX o QEMU. Diagnosticar errores.
 * > Interpretar logs. Optimizar flags de
 * > compilación. Generar scripts. Explicar
 * > consumo de recursos. Reparar configuraciones
 * > dañadas. Crear snapshots antes de modificar
 * > un entorno. Revertir automáticamente cuando
 * > una operación falle."
 *
 * The ViewModel drives the
 * [com.elysium.vanguard.core.runtime.agent.NaturalLanguageParser]
 * + [com.elysium.vanguard.core.runtime.agent.PlanExecutor]
 * pair. The user types a goal in English or
 * Spanish; the parser produces an
 * [com.elysium.vanguard.core.runtime.agent.AgentPlan];
 * the executor dispatches each
 * [com.elysium.vanguard.core.runtime.agent.AgentAction]
 * to the [com.elysium.vanguard.core.runtime.agent.RealAgentCollaborators].
 *
 * The ViewModel exposes a [LocalAgentUiState] to
 * the Compose UI: the latest message, the
 * proposed plan (for the "review + confirm"
 * step), the outcome, and a list of recent
 * exchanges.
 */
@HiltViewModel
class LocalAgentViewModel @Inject constructor(
    private val parser: NaturalLanguageParser,
    private val executor: PlanExecutor,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LocalAgentUiState(
            intro = "Local Agent is rule-based and on-device. No external LLM. " +
                "Try: \"install debian-stable\", \"snapshot ws-1 baseline\", " +
                "\"rollback ws-1 latest\", \"build rust cargo build --release\", " +
                "\"run ls -la /sdcard\"."
        )
    )
    val state: StateFlow<LocalAgentUiState> = _state.asStateFlow()

    /**
     * Parse the user's text into a plan and
     * immediately execute it. The executor's
     * `Refused` outcome (HIGH-risk plan without
     * user confirmation) is surfaced as a
     * confirmation request in the UI; the
     * `Failure` outcome rolls back + surfaces the
     * error; the `Success` outcome records the
     * plan + step results.
     */
    fun submit(text: String, autoConfirm: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val goal = NaturalLanguageGoal(
            text = trimmed,
            languageCode = "en-US",
            autoConfirm = autoConfirm,
        )
        // 1. Parse first so the UI can show the
        // user the proposed plan before any
        // action runs.
        when (val parseResult = parser.parse(goal)) {
            is ParserOutcome.Unparseable -> {
                _state.value = _state.value.copy(
                    exchange = _state.value.exchange.copy(
                        userText = trimmed,
                        assistantText = "I couldn't parse that goal: " +
                            parseResult.reason,
                        proposedPlan = null,
                        lastOutcome = null,
                    ),
                )
                return
            }
            is ParserOutcome.Parsed -> {
                val plan = parseResult.plan
                _state.value = _state.value.copy(
                    exchange = _state.value.exchange.copy(
                        userText = trimmed,
                        assistantText = plan.describe(),
                        proposedPlan = plan,
                        lastOutcome = null,
                    ),
                )
                // 2. Execute. The executor's
                // `Refused` outcome short-circuits
                // when the plan is HIGH-risk and
                // the user hasn't confirmed; the
                // UI surfaces the proposed plan
                // and asks for confirmation.
                execute(plan)
            }
        }
    }

    /**
     * Run a previously-parsed plan. Called
     * after the user confirms a HIGH-risk plan
     * (the `Refused` outcome from
     * [PlanExecutor.execute]).
     */
    fun confirmExecution(plan: AgentPlan) {
        execute(plan)
    }

    /**
     * Clear the exchange. The UI calls this when
     * the user wants to start a new conversation.
     */
    fun reset() {
        _state.value = _state.value.copy(
            exchange = LocalAgentExchange(),
        )
    }

    private fun execute(plan: AgentPlan) {
        viewModelScope.launch {
            // The executor's collaborators do
            // blocking I/O (download, fork); run
            // them on Dispatchers.IO.
            val outcome = withContext(Dispatchers.IO) {
                executor.execute(plan)
            }
            _state.value = _state.value.copy(
                exchange = _state.value.exchange.copy(
                    proposedPlan = null,
                    lastOutcome = outcome,
                    assistantText = when (outcome) {
                        is ExecutionOutcome.Success ->
                            "Done. ${plan.actions.size} action(s) succeeded:\n" +
                                outcome.stepResults.joinToString("\n") {
                                    "  • " + describe(it)
                                }
                        is ExecutionOutcome.Refused ->
                            "Refused: ${outcome.reason}\n\n" +
                                "Plan:\n${plan.describe()}\n\n" +
                                "Tap 'Confirm' to approve this HIGH-risk plan."
                        is ExecutionOutcome.Failure ->
                            "Failed at action ${outcome.failedActionIndex + 1} " +
                                "of ${plan.actions.size}: ${outcome.failureMessage}\n" +
                                if (outcome.rolledBack) "Rolled back to pre-execution snapshot."
                                else "Rollback failed or was not attempted."
                    },
                ),
            )
        }
    }

    private fun describe(result: com.elysium.vanguard.core.runtime.agent.AgentStepResult): String =
        when (result) {
            is com.elysium.vanguard.core.runtime.agent.AgentStepResult.Success ->
                result.message.ifBlank { "ok" }
            is com.elysium.vanguard.core.runtime.agent.AgentStepResult.Failure ->
                result.message
        }
}

/**
 * The ViewModel's UI state. The [exchange] is
 * the latest user→assistant turn; the UI renders
 * it as a chat-like list. [intro] is the
 * one-time greeting the screen shows on first
 * load.
 */
data class LocalAgentUiState(
    val intro: String = "",
    val exchange: LocalAgentExchange = LocalAgentExchange(),
)

/**
 * A single user→assistant exchange. The user
 * types a goal; the assistant shows the parsed
 * plan (if any), the proposed plan awaiting
 * confirmation, the execution outcome, and a
 * human-readable summary of the result.
 */
data class LocalAgentExchange(
    val userText: String = "",
    val assistantText: String = "",
    val proposedPlan: AgentPlan? = null,
    val lastOutcome: ExecutionOutcome? = null,
) {
    val hasHighRiskPending: Boolean
        get() = proposedPlan != null &&
            proposedPlan.riskLevel ==
            com.elysium.vanguard.core.runtime.agent.RiskLevel.HIGH &&
            !proposedPlan.goal.autoConfirm
}
