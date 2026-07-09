package com.elysium.vanguard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PHASE 0.8 — Smoke instrumentation test.
 *
 * Validates that the application class can be instantiated without throwing
 * (Hilt graph builds, ContentProvider FileProvider resolves, etc.). This is
 * the cheapest possible "does the APK even start" check.
 *
 * Real flow tests (file ops, SAF, document viewer rendering) belong in
 * Phase 1 alongside the actual feature implementation.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    @Test
    fun applicationContext_isNotNull() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertNotNull(context)
        assertEquals("com.elysium.vanguard.debug", context.packageName)
    }

    @Test
    fun fileProvider_authorityResolves() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Verify the FileProvider declared in the manifest is queryable.
        // Without this we would discover missing provider config only at runtime
        // when a media player tries to expose a file:// URI.
        val pm = context.packageManager
        val providerInfo = pm.resolveContentProvider(
            "${context.packageName}.fileprovider",
            0
        )
        assertNotNull("FileProvider must be declared in AndroidManifest", providerInfo)
    }
}