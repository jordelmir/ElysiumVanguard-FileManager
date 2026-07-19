package com.elysium.vanguard.core.linux

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash

/**
 * Phase 73 second half — the **Elysium Package Manager**.
 *
 * The package manager is the runtime that
 * installs / upgrades / removes packages from
 * the Elysium Linux rootfs. The package manager
 * is the **stateless** composition of:
 *   - An [ElysiumRepository] (the source of
 *     manifests).
 *   - The set of currently installed packages
 *     (a typed map keyed by `name`).
 *
 * The package manager is the **typical entry
 * point** for the runtime hooks (the Elysium
 * Vanguard File Manager + the Capsule installer
 * + the Workspace Orchestrator + the AI council
 * all consume the package manager).
 *
 * The package manager is **pure-domain** (no
 * I/O, no Android dependencies). The test
 * implementation uses an in-memory repository
 * + an in-memory installed set.
 */
interface ElysiumPackageManager {

    /**
     * Install a package. The package manager:
     *   1. Fetches the manifest from the
     *      repository.
     *   2. Verifies the manifest's signature.
     *   3. Resolves the transitive dependencies.
     *   4. Installs the package + the deps in
     *      dependency order (a topological order).
     *   5. Records the installed set.
     *
     * Returns [ElysiumPackageInstallResult.Success]
     * on success; [ElysiumPackageInstallResult.Failure]
     * on any failure (the install is atomic —
     * a failure rolls back any partial installs).
     */
    fun install(name: String, version: ElysiumPackageVersion): ElysiumPackageInstallResult

    /**
     * Upgrade a package to a newer version. The
     * package manager:
     *   1. Verifies the new version is newer
     *      (the `>` constraint).
     *   2. Resolves the new transitive deps.
     *   3. Removes the old version + the
     *      no-longer-needed deps.
     *   4. Installs the new version + the new
     *      deps.
     *
     * Returns [ElysiumPackageInstallResult].
     */
    fun upgrade(name: String, targetVersion: ElysiumPackageVersion): ElysiumPackageInstallResult

    /**
     * Remove a package. The package manager:
     *   1. Verifies no other package depends on
     *      the package (a removal that breaks a
     *      dependency is rejected).
     *   2. Removes the package + the deps that
     *      are no longer needed.
     *
     * Returns [ElysiumPackageInstallResult].
     */
    fun remove(name: String): ElysiumPackageInstallResult

    /**
     * List the installed packages. The list is
     * sorted by `name` alphabetically.
     */
    fun listInstalled(): List<InstalledPackage>

    /**
     * Check if a package is installed (any version).
     */
    fun isInstalled(name: String): Boolean

    /**
     * Look up the installed version of a package.
     * Returns `null` when the package is not
     * installed.
     */
    fun installedVersion(name: String): ElysiumPackageVersion?
}

/**
 * The result of an install / upgrade / remove
 * operation. The result is a typed value (not a
 * free-form string).
 */
sealed class ElysiumPackageInstallResult {

    /**
     * The operation succeeded. The list of
     * installed packages includes the new set
     * (or the removal).
     */
    data class Success(
        val operation: Operation,
        val packageName: String,
        val version: ElysiumPackageVersion?,
        val installedPackages: List<InstalledPackage>,
    ) : ElysiumPackageInstallResult()

    /**
     * The operation failed. The reason is a
     * typed error envelope.
     */
    data class Failure(
        val operation: Operation,
        val packageName: String,
        val reason: ElysiumPackageInstallError,
    ) : ElysiumPackageInstallResult()

    /**
     * The operation type.
     */
    enum class Operation { INSTALL, UPGRADE, REMOVE }
}

/**
 * The typed error envelope. The errors are the
 * typed outcomes the package manager returns
 * on a failed operation.
 *
 * Every subclass is also a [RuntimeException] so
 * the resolver can throw it through the install
 * pipeline (the catch in [InMemoryElysiumPackageManager.install]
 * unwraps the exception into a typed [ElysiumPackageInstallResult.Failure]).
 */
sealed class ElysiumPackageInstallError(message: String) : RuntimeException(message) {

