package com.elysium.vanguard.features.commandcore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.ai.AgentGatewayHttpClient
import com.elysium.vanguard.core.ai.AgentGatewaySettingsStore
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
    val error: String? = null
)

@HiltViewModel
class AgentCommandViewModel @Inject constructor(
    private val settingsStore: AgentGatewaySettingsStore,
    private val gateway: AgentGatewayHttpClient
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
