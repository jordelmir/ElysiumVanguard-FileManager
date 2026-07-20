package com.elysium.vanguard.features.killswitch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.security.KillSwitch
import com.elysium.vanguard.core.security.KillSwitchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 100 — the **kill switch** view model.
 *
 * The view model holds the 3-step confirm
 * state + the kill switch result. The 3
 * steps are:
 *
 * 1. **Read** the warning + type `WIPE` to
 *    enable the button.
 * 2. **Press** "Continue" to advance to the
 *    final step.
 * 3. **Press** "EXECUTE" to trigger the
 *    wipe.
 *
 * The view model is intentionally
 * **deliberately cumbersome** — a kill
 * switch is a one-way operation; the
 * friction is the feature.
 */
@HiltViewModel
class KillSwitchViewModel @Inject constructor(
    private val killSwitch: KillSwitch,
) : ViewModel() {

    /**
     * The required confirm text. The user
     * must type this verbatim in the input
     * field; any other text is rejected.
     */
    companion object {
        const val CONFIRM_TEXT: String = "WIPE"
        const val MAX_REASON_LENGTH: Int = 200
    }

    private val _state = MutableStateFlow(KillSwitchUiState())
    val state: StateFlow<KillSwitchUiState> = _state.asStateFlow()

    /**
     * Set the typed text. The state machine
     * advances to [KillSwitchStep.READY_TO_CONFIRM]
     * when the text matches [CONFIRM_TEXT] and
     * the reason is non-blank.
     */
    fun onConfirmTextChange(text: String) {
        val step = computeStep(text, _state.value.reason)
        _state.update { it.copy(confirmText = text, step = step) }
    }

    /**
     * Set the reason. The reason is recorded
     * in the audit event when the kill switch
     * triggers.
     */
    fun onReasonChange(reason: String) {
        val trimmed = if (reason.length > MAX_REASON_LENGTH) {
            reason.substring(0, MAX_REASON_LENGTH)
        } else {
            reason
        }
        val step = computeStep(_state.value.confirmText, trimmed)
        _state.update { it.copy(reason = trimmed, step = step) }
    }

    /**
     * Advance to the next step. The user
     * pressed "Continue" — the view moves
     * from READY_TO_CONFIRM to READY_TO_EXECUTE.
     */
    fun onContinue() {
        if (_state.value.step == KillSwitchStep.READY_TO_CONFIRM) {
            _state.update { it.copy(step = KillSwitchStep.READY_TO_EXECUTE) }
        }
    }

    /**
     * Go back one step. The user pressed
     * "Back" on the final screen.
     */
    fun onBack() {
        if (_state.value.step == KillSwitchStep.READY_TO_EXECUTE) {
            _state.update { it.copy(step = KillSwitchStep.READY_TO_CONFIRM) }
        } else if (_state.value.step == KillSwitchStep.READY_TO_CONFIRM) {
            _state.update { it.copy(step = KillSwitchStep.INITIAL) }
        }
    }

    /**
     * Reset the state machine to the initial
     * step. Used when the user dismisses the
     * screen without triggering.
     */
    fun reset() {
        _state.update { KillSwitchUiState() }
    }

    /**
     * Trigger the kill switch. The method
     * launches a coroutine; the result is
     * published via [state.lastResult].
     */
    fun execute() {
        if (_state.value.step != KillSwitchStep.READY_TO_EXECUTE) return
        val reason = _state.value.reason.ifBlank { "user-initiated" }
        _state.update { it.copy(isExecuting = true) }
        viewModelScope.launch {
            val result = killSwitch.trigger(reason)
            _state.update {
                it.copy(
                    isExecuting = false,
                    lastResult = result,
                    step = when (result) {
                        is KillSwitchResult.Success -> KillSwitchStep.COMPLETED
                        is KillSwitchResult.AlreadyTriggered -> KillSwitchStep.COMPLETED
                        is KillSwitchResult.Failure -> KillSwitchStep.READY_TO_EXECUTE
                    },
                )
            }
        }
    }

    private fun computeStep(confirmText: String, reason: String): KillSwitchStep {
        val typedCorrectly = confirmText == CONFIRM_TEXT
        val reasonOk = reason.isNotBlank()
        return when {
            !typedCorrectly || !reasonOk -> KillSwitchStep.INITIAL
            else -> KillSwitchStep.READY_TO_CONFIRM
        }
    }
}

/**
 * The state machine of the kill switch
 * screen.
 */
enum class KillSwitchStep {
    /** The user just opened the screen. */
    INITIAL,

    /** The user typed `WIPE` + a reason. */
    READY_TO_CONFIRM,

    /** The user pressed "Continue". */
    READY_TO_EXECUTE,

    /** The kill switch completed successfully. */
    COMPLETED,
}

/**
 * The view state.
 */
data class KillSwitchUiState(
    val step: KillSwitchStep = KillSwitchStep.INITIAL,
    val confirmText: String = "",
    val reason: String = "",
    val isExecuting: Boolean = false,
    val lastResult: KillSwitchResult? = null,
)
