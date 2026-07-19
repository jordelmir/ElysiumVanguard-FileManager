package com.elysium.vanguard.core.linux

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature

/**
 * Phase 73 third half (I-73.3.1) — the **Elysium Runtime Layer Catalog**.
 *
 * The catalog is the **in-memory collection** of
 * [ElysiumRuntimeLayerManifest]s available for the
 * user's device. The catalog is populated from the
 * Elysium Linux repository (a future Phase 73
 * increment; for now the catalog ships with the
 * defaults below).
 *
 * The catalog is the **only seam** the runtime
 * uses to discover which layers are available. The
 * orchestrator asks: "what is the latest Mesa
 * Turnip layer for ARM64?" — the catalog answers
 * (or returns null if the layer is not in the
 * catalog).
 *
 * The catalog is **read-only** at runtime: layers
 * are added at install time + verified + stored.
 * Once verified, the catalog is the source of
 * truth for "which layers are on this device".
 *
 * The catalog is **thread-safe** (the underlying
 * map is a `ConcurrentHashMap`).
 */
class ElysiumRuntimeLayerCatalog {

    /**
     * The manifests, indexed by `id.value`. The
     * map is keyed by the id (e.g. `"mesa-turnip"`)
     * + the value is the **list of versions**
     * (a layer can have multiple versions
     * installed side-by-side — Mesa 24.0 and
     * Mesa 24.1 are both available).
     */
    private val byLayerId:
        java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.ConcurrentHashMap<String, ElysiumRuntimeLayerManifest>> =
        java.util.concurrent.ConcurrentHashMap()

    /**
     * Add a layer manifest to the catalog. The
     * catalog verifies the manifest's signature
     * against the expected signing key. A failed
     * verification is a hard rejection.
     *
     * Returns `Result.success(Unit)` on success;
     * `Result.failure(...)` on verification failure.
     */
    fun addLayer(
        manifest: ElysiumRuntimeLayerManifest,
        expectedSigningKey: String,
    ): Result<Unit> {
        val expectedSignature = Signature(value = expectedSigningKey)
        val verifyResult = manifest.verifySignature(expectedSignature)
        if (verifyResult.isFailure) {
            return Result.failure(
                verifyResult.exceptionOrNull()
                    ?: IllegalStateException("signature verification failed"),
            )
        }
        val versions = byLayerId.computeIfAbsent(manifest.id.value) {
            java.util.concurrent.ConcurrentHashMap()
        }
        versions[manifest.version.canonical] = manifest
        return Result.success(Unit)
    }

    /**
     * Find a specific layer version. Returns `null`
     * when the layer is not in the catalog.
     */
    fun find(
        id: ElysiumRuntimeLayerId,
        version: ElysiumPackageVersion,
    ): ElysiumRuntimeLayerManifest? =
        byLayerId[id.value]?.get(version.canonical)

    /**
     * Find the latest version of a layer. The
     * "latest" is the highest semver (per the
     * Phase 73 first half [ElysiumPackageVersion]
     * `Comparable` contract).
     *
     * Returns `null` when the layer is not in the
     * catalog.
     */
    fun latest(id: ElysiumRuntimeLayerId): ElysiumRuntimeLayerManifest? =
        byLayerId[id.value]?.values?.maxByOrNull { it.version }

    /**
     * Find the latest version of a layer for a
     * specific host ABI. E.g. the latest Mesa
     * Turnip for ARM64 — the catalog skips the
     * X86_64 build (Turnip is Adreno-only).
     *
     * Returns `null` when no layer with the
     * requested id + host ABI is in the catalog.
     */
    fun latestForAbi(
        id: ElysiumRuntimeLayerId,
        hostAbi: ElysiumAbi,
    ): ElysiumRuntimeLayerManifest? =
        byLayerId[id.value]?.values
            ?.filter { it.hostAbi == hostAbi }
            ?.maxByOrNull { it.version }

    /**
     * List all versions of a layer. The list is
     * sorted by version descending (the latest
     * version is first).
     */
    fun listVersions(id: ElysiumRuntimeLayerId): List<ElysiumRuntimeLayerManifest> =
        byLayerId[id.value]?.values?.sortedByDescending { it.version }
            ?: emptyList()

