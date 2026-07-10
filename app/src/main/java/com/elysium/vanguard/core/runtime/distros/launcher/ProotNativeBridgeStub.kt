package com.elysium.vanguard.core.runtime.distros.launcher

/**
 * PHASE 9.6.4 — Stub for the JNI bridge.
 *
 * Real proot is invoked through JNI when the bundled `libproot.so`
 * is present. Until that binary ships (Jor cross-compiles per
 * `proot/INSTALL.md`), the bridge stays in the UNLOADED state and
 * the [NativeProotLauncher] keeps reporting `isAvailable == false`.
 *
 * Why not stub out the JNI calls more aggressively: when proot lands
 * we want minimal Kotlin surface to wire up. Today's stub exposes
 * just the status probe; `invoke(args)` will be added in the same
 * commit that drops the .so.
 *
 * Phase 9.6.4 — first build; intentionally minimal.
 */
class ProotNativeBridgeStub {

    enum class LoadingState { UNLOADED, ATTEMPTING, LOADED, FAILED }

    @Volatile
    var loadingState: LoadingState = LoadingState.UNLOADED
        private set

    fun attemptLoad(libraryPath: String?): Boolean {
        if (loadingState == LoadingState.LOADED) return true
        loadingState = LoadingState.ATTEMPTING
        return try {
            // Real implementation would call System.load(libraryPath)
            // or System.loadLibrary("proot"). Neither is available on
            // the JVM test classpath, so we don't actually load.
            loadingState = LoadingState.UNLOADED
            false
        } catch (_: Throwable) {
            loadingState = LoadingState.FAILED
            false
        }
    }

    fun isLoaded(): Boolean = loadingState == LoadingState.LOADED
}
