package com.elysium.vanguard.core.linux

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature

/**
 * Phase 73 first half — the **Elysium Package Manifest**.
 *
 * The manifest is the typed signed metadata of an
 * Elysium package. The manifest is the
 * **canonical contract** between the package
 * publisher + the package consumer (the user +
 * the package manager).
 *
 * The manifest has:
 *   - `name: String` — the package's reverse-DNS
 *     identifier.
 *   - `version: ElysiumPackageVersion` — the
 *     semver.
 *   - `abi: ElysiumAbi` — the target ABI.
 *   - `description: String` — the user-facing
 *     description.
 *   - `dependencies: List<ElysiumPackageDependency>`
 *     — the runtime dependencies.
 *   - `provides: List<String>` — the capabilities
 *     the package provides (e.g. a Python package
 *     provides `"python-3.11"` capability; a
 *     consumer can depend on the capability
 *     instead of the specific package).
 *   - `files: List<ElysiumPackageFile>` — the
 *     file list with per-file content hash.
 *   - `scripts: ElysiumPackageScripts` — the
 *     install / remove scripts.
 *   - `contentHash: ContentHash` — the SHA-256 of
 *     the package tarball (the bytes the user
 *     downloads; NOT part of the canonical form
 *     since the canonical form IS the manifest,
 *     not the tarball).
 *   - `signature: Signature` — the signature on
 *     the canonical form of the manifest. The
 *     signature binds the manifest to the
 *     publisher.
 *
 * The manifest is **append-only + signed**: a
 * new release of the same package is a new
 * `ElysiumPackageManifest` with a new version
 * + a new content hash + a new signature. A
 * silent downgrade is a `R-DI-6` typed error.
 *
 * The manifest is **content-addressed by
 * composition**: the same `name` + `version` +
 * `abi` + `description` + `dependencies` +
 * `provides` + `files` + `scripts` produces the
 * same canonical form. The signature binds
 * the canonical form to the publisher.
 */
data class ElysiumPackageManifest(
    val name: String,
    val version: ElysiumPackageVersion,
    val abi: ElysiumAbi,
    val description: String,
    val dependencies: List<ElysiumPackageDependency> = emptyList(),
    val provides: List<String> = emptyList(),
    val files: List<ElysiumPackageFile> = emptyList(),
    val scripts: ElysiumPackageScripts = ElysiumPackageScripts.NONE,
    val contentHash: ContentHash,
    val signature: Signature,
) {
    init {
        require(name.isNotBlank()) {
            "ElysiumPackageManifest.name must not be blank"
        }
        require(name.matches(Regex(PACKAGE_NAME_PATTERN))) {
            "ElysiumPackageManifest.name must match " +
                "$PACKAGE_NAME_PATTERN, got: $name"
        }
        require(description.isNotBlank()) {
            "ElysiumPackageManifest.description must not be blank"
        }
        // Every `provides` capability is non-blank.
        require(provides.all { it.isNotBlank() }) {
            "ElysiumPackageManifest.provides must not contain blank entries"
        }
        // A package must declare at least one file
        // (an empty file list is a smell — the
        // package is empty).
        require(files.isNotEmpty()) {
            "ElysiumPackageManifest.files must not be empty; " +
                "an empty package is a deployment error"
        }
    }

    /**
     * The canonical form of the manifest. The
     * form is the deterministic UTF-8 byte
     * sequence used to compute the manifest's
     * signature + to verify the signature at
     * install time.
     *
     * The form EXCLUDES the `signature` (the
     * signature is computed over the form; the
     * form is the input, not the output, of the
     * signature).
     */
    val canonicalForm: String
        get() = buildString {
            append("elysium-pkg:v1")
            append("|name=").append(name)
            append("|version=").append(version.canonical)
            append("|abi=").append(ElysiumAbi.canonicalName(abi))
            append("|description=").append(description)
            append("|deps=")
            append(
                dependencies.sortedBy { it.packageName }
                    .joinToString(";") { dep -> dep.canonical },
            )
            append("|provides=").append(provides.sorted().joinToString(","))
            append("|files=")
            append(
                files.sortedBy { it.installPath }
                    .joinToString(";") { file -> file.canonical },
            )
            append("|scripts=").append(scripts.canonical)
            append("|contentHash=").append(contentHash.value)
        }

    /**
     * Verify the manifest's signature. The
     * function builds the canonical form +
     * compares the manifest's signature to the
     * expected signature. A failed verification
     * is a hard rejection (a tampered manifest is
     * never installed).
     */
    fun verifySignature(expectedSignature: Signature): Result<Unit> {
        val canonical = canonicalForm
        val recomputed = Signature.sign(
            payload = canonical.toByteArray(Charsets.UTF_8),
            key = expectedSignature.value.toByteArray(),
        )
        return if (recomputed.value == signature.value) {
            Result.success(Unit)
        } else {
            Result.failure(
                ElysiumPackageVerificationError.SignatureMismatch(
                    name = name,
                    version = version,
                    expected = expectedSignature.value,
                    actual = signature.value,
                ),
            )
        }
    }

    /**
     * The string form. The string is the
     * canonical form.
     */
    override fun toString(): String = canonicalForm

    companion object {
        /**
         * The package name pattern (reverse-DNS,
         * the same as
         * [ElysiumPackageDependency.PACKAGE_NAME_PATTERN]).
         */
        const val PACKAGE_NAME_PATTERN: String = ElysiumPackageDependency.PACKAGE_NAME_PATTERN
    }
}