    /**
     * List all layer ids in the catalog. The list
     * is sorted alphabetically.
     */
    fun listLayerIds(): List<ElysiumRuntimeLayerId> =
        byLayerId.keys.sorted().map { ElysiumRuntimeLayerId(it) }

    /**
     * The total number of layer manifests in the
     * catalog (the count across all ids + all
     * versions).
     */
    fun size(): Int = byLayerId.values.sumOf { it.size }

    /**
     * Build the [ElysiumRuntimeLayer] (the
     * sealed-class instance) for a specific layer
     * version. The catalog looks up the manifest
     * and returns the corresponding layer object.
     *
     * Returns `null` when the layer is not in the
     * catalog.
     */
    fun asLayer(
        id: ElysiumRuntimeLayerId,
        version: ElysiumPackageVersion,
    ): ElysiumRuntimeLayer? {
        val manifest = find(id, version) ?: return null
        return when (id) {
            ElysiumRuntimeLayerId.NATIVE -> ElysiumRuntimeLayer.Native(
                version = manifest.version,
                hostAbi = manifest.hostAbi,
            )
            ElysiumRuntimeLayerId.MESA_TURNIP -> ElysiumRuntimeLayer.MesaTurnip(
                version = manifest.version,
                hostAbi = manifest.hostAbi,
            )
            ElysiumRuntimeLayerId.BOX64 -> ElysiumRuntimeLayer.Box64(
                version = manifest.version,
                hostAbi = manifest.hostAbi,
            )
            ElysiumRuntimeLayerId.FEX -> ElysiumRuntimeLayer.Fex(
                version = manifest.version,
                hostAbi = manifest.hostAbi,
            )
            ElysiumRuntimeLayerId.WINE -> ElysiumRuntimeLayer.Wine(
                version = manifest.version,
                hostAbi = manifest.hostAbi,
            )
            // An unknown id — return null rather
            // than construct a malformed layer.
            else -> null
        }
    }
}

/**
 * The **default Elysium Linux runtime layer
 * catalog** — the layers Elysium Linux ships with
 * out of the box.
 *
 * The defaults are a snapshot of the Elysium Linux
 * distro's official layer set at the time of the
 * Phase 73 third half release. The user can add
 * more layers via the package manager
 * (`pm install com.elysium.runtime.<layer>`).
 *
 * The defaults are **placeholder manifests** for
 * the architecture — the real downloads come from
 * the Elysium Linux repository (Phase 73 third
 * half sub-task I-73.3.2 will populate the real
 * binaries). The placeholder manifests establish
 * the **shape** of the catalog.
 */
object ElysiumRuntimeLayerDefaults {

    /**
     * The default signing key. The real production
     * key is published with the Elysium Linux
     * distribution team's certificate.
     */
    const val DEFAULT_SIGNING_KEY: String = "elysium-linux-test-signing-key"

    /**
     * The default native ARM64 layer. The native
     * layer is the **baseline** — every Elysium
     * Linux install has it.
     */
    val NATIVE_ARM64: ElysiumRuntimeLayerManifest = buildManifest(
        id = ElysiumRuntimeLayerId.NATIVE,
        version = "1.0.0",
        hostAbi = ElysiumAbi.ARM64,
        displayName = "Native (arm64-v8a)",
        capabilities = setOf(ElysiumRuntimeCapability.EXECUTE_NATIVE),
        description = "The native ARM64 baseline. Every " +
            "Elysium Linux install has this layer.",
        homepage = "https://elysium.vanguard/linux/native",
        provides = listOf(
            "elysium-runtime-native",
            "elysium-linux-base",
        ),
        files = listOf(
            ElysiumPackageFile(
                installPath = "/lib/ld-linux-aarch64.so.1",
                contentHash = ContentHash("0".repeat(64)),
            ),
            ElysiumPackageFile(
                installPath = "/usr/lib/elysium/runtime/native/1.0.0/manifest.json",
                contentHash = ContentHash("0".repeat(64)),
            ),
        ),
    )