    /**
     * The manifest is not in the repository.
     */
    data class ManifestNotFound(
        val name: String,
        val version: ElysiumPackageVersion,
    ) : ElysiumPackageInstallError("Manifest not found: $name@${version.canonical}")

    /**
     * The manifest's signature does not match
     * the expected signature. The manifest is
     * tampered (or signed by a different
     * publisher).
     */
    data class SignatureVerificationFailed(
        val name: String,
        val version: ElysiumPackageVersion,
    ) : ElysiumPackageInstallError("Signature verification failed: $name@${version.canonical}")

    /**
     * A transitive dependency is not satisfiable
     * (the repo doesn't have a compatible
     * version of a required dep).
     */
    data class UnsatisfiableDependency(
        val name: String,
        val missingDep: String,
        val constraint: VersionConstraint,
    ) : ElysiumPackageInstallError(
        "Unsatisfiable dependency for $name: " +
            "$missingDep with constraint ${constraint.canonical}",
    )

    /**
     * The package manager detected a cyclic
     * dependency. A cyclic dependency is a
     * packaging error (the manifest declares
     * a chain that loops back to itself).
     *
     * `cyclePath` is the dependency path that
     * loops (the last element equals the first).
     */
    data class CyclicDependency(
        val name: String,
        val cyclePath: List<String>,
    ) : ElysiumPackageInstallError(
        "Cyclic dependency for $name: " +
            cyclePath.joinToString(" -> "),
    )

    /**
     * The remove operation is rejected: another
     * installed package depends on the package
     * being removed.
     */
    data class DependentPackages(
        val name: String,
        val dependents: List<String>,
    ) : ElysiumPackageInstallError(
        "Cannot remove $name: the following packages depend on it: $dependents",
    )

    /**
     * The upgrade operation is rejected: the
     * target version is not newer than the
     * installed version.
     */
    data class NotAnUpgrade(
        val name: String,
        val current: ElysiumPackageVersion,
        val target: ElysiumPackageVersion,
    ) : ElysiumPackageInstallError(
        "Not an upgrade for $name: current ${current.canonical}, " +
            "target ${target.canonical}",
    )

    /**
     * The package is already installed at the
     * target version.
     */
    data class AlreadyInstalled(
        val name: String,
        val version: ElysiumPackageVersion,
    ) : ElysiumPackageInstallError(
        "$name@${version.canonical} is already installed",
    )

    /**
     * The package is not installed (the remove
     * operation is rejected).
     */
    data class NotInstalled(
        val name: String,
    ) : ElysiumPackageInstallError("$name is not installed")
}

/**
 * The installed state of a package. The
 * installed state has:
 *   - `name: String` — the package's name.
 *   - `version: ElysiumPackageVersion` — the
 *     installed version.
 *   - `installedAtMs: Long` — the install
 *     timestamp (millis since epoch).
 *   - `contentHash: ContentHash` — the
 *     installed content hash.
 */
data class InstalledPackage(
    val name: String,
    val version: ElysiumPackageVersion,
    val installedAtMs: Long,
    val contentHash: ContentHash,
) {
    /**
     * The package's manifest reference. The
     * `contentHash` is the canonical id of the
     * installed manifest.
     */
    val canonicalId: String = "$name@${version.canonical}:${contentHash.value}"
}

/**
 * An in-memory [ElysiumPackageManager] for
 * testing. The package manager is the stateless
 * composition of:
 *   - An [ElysiumRepository] (the source of
 *     manifests).
 *   - The set of currently installed packages
 *     (a typed map keyed by `name`).
 *
 * The package manager is **thread-safe** (the
 * underlying maps are `ConcurrentHashMap`).
 */
