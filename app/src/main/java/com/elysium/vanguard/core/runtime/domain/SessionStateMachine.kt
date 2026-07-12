package com.elysium.vanguard.core.runtime.domain

/** Serializes and validates runtime lifecycle transitions. */
class SessionStateMachine(initial: SessionState = SessionState.Created) {
    @Volatile
    private var current: SessionState = initial

    fun state(): SessionState = current

    @Synchronized
    fun transition(next: SessionState): SessionState {
        check(isAllowed(current, next)) {
            "invalid session transition: ${current.name()} -> ${next.name()}"
        }
        current = next
        return next
    }

    private fun isAllowed(from: SessionState, to: SessionState): Boolean {
        if (from is SessionState.Stopped || from is SessionState.Failed) return false
        if (to is SessionState.Failed) return true
        return when (from) {
            SessionState.Created -> to is SessionState.Validating
            SessionState.Validating -> to is SessionState.Preparing
            SessionState.Preparing -> to is SessionState.Starting
            SessionState.Starting -> to is SessionState.Running
            is SessionState.Running ->
                to is SessionState.Suspending || to is SessionState.Stopping
            SessionState.Suspending ->
                to is SessionState.Suspended || to is SessionState.Stopping
            SessionState.Suspended ->
                to is SessionState.Recovering || to is SessionState.Stopping
            SessionState.Recovering ->
                to is SessionState.Running || to is SessionState.Stopping
            SessionState.Stopping -> to is SessionState.Stopped
            is SessionState.Stopped,
            is SessionState.Failed -> false
        }
    }

    private fun SessionState.name(): String = this::class.simpleName ?: "Unknown"
}