    /**
     * The default Mesa Turnip layer. Turnip is the
     ** Vulkan driver for Adreno GPUs.
     */
    val MESA_TURNIP_24_1: ElysiumRuntimeLayerManifest = buildManifest(
        id = ElysiumRuntimeLayerId.MESA_TURNIP,
        version = "24.1.0",
        hostAbi = ElysiumAbi.ARM64,
        displayName = "Mesa Turnip 24.1.0",
        capabilities = setOf(
            ElysiumRuntimeCapability.GPU_VULKAN,
            ElysiumRuntimeCapability.GPU_VULKAN_TURNIP,
        ),
        description = "Mesa Turnip is the open-source " +
            "Vulkan driver for Qualcomm Adreno GPUs. " +
            "It is the GPU acceleration layer for Vulkan " +
            "capsules on Adreno devices.",
        homepage = "https://mesa.freedesktop.org/",
        deps = listOf(
            ElysiumPackageDependency(
                packageName = "com.elysium.linux.kernel.adreno",
                constraint = VersionConstraint(
                    kind = ConstraintKind.GTE,
                    version = ElysiumPackageVersion(6, 1, 0),
                ),
            ),
        ),
        provides = listOf(
            "elysium-runtime-vulkan",
            "elysium-runtime-turnip",
            "libvulkan.so.1",
        ),
        files = listOf(
            ElysiumPackageFile(
                installPath = "/usr/lib/elysium/runtime/mesa-turnip/24.1.0/libvulkan_adreno.so",
                contentHash = ContentHash("0".repeat(64)),
                permissions = FilePermissions(mode = 0x1ED),
            ),
            ElysiumPackageFile(
                installPath = "/usr/lib/elysium/runtime/mesa-turnip/24.1.0/icd.d/adreno_icd.json",
                contentHash = ContentHash("0".repeat(64)),
            ),
        ),
    )

    /**
     * The default Box64 layer. Box64 is the
     ** user-mode x86_64 binary translator.
     */
    val BOX64_0_3_2: ElysiumRuntimeLayerManifest = buildManifest(
        id = ElysiumRuntimeLayerId.BOX64,
        version = "0.3.2",
        hostAbi = ElysiumAbi.ARM64,
        displayName = "Box64 0.3.2",
        capabilities = setOf(ElysiumRuntimeCapability.EXECUTE_X86_64),
        description = "Box64 is the user-mode x86_64 " +
            "binary translator for ARM64. It lets the " +
            "orchestrator launch x86_64 Linux binaries " +
            "on an ARM64 device without a full VM.",
        homepage = "https://box86.org/",
        deps = listOf(
            ElysiumPackageDependency(
                packageName = "com.elysium.linux.glibc",
                constraint = VersionConstraint(
                    kind = ConstraintKind.GTE,
                    version = ElysiumPackageVersion(2, 38, 0),
                ),
            ),
        ),
        provides = listOf(
            "elysium-runtime-x86-64",
            "box64",
        ),
        files = listOf(
            ElysiumPackageFile(
                installPath = "/usr/bin/box64",
                contentHash = ContentHash("0".repeat(64)),
                permissions = FilePermissions(mode = 0x1ED),
            ),
            ElysiumPackageFile(
                installPath = "/usr/lib/elysium/runtime/box64/0.3.2/libbox64.so",
                contentHash = ContentHash("0".repeat(64)),
                permissions = FilePermissions(mode = 0x1A4),
            ),
        ),
    )

    /**
     * The default FEX layer. FEX is the
     ** user-mode x86 (32-bit) binary translator.
     */
    val FEX_2404: ElysiumRuntimeLayerManifest = buildManifest(
        id = ElysiumRuntimeLayerId.FEX,
        version = "2404.0.0",
        hostAbi = ElysiumAbi.ARM64,
        displayName = "FEX-Emu 2404",
        capabilities = setOf(ElysiumRuntimeCapability.EXECUTE_X86),
        description = "FEX-Emu is the user-mode x86 " +
            "(32-bit) binary translator for ARM64. " +
            "It runs legacy 32-bit x86 binaries.",
        homepage = "https://fex-emu.org/",
        deps = listOf(
            ElysiumPackageDependency(
                packageName = "com.elysium.linux.glibc",
                constraint = VersionConstraint(
                    kind = ConstraintKind.GTE,
                    version = ElysiumPackageVersion(2, 38, 0),
                ),
            ),
        ),
        provides = listOf(
            "elysium-runtime-x86",
            "fex",
        ),
        files = listOf(
            ElysiumPackageFile(
                installPath = "/usr/bin/FEXInterpreter",
                contentHash = ContentHash("0".repeat(64)),
                permissions = FilePermissions(mode = 0x1ED),
            ),
            ElysiumPackageFile(
                installPath = "/usr/lib/elysium/runtime/fex/2404.0/libFEX.so",
                contentHash = ContentHash("0".repeat(64)),
                permissions = FilePermissions(mode = 0x1A4),
            ),
        ),
    )

