package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import java.io.File
import java.security.MessageDigest

/**
 * The Market's install seam. The installer is the
 * **only legitimate way** to install a listing.
 *
 * The flow:
 *   1. The consumer supplies a `listingId` + a
 *      `byteSource` (the artifact's bytes) + a
 *      `targetDir` + a `verifyingKey`.
 *   2. The installer reads the listing from the
 *      catalog.
 *   3. The installer reads the bytes from the
 *      `byteSource` (for Phase 1: an in-memory
 *      `ByteArray`; for Phase 2: a URL/HTTP
 *      source).
 *   4. The installer verifies the listing's
 *      signature (against the `verifyingKey`).
 *   5. The installer verifies the bytes' content
 *      hash (against the listing's `contentHash`).
 *   6. The installer writes the bytes to
 *      `targetDir/<listingId>`.
 *   7. The installer returns an `InstallReceipt`.
 *
 * Any failure is a hard rejection. The install
 * MUST NOT proceed past a failed verification.
 */
interface MarketInstaller {

    fun install(request: InstallRequest): Result<InstallReceipt>
}

/**
 * A typed request to install a listing. The
 * `byteSource` is a functional interface (the
 * Phase 2 impl uses an HTTP source; the Phase 1
 * impl uses an in-memory `ByteArray`).
 */
data class InstallRequest(
    val listingId: String,
    val byteSource: () -> ByteArray,
    val targetDir: File,
    val verifyingKey: ByteArray,
)

/**
 * The receipt returned from a successful install.
 * The receipt is the only proof that the install
 * happened. The receipt is content-addressed
 * (the `artifactHash` is the SHA-256 of the
 * installed bytes).
 */
data class InstallReceipt(
    val listingId: String,
    val installedPath: File,
    val artifactHash: String,
    val signatureKeyId: String,
    val bytesInstalled: Long,
)

/**
 * The Phase 1 in-memory implementation of the
 * `MarketInstaller`. The installer reads the
 * bytes from the `byteSource` + verifies the
 * signature + verifies the content hash + writes
 * to the target directory.
 */
class LocalMarketInstaller(
    private val catalog: MarketCatalog,
) : MarketInstaller {

    override fun install(request: InstallRequest): Result<InstallReceipt> {
        val listing = catalog.getById(request.listingId)
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "MarketInstaller.listingId",
                    reason = "no listing with id ${request.listingId} in the catalog",
                ),
            )

        // Step 4: verify the signature.
        if (!MarketSigning.verify(listing, request.verifyingKey)) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "MarketInstaller.signature",
                    reason = "signature verification failed for listing ${listing.id}",
                ),
            )
        }

        // Step 3: read the bytes.
        val bytes = request.byteSource()

        // Step 5: verify the content hash.
        val actualHash = sha256Hex(bytes)
        if (actualHash != listing.contentHash.value) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "MarketInstaller.contentHash",
                    reason = "content hash mismatch for listing ${listing.id}: expected ${listing.contentHash.value}, got $actualHash",
                ),
            )
        }

        // Step 6: write the bytes.
        val targetFile = File(request.targetDir, listing.id)
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(bytes)

        // Step 7: return the receipt.
        return Result.success(
            InstallReceipt(
                listingId = listing.id,
                installedPath = targetFile,
                artifactHash = actualHash,
                signatureKeyId = listing.signatureKeyId,
                bytesInstalled = bytes.size.toLong(),
            ),
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
