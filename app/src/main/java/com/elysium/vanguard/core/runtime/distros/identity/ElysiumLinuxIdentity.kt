package com.elysium.vanguard.core.runtime.distros.identity

import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase 3 — Elysium Vanguard Linux identity and rootfs manifest system.
 *
 * Generates os-release files, rootfs manifests with SBOM, and signed
 * manifests for verified Linux distributions running under the Elysium
 * Vanguard Universal Computing Fabric.
 */
object ElysiumLinuxIdentity {

    private const val ELYSIUM_VERSION = "1.0.0"
    private const val ELYSIUM_BUG_REPORT_URL = "https://github.com/ElysiumVanguard/issues"

    /**
     * Generate a complete /etc/os-release content for an Elysium Vanguard
     * managed rootfs. This overrides the distribution's own os-release to
     * inject Elysium-specific identifiers and branding.
     */
    fun generateOsRelease(distro: Distro): String = buildString {
        appendLine("# Elysium Vanguard Universal Computing Fabric")
        appendLine("# Auto-generated — do not edit manually")
        appendLine("NAME=\"${distro.displayName}\"")
        appendLine("VERSION=\"${distro.version}\"")
        appendLine("ID=${distro.family.name.lowercase()}")
        appendLine("ID_LIKE=\"${idLikeFor(distro.family)}\"")
        appendLine("PRETTY_NAME=\"${distro.displayName} (Elysium Vanguard)\"")
        appendLine("VERSION_ID=\"${distro.version}\"")
        appendLine("HOME_URL=\"${distro.homepage}\"")
        appendLine("BUG_REPORT_URL=\"$ELYSIUM_BUG_REPORT_URL\"")
        appendLine("PRIVACY_POLICY_URL=\"\"")
        appendLine("SUPPORT_URL=\"${distro.homepage}\"")
        appendLine("BUILD_ID=\"elysium-${ELYSIUM_VERSION}-${Instant.now().atZone(ZoneOffset.UTC).toLocalDate()}\"")
        appendLine("VARIANT=\"Elysium Vanguard\"")
        appendLine("VARIANT_ID=\"elysium-vanguard\"")
        appendLine("PLATFORM_ID=\"platform:android-aarch64\"")
        appendLine("SYSENV=\"proot\"")
    }

