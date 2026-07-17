package com.elysium.vanguard

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * PHASE 44 — the Hilt-aware test runner.
 *
 * The default [AndroidJUnitRunner] returns the
 * project's [com.elysium.vanguard.TitanApp] as
 * the host Application; Hilt's test rule
 * ([dagger.hilt.android.testing.HiltAndroidRule])
 * requires the host to be a
 * [HiltTestApplication] instead. This runner
 * swaps the Application class for the duration
 * of the test, so every `@HiltAndroidTest`
 * instrumented test runs against the test graph.
 *
 * Wired in [com.elysium.vanguard.app]'s
 * `defaultConfig.testInstrumentationRunner`.
 *
 * Why not just `@HiltAndroidTest` + `HiltAndroidRule`?
 * The rule handles the *test* (it injects via the
 * rule's `hiltComponent()`), but the host
 * Application must still be a Hilt test app for
 * the rule's bindings to take effect at the
 * process level. This runner is the process-level
 * counterpart.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