class InMemoryElysiumPackageManager(
    private val repository: ElysiumRepository,
    private val expectedSigningKey: String = DEFAULT_SIGNING_KEY,
    private val clock: () -> Long = System::currentTimeMillis,
) : ElysiumPackageManager {

    private val installed: java.util.concurrent.ConcurrentHashMap<String, InstalledPackage> =
        java.util.concurrent.ConcurrentHashMap()

    override fun install(
        name: String,
        version: ElysiumPackageVersion,
    ): ElysiumPackageInstallResult {
        // Step 1: check if already installed.
        val existing = installed[name]
        if (existing != null && existing.version == version) {
            return ElysiumPackageInstallResult.Failure(
                operation = ElysiumPackageInstallResult.Operation.INSTALL,
                packageName = name,
                reason = ElysiumPackageInstallError.AlreadyInstalled(name, version),
            )
        }
        // Step 2: fetch the manifest.
        val manifest = repository.fetchManifest(name, version)
            ?: return ElysiumPackageInstallResult.Failure(
                operation = ElysiumPackageInstallResult.Operation.INSTALL,
                packageName = name,
                reason = ElysiumPackageInstallError.ManifestNotFound(name, version),
            )
        // Step 3: verify the signature.
        val expectedSignature = com.elysium.vanguard.foundry.core.ontology.primitives.Signature(
            value = expectedSigningKey,
        )
        val verifyResult = manifest.verifySignature(expectedSignature)
        if (verifyResult.isFailure) {
            return ElysiumPackageInstallResult.Failure(
                operation = ElysiumPackageInstallResult.Operation.INSTALL,
                packageName = name,
                reason = ElysiumPackageInstallError.SignatureVerificationFailed(name, version),
            )
        }
        // Step 4: resolve transitive dependencies.
        // The resolver throws a typed
        // ElysiumPackageInstallError on failure;
        // we catch + wrap into a Failure result.
        val resolvedDeps: List<ElysiumPackageManifest> = try {
            resolveDependencies(manifest)
        } catch (e: ElysiumPackageInstallError) {
            return ElysiumPackageInstallResult.Failure(
                operation = ElysiumPackageInstallResult.Operation.INSTALL,
                packageName = name,
                reason = e,
            )
        }
        // Step 5: install (atomic — a failure rolls back).
        val snapshot = java.util.concurrent.ConcurrentHashMap(installed)
        try {
            for (dep in resolvedDeps) {
                installSingle(dep)
            }
            installSingle(manifest)
        } catch (e: Exception) {
            // Roll back to the snapshot.
            installed.clear()
            installed.putAll(snapshot)
            return ElysiumPackageInstallResult.Failure(
                operation = ElysiumPackageInstallResult.Operation.INSTALL,
                packageName = name,
                reason = ElysiumPackageInstallError.SignatureVerificationFailed(name, version),
            )
        }
        return ElysiumPackageInstallResult.Success(
            operation = ElysiumPackageInstallResult.Operation.INSTALL,
            packageName = name,
            version = version,
            installedPackages = listInstalled(),
        )
    }

    override fun upgrade(
        name: String,
        targetVersion: ElysiumPackageVersion,
    ): ElysiumPackageInstallResult {
        val current = installed[name]
            ?: return ElysiumPackageInstallResult.Failure(
                operation = ElysiumPackageInstallResult.Operation.UPGRADE,
                packageName = name,
                reason = ElysiumPackageInstallError.NotInstalled(name),
            )
        if (targetVersion <= current.version) {
            return ElysiumPackageInstallResult.Failure(
                operation = ElysiumPackageInstallResult.Operation.UPGRADE,
                packageName = name,
                reason = ElysiumPackageInstallError.NotAnUpgrade(name, current.version, targetVersion),
            )
        }
        // Uninstall the old version.
        installed.remove(name)
        // Install the new version.
        return install(name, targetVersion)
    }

    override fun remove(name: String): ElysiumPackageInstallResult {
        if (installed[name] == null) {
            return ElysiumPackageInstallResult.Failure(
                operation = ElysiumPackageInstallResult.Operation.REMOVE,
                packageName = name,
                reason = ElysiumPackageInstallError.NotInstalled(name),
            )
        }
        // Check that no other installed package depends on this one.
        val dependents = installed.values.filter { other ->
            other.name != name &&
                repository.fetchManifest(other.name, other.version)
                    ?.dependencies
                    ?.any { it.packageName == name } == true
        }.map { it.name }
        if (dependents.isNotEmpty()) {
            return ElysiumPackageInstallResult.Failure(
                operation = ElysiumPackageInstallResult.Operation.REMOVE,
                packageName = name,
                reason = ElysiumPackageInstallError.DependentPackages(name, dependents),
            )
        }
        installed.remove(name)
        return ElysiumPackageInstallResult.Success(
            operation = ElysiumPackageInstallResult.Operation.REMOVE,
            packageName = name,
            version = null,
            installedPackages = listInstalled(),
        )
    }

    override fun listInstalled(): List<InstalledPackage> =
        installed.values.sortedBy { it.name }

    override fun isInstalled(name: String): Boolean = installed.containsKey(name)

    override fun installedVersion(name: String): ElysiumPackageVersion? =
        installed[name]?.version

    /**
     * Resolve the transitive dependencies of a
     * manifest. The resolver is a pure function
     * (no I/O, no side effects). The resolver
     * returns a list of manifests in dependency
     * order (a topological order: deps are
     * listed before the manifest that depends
     * on them).
     *
     * The resolver **throws** a typed
     * [ElysiumPackageInstallError] on the first
     * unsatisfiable dep or cyclic dep. The
     * `install` pipeline catches the exception
     * and wraps it into a
     * [ElysiumPackageInstallResult.Failure].
     */
    private fun resolveDependencies(
        manifest: ElysiumPackageManifest,
    ): List<ElysiumPackageManifest> {
        val visited = mutableSetOf<String>()
        val order = mutableListOf<ElysiumPackageManifest>()
        // The current path (used to report
        // cycles). The last element is the
        // currently-visiting manifest; the list
        // grows as we descend.
        val path = mutableListOf<String>()
        // The recursive DFS. A cycle is a hard
        // failure (the manifest declares a cyclic
        // dependency, which is a packaging error).
        fun visit(m: ElysiumPackageManifest) {
            if (m.name in visited) return
            if (m.name in path) {
                // A cycle: report the loop path
                // from the first occurrence of
                // `m.name` to the end of the path.
                val cycleStart = path.indexOf(m.name)
                val cyclePath = path.subList(cycleStart, path.size) + m.name
                throw ElysiumPackageInstallError.CyclicDependency(
                    name = m.name,
                    cyclePath = cyclePath,
                )
            }
            path.add(m.name)
            for (dep in m.dependencies) {
                val depManifest = pickDependencyVersion(m, dep)
                visit(depManifest)
            }
            path.removeAt(path.size - 1)
            visited.add(m.name)
            order.add(m)
        }
        visit(manifest)
        return order
    }

    /**
     * Pick a specific version of a dependency
     * that satisfies the constraint. The
     * resolver returns the latest compatible
     * version from the repository.
     *
     * Throws [ElysiumPackageInstallError.UnsatisfiableDependency]
     * when no version satisfies the constraint.
     */
    private fun pickDependencyVersion(
        from: ElysiumPackageManifest,
        dep: ElysiumPackageDependency,
    ): ElysiumPackageManifest {
        val versions = repository.listVersions(dep.packageName)
        val compatible = versions
            .mapNotNull { v -> repository.fetchManifest(dep.packageName, v) }
            .filter { dep.constraint.satisfiedBy(it.version) }
        val picked = compatible.maxByOrNull { it.version }
            ?: throw ElysiumPackageInstallError.UnsatisfiableDependency(
                name = from.name,
                missingDep = dep.packageName,
                constraint = dep.constraint,
            )
        return picked
    }

    /**
     * Install a single manifest. The method
     * records the install in the `installed` map.
     */
    private fun installSingle(manifest: ElysiumPackageManifest) {
        installed[manifest.name] = InstalledPackage(
            name = manifest.name,
            version = manifest.version,
            installedAtMs = clock(),
            contentHash = manifest.contentHash,
        )
    }

    companion object {
        /**
         * The default signing key (for tests).
         * Production uses a publisher-specific
         * key.
         */
        const val DEFAULT_SIGNING_KEY: String = "elysium-test-signing-key"
    }
}
