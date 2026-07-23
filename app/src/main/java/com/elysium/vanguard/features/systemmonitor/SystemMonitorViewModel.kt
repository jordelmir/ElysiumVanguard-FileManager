package com.elysium.vanguard.features.systemmonitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.system.SystemInfoProvider
import com.elysium.vanguard.core.system.SystemSample
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PHASE 114 — the **system monitor
 * ViewModel**. The VM polls the
 * [SystemInfoProvider] at a configurable
 * interval + exposes the rolling history
 * to the UI.
 *
 * The VM has two responsibilities:
 *  - **Polling**: kick off a coroutine
 *    that samples the provider every
 *    [pollIntervalMs] (default: 1
 *    second) + appends the new sample
 *    to a ring buffer.
 *  - **Exposing state**: the rolling
 *    history is exposed as a
 *    `StateFlow<List<SystemSample>>`.
 *    The UI subscribes via
 *    `collectAsState` + renders the
 *    history.
 *
 * **Why a ring buffer (not an
 * unbounded list)**: the platform
 * could poll for hours; an unbounded
 * list would grow indefinitely + the
 * UI would render thousands of
 * samples. The buffer caps at
 * [MAX_HISTORY] (60 samples = 1
 * minute at the default interval).
 *
 * **Why `viewModelScope.launch`**:
 * the coroutine is tied to the
 * ViewModel's lifecycle. When the
 * ViewModel is cleared (the user
 * navigates away), the coroutine is
 * cancelled + the polling stops.
 */
@HiltViewModel
class SystemMonitorViewModel @Inject constructor(
    private val provider: SystemInfoProvider,
) : ViewModel() {

    private val _history = MutableStateFlow<List<SystemSample>>(emptyList())
    val history: StateFlow<List<SystemSample>> = _history.asStateFlow()

    /**
     * The most recent sample. The UI
     * uses this for the "current
     * values" display.
     */
    val currentSample: SystemSample?
        get() = _history.value.lastOrNull()

    private var pollJob: Job? = null

    /**
     * Start the polling loop. The
     * function is idempotent: calling
     * it twice does not start a second
     * loop (the existing one is
     * cancelled first).
     */
    fun startPolling(intervalMs: Long = DEFAULT_POLL_INTERVAL_MS) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            try {
                while (isActive) {
                    val sample = try {
                        provider.sample()
                    } catch (e: Exception) {
                        // A failed sample is
                        // silently dropped (the
                        // next sample is taken
                        // after the delay). The
                        // UI keeps showing the
                        // last successful
                        // sample.
                        null
                    }
                    if (sample != null) {
                        _history.value = (_history.value + sample)
                            .takeLast(MAX_HISTORY)
                    }
                    try {
                        delay(intervalMs)
                    } catch (e: CancellationException) {
                        // The coroutine was
                        // cancelled during the
                        // delay. Exit cleanly:
                        // the parent scope sees
                        // a normal completion
                        // (not a failure).
                        break
                    }
                }
            } catch (e: CancellationException) {
                // Safety net: any other
                // suspension point that
                // throws CancellationException
                // is caught here. The
                // exception is intentionally
                // not re-thrown — the parent
                // scope sees a normal
                // completion.
            }
        }
    }

    /**
     * Stop the polling loop. The
     * function is a no-op when polling
     * is not running.
     */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 1000L
        const val MAX_HISTORY: Int = 60
    }
}