    /**
     * The default Wine layer. Wine is the
     ** Windows API re-implementation for Linux.
     */
    val WINE_9_0: ElysiumRuntimeLayerManifest = buildManifest(
        id = ElysiumRuntimeLayerId.WINE,
        version = "9.0.0",
        hostAbi = ElysiumAbi.ARM64,
        displayName = "Wine 9.0",
        capabilities = setOf(ElysiumRuntimeCapability.EXECUTE_WINDOWS),
        description = "Wine is the Windows API " +
            "re-implementation for Linux. It lets the " +
            "orchestrator launch Windows PE binaries " +
            "(.exe, .msi) on the Elysium Linux runtime.",
        homepage = "https://www.winehq.org/",
        deps = listOf(
            ElysiumPackageDependency(
                packageName = "com.elysium.linux.glibc",
                constraint = VersionConstraint(
                    kind = ConstraintKind.GTE,
                    version = ElysiumPackageVersion(2, 38, 0),
                ),
            ),
            ElysiumPackageDependency(
                packageName = "com.elysium.linux.fontconfig",
                constraint = VersionConstraint(
                    kind = ConstraintKind.ANY,
                    version = ElysiumPackageVersion(0, 0, 0),
                ),
            ),
        ),
        provides = listOf(
            "elysium-runtime-windows",
            "wine",
        ),
        files = listOf(
            ElysiumPackageFile(
                installPath = "/usr/bin/wine",
                contentHash = ContentHash("0".repeat(64)),
                permissions = FilePermissions(mode = 0x1ED),
            ),
            ElysiumPackageFile(
                installPath = "/usr/lib/elysium/runtime/wine/9.0.0/wine/x86_64-unix/wine",
                contentHash = ContentHash("0".repeat(64)),
                permissions = FilePermissions(mode = 0x1A4),
            ),
        ),
    )

    /**
     * The default catalog: every default layer.
     * The catalog is the seed of the user's
     * runtime layer catalog.
     */
    val ALL: List<ElysiumRuntimeLayerManifest> = listOf(
        NATIVE_ARM64,
        MESA_TURNIP_24_1,
        BOX64_0_3_2,
        FEX_2404,
        WINE_9_0,
    )

    /**
     * Build a manifest, signing it with the given
     * key. The signature is computed over the
     * canonical form (which excludes the signature
     * field itself).
     */
    private fun buildManifest(
        id: ElysiumRuntimeLayerId,
        version: String,
        hostAbi: ElysiumAbi,
        displayName: String,
        capabilities: Set<ElysiumRuntimeCapability>,
        description: String,
        homepage: String,
        deps: List<ElysiumPackageDependency> = emptyList(),
        provides: List<String> = emptyList(),
        files: List<ElysiumPackageFile> = emptyList(),
        signingKey: String = DEFAULT_SIGNING_KEY,
    ): ElysiumRuntimeLayerManifest {
        val v = ElysiumPackageVersion.parse(version).getOrThrow()
        val unsigned = ElysiumRuntimeLayerManifest(
            id = id,
            version = v,
            hostAbi = hostAbi,
            displayName = displayName,
            capabilities = capabilities,
            dependencies = deps,
            provides = provides,
            files = files,
            description = description,
            homepage = homepage,
            contentHash = ContentHash("0".repeat(64)),
            signature = Signature("placeholder"),
        )
        return unsigned.copy(
            signature = Signature.sign(
                payload = unsigned.canonicalForm.toByteArray(Charsets.UTF_8),
                key = signingKey.toByteArray(),
            ),
        )
    }
}
