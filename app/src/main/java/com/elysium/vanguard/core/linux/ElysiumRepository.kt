package com.elysium.vanguard.core.linux

/**
 * Phase 73 second half — the **Elysium Repository**.
 *
 * The repository is the source of signed
 * [ElysiumPackageManifest]s. The repository:
 *   - Stores the manifests indexed by
 *     `(name, version)`.
 *   - Returns the manifest for a given
 *     `(name, version)` lookup.
 *   - Lists the available packages (the
 *     manifest names).
 *   - Lists the versions of a given package.
 *
 * The repository is the **only** seam the
 * package manager uses to fetch a manifest.
 * The production implementation reads from
 * a signed repository (a future Phase 73
 * increment). The test implementation is an
 * in-memory map.
 *
 * The repository is **typed**: the manifest
 * is the typed value; the content hash + the
 * signature are part of the manifest (the
 * consumer verifies the signature after
 * fetching).
 */
interface ElysiumRepository {

    /**
     * Fetch a manifest by `(name, version)`.
     * Returns `null` when the manifest is not
     * in the repository.
     */
    fun fetchManifest(name: String, version: ElysiumPackageVersion): ElysiumPackageManifest?

    /**
     * List the available versions of a package.
     * The list is sorted by version descending
     * (the latest version is first).
     */
    fun listVersions(name: String): List<ElysiumPackageVersion>

    /**
     * List the available packages (the manifest
     * names). The list is sorted alphabetically.
     */
    fun listPackages(): List<String>

    /**
     * The total number of manifests in the
     * repository.
     */
    fun size(): Int
}

/**
 * An in-memory [ElysiumRepository] for testing.
 * The repository is a `Map<String, Map<ElysiumPackageVersion,
 * ElysiumPackageManifest>>` keyed by name + version.
 * The `addManifest` method verifies the
 * manifest's signature + content hash before
 * storing.
 *
 * The repository is **thread-safe** (the
 * underlying map is a `ConcurrentHashMap`).
 */
class InMemoryElysiumRepository : ElysiumRepository {

    private val byName: java.util.concurrent.ConcurrentHashMap<String,
        java.util.concurrent.ConcurrentHashMap<String, ElysiumPackageManifest>> =
        java.util.concurrent.ConcurrentHashMap()

    override fun fetchManifest(
        name: String,
        version: ElysiumPackageVersion,
    ): ElysiumPackageManifest? =
        byName[name]?.get(version.canonical)

    override fun listVersions(name: String): List<ElysiumPackageVersion> =
        byName[name]?.keys?.map { ElysiumPackageVersion.parse(it).getOrThrow() }
            ?.sortedDescending()
            ?: emptyList()

    override fun listPackages(): List<String> =
        byName.keys.sorted()

    override fun size(): Int =
        byName.values.sumOf { it.size }

    /**
     * Add a manifest to the repository. The
     * repository verifies the manifest's
     * signature against the expected signing
     * key. A failed verification is a hard
     * rejection.
     *
     * The function returns `Result.success(Unit)`
     * on success; `Result.failure(...)` on
     * verification failure.
     */
    fun addManifest(
        manifest: ElysiumPackageManifest,
        expectedSigningKey: String,
    ): Result<Unit> {
        // Verify the signature.
        val expectedSignature = com.elysium.vanguard.foundry.core.ontology.primitives.Signature(
            value = expectedSigningKey,
        )
        val verifyResult = manifest.verifySignature(expectedSignature)
        if (verifyResult.isFailure) {
            return Result.failure(
                verifyResult.exceptionOrNull()
                    ?: IllegalStateException("signature verification failed"),
            )
        }
        // Add the manifest.
        val versions = byName.computeIfAbsent(manifest.name) {
            java.util.concurrent.ConcurrentHashMap()
        }
        versions[manifest.version.canonical] = manifest
        return Result.success(Unit)
    }
}