/**
 * The package's file list entry. The entry
 * declares:
 *   - `installPath: String` — the absolute path
 *     where the file is installed in the
 *     rootfs (e.g. `"/usr/bin/python3"`).
 *   - `contentHash: ContentHash` — the SHA-256
 *     of the file's bytes. The package manager
 *     verifies the hash at install time + on
 *     every load.
 *   - `permissions: FilePermissions` — the
 *     file's mode + uid + gid.
 */
data class ElysiumPackageFile(
    val installPath: String,
    val contentHash: ContentHash,
    val permissions: FilePermissions = FilePermissions.DEFAULT,
) {
    init {
        require(installPath.isNotBlank()) {
            "ElysiumPackageFile.installPath must not be blank"
        }
        require(installPath.startsWith("/")) {
            "ElysiumPackageFile.installPath must be absolute, got: $installPath"
        }
    }

    /**
     * The canonical form. The form is the
     * install path + the content hash + the
     * permissions canonical form.
     */
    val canonical: String
        get() = "$installPath:" +
            "${contentHash.value}:" +
            permissions.canonical

    /**
     * The string form. The string is the
     * canonical form.
     */
    override fun toString(): String = canonical
}

/**
 * The file permissions. The permissions are
 * the file's mode + uid + gid.
 *
 * The permissions are a value object (immutable).
 * The default permissions are `0644` (rw-r--r--)
 * + uid 0 + gid 0 (root).
 */
data class FilePermissions(
    val mode: Int = 0x1A4,
    val uid: Int = 0,
    val gid: Int = 0,
) {
    init {
        require(mode in 0..0x1FF) {
            "FilePermissions.mode must be in 0..0x1FF, got " +
                "0${mode.toString(8)}"
        }
        require(uid >= 0) {
            "FilePermissions.uid must be >= 0, got $uid"
        }
        require(gid >= 0) {
            "FilePermissions.gid must be >= 0, got $gid"
        }
    }

    /**
     * The canonical form. The form is the mode
     * (octal) + the uid + the gid.
     */
    val canonical: String
        get() = "0${mode.toString(8)}:${uid}:${gid}"

    /**
     * The string form. The string is the
     * canonical form.
     */
    override fun toString(): String = canonical

    companion object {
        /** The default permissions (`0644` + root:root). */
        val DEFAULT: FilePermissions = FilePermissions()
    }
}

/**
 * The package's install / remove scripts.
 * The scripts are shell commands the package
 * manager runs at install / remove time.
 *
 * The scripts are optional. The default is
 * [ElysiumPackageScripts.NONE] (no scripts).
 *
 * The scripts are pure string values; the
 * package manager runs them with `/bin/sh`.
 */
data class ElysiumPackageScripts(
    val preInstall: String? = null,
    val postInstall: String? = null,
    val preRemove: String? = null,
    val postRemove: String? = null,
) {
    init {
        // Every script that is set is non-blank.
        if (preInstall != null) require(preInstall.isNotBlank()) {
            "ElysiumPackageScripts.preInstall must not be blank when set"
        }
        if (postInstall != null) require(postInstall.isNotBlank()) {
            "ElysiumPackageScripts.postInstall must not be blank when set"
        }
        if (preRemove != null) require(preRemove.isNotBlank()) {
            "ElysiumPackageScripts.preRemove must not be blank when set"
        }
        if (postRemove != null) require(postRemove.isNotBlank()) {
            "ElysiumPackageScripts.postRemove must not be blank when set"
        }
    }

    /**
     * The canonical form. The form is the
     * concatenation of the four scripts (each
     * with its name as a key).
     */
    val canonical: String
        get() = buildString {
            append("preInstall=").append(preInstall ?: "")
            append("|postInstall=").append(postInstall ?: "")
            append("|preRemove=").append(preRemove ?: "")
            append("|postRemove=").append(postRemove ?: "")
        }

    /**
     * The string form. The string is the
     * canonical form.
     */
    override fun toString(): String = canonical

    companion object {
        /** No scripts (the default). */
        val NONE: ElysiumPackageScripts = ElysiumPackageScripts()
    }
}

/**
 * The package verification error envelope.
 * The errors are the typed outcomes the
 * package manager returns on a failed
 * verification.
 */
sealed class ElysiumPackageVerificationError(
    message: String,
) : RuntimeException(message) {

    /**
     * The manifest's signature does not match
     * the expected signature. The manifest is
     * tampered (or signed by a different
     * publisher).
     */
    data class SignatureMismatch(
        val name: String,
        val version: ElysiumPackageVersion,
        val expected: String,
        val actual: String,
    ) : ElysiumPackageVerificationError(
        message = "Signature mismatch for $name@${version.canonical}: " +
            "expected $expected, got $actual",
    )

    /**
     * The manifest's content hash does not match
     * the tarball's actual content hash. The
     * package is corrupted.
     */
    data class ContentHashMismatch(
        val name: String,
        val version: ElysiumPackageVersion,
        val expected: String,
        val actual: String,
    ) : ElysiumPackageVerificationError(
        message = "Content hash mismatch for $name@${version.canonical}: " +
            "expected $expected, got $actual",
    )
}