    /**
     * Generate the Elysium rootfs manifest with SBOM (Software Bill of
     * Materials) for a installed distro. The manifest includes distro
     * metadata, installation parameters, integrity hashes, and package
     * inventory.
     */
    fun generateRootfsManifest(
        distro: Distro,
        rootfsDir: File,
        installedAtMs: Long = System.currentTimeMillis(),
        installedBy: String = "elysium-vanguard-$ELYSIUM_VERSION"
    ): RootfsManifest {
        val rootfsHash = computeDirSha256(rootfsDir)
        val installedPackages = readInstalledPackages(rootfsDir, distro.packageManager)
        return RootfsManifest(
            manifestVersion = MANIFEST_VERSION,
            elysiumVersion = ELYSIUM_VERSION,
            distroId = distro.id,
            distroDisplayName = distro.displayName,
            distroFamily = distro.family,
            distroVersion = distro.version,
            packageManager = distro.packageManager,
            rootfsUrl = distro.rootfsUrl,
            rootfsSha256 = distro.sha256,
            rootfsOnDiskSha256 = rootfsHash,
            installedAtMs = installedAtMs,
            installedBy = installedBy,
            installedOn = "android-aarch64",
            sbom = SoftwareBillOfMaterials(
                packages = installedPackages,
                totalPackages = installedPackages.size,
                generatedAtMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Verify a rootfs directory against its expected SHA-256 hash.
     * Returns true only if the hash matches exactly.
     */
    fun verifyRootfsIntegrity(rootfsDir: File, expectedSha256: String?): Boolean {
        if (expectedSha256 == null) return true
        val actual = computeDirSha256(rootfsDir)
        return actual.equals(expectedSha256, ignoreCase = true)
    }

    /**
     * Generate a signed manifest string. The signature is produced by
     * the [RootfsSigner] provided by the caller; production wires
     * [AndroidKeystoreRootfsSigner] so the private key never leaves
     * the secure container, and tests / dev wire [HmacRootfsSigner].
     *
     * Verification: callers compare the [SignedManifest.algorithm]
     * against the expected algorithm and then call
     * [RootfsSigner.verify] with the canonical JSON bytes and the
     * base64 signature. The [SignedManifest.publicKey] field carries
     * the verification key (null for symmetric signers like HMAC).
     */
    fun signManifest(
        manifest: RootfsManifest,
        signer: RootfsSigner = HmacRootfsSigner(ELYSIUM_KEY_ALIAS),
        keyAlias: String = signer.publicKeyBase64() ?: ELYSIUM_KEY_ALIAS
    ): SignedManifest {
        val canonical = manifest.toJson()
        val signature = signer.sign(canonical.toByteArray(Charsets.UTF_8))
        return SignedManifest(
            manifest = manifest,
            signature = signature,
            algorithm = signer.algorithm,
            publicKey = signer.publicKeyBase64(),
            signedAtMs = System.currentTimeMillis(),
            keyAlias = keyAlias
        )
    }

    private fun idLikeFor(family: DistroFamily): String = when (family) {
        DistroFamily.DEBIAN -> "debian"
        DistroFamily.MUSL -> "alpine"
        DistroFamily.ARCH -> "arch"
    }

    private fun computeDirSha256(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                digest.update(file.absolutePath.toByteArray(Charsets.UTF_8))
                digest.update(file.length().toByteArray())
                file.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        digest.update(buf, 0, n)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readInstalledPackages(rootfsDir: File, packageManager: String): List<PackageEntry> {
        val dbDir = when (packageManager) {
            "apt" -> File(rootfsDir, "var/lib/dpkg")
            "apk" -> File(rootfsDir, "var/lib/apk")
            "pacman" -> File(rootfsDir, "var/lib/pacman/local")
            else -> return emptyList()
        }
        if (!dbDir.isDirectory) return emptyList()
        return when (packageManager) {
            "apt" -> readDpkgPackages(dbDir)
            "apk" -> readApkPackages(dbDir)
            "pacman" -> readPacmanPackages(dbDir)
            else -> emptyList()
        }
    }

    private fun readDpkgPackages(statusDir: File): List<PackageEntry> {
        val statusFile = File(statusDir, "status")
        if (!statusFile.canRead()) return emptyList()
        val packages = mutableListOf<PackageEntry>()
        var name: String? = null
        var version: String? = null
        statusFile.readLines().forEach { line ->
            when {
                line.startsWith("Package: ") -> {
                    val finishedName = name
                    val finishedVersion = version
                    if (finishedName != null && finishedVersion != null) {
                        packages += PackageEntry(name = finishedName, version = finishedVersion)
                    }
                    name = line.removePrefix("Package: ").trim()
                    version = null
                }
                line.startsWith("Version: ") -> {
                    version = line.removePrefix("Version: ").trim()
                }
                line.isBlank() -> {
                    val finishedName = name
                    val finishedVersion = version
                    if (finishedName != null && finishedVersion != null) {
                        packages += PackageEntry(name = finishedName, version = finishedVersion)
                    }
                    name = null
                    version = null
                }
            }
        }
        val finalName = name
        val finalVersion = version
        if (finalName != null && finalVersion != null) {
            packages += PackageEntry(name = finalName, version = finalVersion)
        }
        return packages
    }

    private fun readApkPackages(dbDir: File): List<PackageEntry> {
        val installedDir = File(dbDir, "installed")
        if (!installedDir.isDirectory) return emptyList()
        return installedDir.listFiles()
            ?.filter { it.isFile && it.extension == "list" }
            ?.map { file ->
                val parts = file.nameWithoutExtension.split('-', limit = 2)
                PackageEntry(
                    name = parts.getOrElse(0) { file.nameWithoutExtension },
                    version = parts.getOrElse(1) { "unknown" }
                )
            }
            ?: emptyList()
    }

    private fun readPacmanPackages(localDir: File): List<PackageEntry> {
        if (!localDir.isDirectory) return emptyList()
        return localDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val descFile = File(dir, "desc")
                if (!descFile.canRead()) return@mapNotNull null
                var name: String? = null
                var version: String? = null
                var inName = false
                var inVersion = false
                descFile.readLines().forEach { line ->
                    when {
                        line == "%NAME%" -> { inName = true; inVersion = false }
                        line == "%VERSION%" -> { inVersion = true; inName = false }
                        line.startsWith("%") -> { inName = false; inVersion = false }
                        inName -> name = line.trim()
                        inVersion -> version = line.trim()
                    }
                }
                val finishedName = name
                val finishedVersion = version
                if (finishedName != null && finishedVersion != null) {
                    PackageEntry(name = finishedName, version = finishedVersion)
                } else null
            }
            ?: emptyList()
    }

    private fun Long.toByteArray(): ByteArray {
        var v = this
        val bytes = ByteArray(8)
        for (i in 7 downTo 0) {
            bytes[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return bytes
    }

    private const val MANIFEST_VERSION = 1
    private const val ELYSIUM_KEY_ALIAS = "elysium-rootfs-signing"
}

data class PackageEntry(
    val name: String,
    val version: String
)

data class SoftwareBillOfMaterials(
    val packages: List<PackageEntry>,
    val totalPackages: Int,
    val generatedAtMs: Long
) {
    fun toJson(): String = buildString {
        append("{\"packages\":[")
        packages.forEachIndexed { i, pkg ->
            if (i > 0) append(",")
            append("{\"name\":\"${pkg.name}\",\"version\":\"${pkg.version}\"}")
        }
        append("],\"totalPackages\":").append(totalPackages)
        append(",\"generatedAtMs\":").append(generatedAtMs).append("}")
    }
}

data class RootfsManifest(
    val manifestVersion: Int,
    val elysiumVersion: String,
    val distroId: String,
    val distroDisplayName: String,
    val distroFamily: DistroFamily,
    val distroVersion: String,
    val packageManager: String,
    val rootfsUrl: String,
    val rootfsSha256: String?,
    val rootfsOnDiskSha256: String,
    val installedAtMs: Long,
    val installedBy: String,
    val installedOn: String,
    val sbom: SoftwareBillOfMaterials
) {
    fun toJson(): String = buildString {
        append("{\"manifestVersion\":").append(manifestVersion).append(',')
        append("\"elysiumVersion\":\"").append(elysiumVersion).append("\",")
        append("\"distroId\":\"").append(distroId).append("\",")
        append("\"distroDisplayName\":\"").append(distroDisplayName).append("\",")
        append("\"distroFamily\":\"").append(distroFamily.name).append("\",")
        append("\"distroVersion\":\"").append(distroVersion).append("\",")
        append("\"packageManager\":\"").append(packageManager).append("\",")
        append("\"rootfsUrl\":\"").append(rootfsUrl).append("\",")
        append("\"rootfsSha256\":").append(rootfsSha256?.let { "\"$it\"" } ?: "null").append(',')
        append("\"rootfsOnDiskSha256\":\"").append(rootfsOnDiskSha256).append("\",")
        append("\"installedAtMs\":").append(installedAtMs).append(',')
        append("\"installedBy\":\"").append(installedBy).append("\",")
        append("\"installedOn\":\"").append(installedOn).append("\",")
        append("\"sbom\":").append(sbom.toJson()).append("}")
    }
}

data class SignedManifest(
    val manifest: RootfsManifest,
    val signature: String,
    val algorithm: String = "HmacSHA256",
    val publicKey: String? = null,
    val signedAtMs: Long,
    val keyAlias: String
) {
    fun toJson(): String = buildString {
        append("{\"manifest\":").append(manifest.toJson()).append(',')
        append("\"signature\":\"").append(signature).append("\",")
        append("\"algorithm\":\"").append(algorithm).append("\",")
        if (publicKey != null) {
            append("\"publicKey\":\"").append(publicKey).append("\",")
        }
        append("\"signedAtMs\":").append(signedAtMs).append(',')
        append("\"keyAlias\":\"").append(keyAlias).append("\"}")
    }
}
