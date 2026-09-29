package com.elysium.vanguard

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Instrumented-test host activity (declared unexported in the
 * main manifest, referenced only by androidTest).
 *
 * `createComposeRule()` launches a plain ComponentActivity, which
 * is NOT a Hilt component holder — screens that call
 * `hiltViewModel()` internally (e.g.
 * [com.elysium.vanguard.core.runtime.ui.MainScreen]) then fail
 * with "component holder does not implement GeneratedComponent".
 * A `@AndroidEntryPoint` activity IS a holder, so those screens
 * can be mounted directly without driving the real NavHost
 * (splash → dashboard → route), which depends on real-time
 * delays and infinite dashboard animations that Espresso cannot
 * idle through.
 *
 * The class lives in the main source set because an activity
 * declared in the main manifest must be loadable by the target
 * APK's classloader (a test-APK activity would resolve to a
 * different process and fail to launch).
 */
@AndroidEntryPoint
class MainScreenTestHost : ComponentActivity()
