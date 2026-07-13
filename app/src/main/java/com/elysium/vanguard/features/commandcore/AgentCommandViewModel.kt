package com.elysium.vanguard.features.commandcore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.ai.AgentGatewayHttpClient
import com.elysium.vanguard.core.ai.AgentGatewaySettingsStore
import com.elysium.vanguard.core.ai.AgentFunctionOutput
import com.elysium.vanguard.core.ai.AgentLocalToolExecutor
import com.elysium.vanguard.core.ai.AgentToolCall
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommandCoreMessage(
    val author: CommandCoreAuthor,
    val text: String
)

enum class CommandCoreAuthor { USER, CORE }

data class AgentCommandUiState(
    val endpoint: String = "http://localhost:8787",
    val isConfigured: Boolean = false,
    val isWorking: Boolean = false,
    val gatewayStatus: String = "NOT CONFIGURED",
    val messages: List<CommandCoreMessage> = listOf(
        CommandCoreMessage(
            CommandCoreAuthor.CORE,
            "Command Core is local-first. Connect a protected gateway to plan workspace actions."
        )
    ),
    val proposedActions: List<AgentToolCall> = emptyList(),
    val activeResponseId: String? = null,
    val error: String? = null
)

@HiltViewModel
class AgentCommandViewModel @Inject constructor(
    private val settingsStore: AgentGatewaySettingsStore,
    private val gateway: AgentGatewayHttpClient,
    private val localTools: AgentLocalToolExecutor
) : ViewModel() {
    private val _state = MutableStateFlow(AgentCommandUiState())
    val state: StateFlow<AgentCommandUiState> = _state.asStateFlow()

    init {
        refreshSettings()
    }

    fun saveGateway(endpoint: String, gatewayToken: String) {
        viewModelScope.launch {
            runCatching { settingsStore.save(endpoint, gatewayToken) }
                .onSuccess {
                    refreshSettings(checkHealth = true)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(error = error.message.orEmpty().ifBlank { "Gateway configuration failed" })
                }
        }
    }

    fun clearGateway() {
        settingsStore.clear()
        refreshSettings()
    }

    fun send(message: String) {
        val prompt = message.trim()
        if (prompt.isEmpty() || _state.value.isWorking) return
        viewModelScope.launch {
            val connection = settingsStore.readConnection()
            if (connection == null) {
                _state.value = _state.value.copy(error = "Configure the protected gateway token first")
                return@launch
            }
            _state.value = _state.value.copy(
                isWorking = true,
                error = null,
                proposedActions = emptyList(),
                messages = _state.value.messages + CommandCoreMessage(CommandCoreAuthor.USER, prompt)
            )
            runCatching {
                gateway.startTurn(
                    connection = connection,
                    message = prompt,
                    context = listOf("surface=android-command-core", "tool-execution=approval-gated"),
                    safetyIdentifier = safetyIdentifier()
                )
            }.onSuccess { response ->
                val assistantText = response.text.ifBlank {
                    if (response.toolCalls.isEmpty()) "Command Core completed the planning turn."
                    else "I prepared ${response.toolCalls.size} scoped action(s) for review."
                }
                _state.value = _state.value.copy(
                    isWorking = false,
                    gatewayStatus = "CONNECTED",
                    activeResponseId = response.responseId,
                    messages = _state.value.messages + CommandCoreMessage(CommandCoreAuthor.CORE, assistantText),
                    proposedActions = response.toolCalls
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    isWorking = false,
                    gatewayStatus = "UNREACHABLE",
                        error = error.message.orEmpty().ifBlank { "Command Core request failed" }
                )
            }
        }
    }

    /** Executes one user-reviewed typed operation, then resumes the model with its result. */
    fun executeApproved(action: AgentToolCall) {
        val previousResponseId = _state.value.activeResponseId
        if (_state.value.isWorking || previousResponseId.isNullOrBlank()) return
        viewModelScope.launch {
            val connection = settingsStore.readConnection()
            if (connection == null) {
                _state.value = _state.value.copy(error = "Protected gateway configuration is missing")
                return@launch
            }
            _state.value = _state.value.copy(
                isWorking = true,
                error = null,
                proposedActions = _state.value.proposedActions.filterNot { it.callId == action.callId },
                messages = _state.value.messages + CommandCoreMessage(
                    CommandCoreAuthor.CORE,
                    "Executing approved ${action.name.replace('_', ' ')} locally…"
                )
            )
            val output: AgentFunctionOutput = localTools.execute(action)
            runCatching {
                gateway.continueTurn(
                    connection = connection,
                    previousResponseId = previousResponseId,
                    toolOutputs = listOf(output),
                    safetyIdentifier = safetyIdentifier()
                )
            }.onSuccess { response ->
                val assistantText = response.text.ifBlank {
                    "The approved action returned ${output.output["status"] ?: "a result"}."
                }
                _state.value = _state.value.copy(
                    isWorking = false,
                    gatewayStatus = "CONNECTED",
                    activeResponseId = response.responseId,
                    messages = _state.value.messages + CommandCoreMessage(CommandCoreAuthor.CORE, assistantText),
                    proposedActions = response.toolCalls
                )
            }.onFailure { error ->
                // The action did run locally. Keep its result visible even if the
                // provider cannot produce a next turn, rather than hiding it.
                _state.value = _state.value.copy(
                    isWorking = false,
                    gatewayStatus = "UNREACHABLE",
                    messages = _state.value.messages + CommandCoreMessage(
                        CommandCoreAuthor.CORE,
                        "Local result: ${output.output["status"] ?: "complete"}. Gateway continuation failed."
                    ),
                    error = error.message.orEmpty().ifBlank { "Gateway continuation failed" }
                )
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun refreshSettings(checkHealth: Boolean = false) {
        val settings = settingsStore.current()
        _state.value = _state.value.copy(
            endpoint = settings.endpoint,
            isConfigured = settings.isConfigured,
            gatewayStatus = if (settings.isConfigured) "READY" else "NOT CONFIGURED",
            error = null
        )
        if (checkHealth && settings.isConfigured) {
            viewModelScope.launch {
                runCatching { gateway.health(settings.endpoint) }
                    .onSuccess { health ->
                        _state.value = _state.value.copy(gatewayStatus = health.status.uppercase())
                    }
                    .onFailure {
                        _state.value = _state.value.copy(gatewayStatus = "UNREACHABLE")
                    }
            }
        }
    }

    private fun safetyIdentifier(): String {
        return settingsStore.currentSafetyIdentifier()
    }
}
