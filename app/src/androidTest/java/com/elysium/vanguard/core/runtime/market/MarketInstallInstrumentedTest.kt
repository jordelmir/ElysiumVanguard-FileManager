package com.elysium.vanguard.core.runtime.market

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 64 — Instrumented test for the Market's
 * publish + install flow on a real Android device.
 *
 * Why this is an `androidTest` (not a JVM test):
 * the install path needs a real file system
 * (`/data/data/<package>/files/`) + the file
 * permissions. The JVM test uses `Files.createTempDirectory`
 * which works in the unit test classpath but
 * not in the production filesystem layout.
 *
 * The test runs on:
 *   - A real Android device (USB-connected)
 *   - An Android emulator (x86_64, arm64-v8a)
 *   - The Robolectric emulator (in-process)
 *
 * The test does NOT require:
 *   - A network connection
 *   - The Vanguard Cloud
 *   - A real signing key (uses a hard-coded
 *     test key)
 */
@RunWith(AndroidJUnit4::class)
class MarketInstallInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val signingKey = "phase-64-instrumented-test-key".toByteArray()
    private val publisherId = "publisher:instrumented-test"

    @Test
    fun `publish_then_install_round-trip_succeeds_in_app_private_storage`() {
        val catalog = InMemoryMarketCatalog()
        val publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = signingKey,
            publisherId = publisherId,
        )
        val installer = LocalMarketInstaller(catalog = catalog)

        val artifactBytes = ByteArray(2048) { (it % 256).toByte() }
        val contentHash = ContentHash.of(artifactBytes)

        val draft = MarketListingDraft(
            id = "com.elysium.vanguard:test-app:1.0.0",
            name = "Test App",
            type = MarketListingType.APP,
            version = "1.0.0",
            contentHash = contentHash,
            sizeBytes = artifactBytes.size.toLong(),
            dependencies = emptyList(),
            tags = listOf("test", "instrumented"),
        )

        val published = publisher.publish(draft).getOrThrow()
        assertEquals(publisherId, published.signatureKeyId)

        // Install into the app's private files dir.
        val targetDir = File(context.filesDir, "market-install-test")
        targetDir.deleteRecursively()
        val receipt = installer.install(
            InstallRequest(
                listingId = draft.id,
                byteSource = { artifactBytes },
                targetDir = targetDir,
                verifyingKey = signingKey,
            ),
        ).getOrThrow()

        assertEquals(contentHash.value, receipt.artifactHash)
        assertTrue("installed file should exist", receipt.installedPath.exists())
        assertEquals(artifactBytes.size.toLong(), receipt.bytesInstalled)

        // Cleanup.
        targetDir.deleteRecursively()
    }

    @Test
    fun `install_rejects_when_bytes_do_not_match_content_hash`() {
        val catalog = InMemoryMarketCatalog()
        val publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = signingKey,
            publisherId = publisherId,
        )
        val installer = LocalMarketInstaller(catalog = catalog)

        val draft = MarketListingDraft(
            id = "com.elysium.vanguard:test-app:2.0.0",
            name = "Test App 2",
            type = MarketListingType.APP,
            version = "2.0.0",
            contentHash = ContentHash.of("real-bytes"),
            sizeBytes = 100L,
            dependencies = emptyList(),
            tags = listOf("test"),
        )
        publisher.publish(draft)

        val wrongBytes = "wrong-bytes".toByteArray()
        val targetDir = File(context.filesDir, "market-install-rejection-test")
        targetDir.deleteRecursively()
        val result = installer.install(
            InstallRequest(
                listingId = draft.id,
                byteSource = { wrongBytes },
                targetDir = targetDir,
                verifyingKey = signingKey,
            ),
        )
        assertTrue("expected failure on hash mismatch", result.isFailure)
        assertTrue("no file should be written on failure", targetDir.listFiles()?.isEmpty() != false)
    }
}
