package com.elysium.vanguard.core.linux

/**
 * Phase 73 first half — the **Elysium Package Version**.
 *
 * The version is the semver (semantic version)
 * of an Elysium package. Per semver.org:
 *
 *   - `MAJOR` — incompatible API changes.
 *   - `MINOR` — backward-compatible functionality.
 *   - `PATCH` — backward-compatible bug fixes.
 *   - `preRelease` — optional pre-release identifier
 *     (e.g. `"alpha.1"`, `"beta.2"`).
 *   - `build` — optional build metadata (e.g.
 *     `"build.42"`).
 *
 * The version is **explicit** (every package
 * declares its version; the package manager
 * refuses to install a package with an
 * incompatible version constraint). The version
 * is **ordered** (the comparison operator
 * implements the semver ordering; pre-release
 * versions are ordered before the corresponding
 * release version).
 *
 * The version is **immutable** (a data class; no
 * setters). A new version is a new value. The
 * package's lifecycle (a new release) is a new
 * `ElysiumPackageVersion` value.
 */
data class ElysiumPackageVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
    val build: String? = null,
) : Comparable<ElysiumPackageVersion> {
    init {
        require(major >= 0) {
            "ElysiumPackageVersion.major must be >= 0, got $major"
        }
        require(minor >= 0) {
            "ElysiumPackageVersion.minor must be >= 0, got $minor"
        }
        require(patch >= 0) {
            "ElysiumPackageVersion.patch must be >= 0, got $patch"
        }
        if (preRelease != null) {
            require(preRelease.isNotBlank()) {
                "ElysiumPackageVersion.preRelease must not be blank when set"
            }
            require(preRelease.matches(Regex(PRE_RELEASE_PATTERN))) {
                "ElysiumPackageVersion.preRelease must match $PRE_RELEASE_PATTERN, " +
                    "got: $preRelease"
            }
        }
        if (build != null) {
            require(build.isNotBlank()) {
                "ElysiumPackageVersion.build must not be blank when set"
            }
            require(build.matches(Regex(BUILD_PATTERN))) {
                "ElysiumPackageVersion.build must match $BUILD_PATTERN, " +
                    "got: $build"
            }
        }
    }

    /**
     * The canonical string form (per semver.org).
     * The form is `"MAJOR.MINOR.PATCH"` + optional
     * `"-preRelease"` + optional `"+build"`.
     *
     * The canonical form is the form the package
     * manager stores + the form the version
     * constraint compares.
     */
    val canonical: String = buildString {
        append("$major.$minor.$patch")
        if (preRelease != null) append("-").append(preRelease)
        if (build != null) append("+").append(build)
    }

    /**
     * The semver comparison. The order is:
     *   - Compare MAJOR, MINOR, PATCH numerically.
     *   - If all equal, a version with a preRelease
     *     is LESS than the same version without.
     *   - Pre-release identifiers are compared
     *     dot-separated; numeric identifiers are
     *     compared numerically; alphanumeric
     *     identifiers are compared lexically.
     *   - Build metadata is IGNORED in the
     *     comparison (per semver).
     */
    override fun compareTo(other: ElysiumPackageVersion): Int {
        val majorCmp = major.compareTo(other.major)
        if (majorCmp != 0) return majorCmp
        val minorCmp = minor.compareTo(other.minor)
        if (minorCmp != 0) return minorCmp
        val patchCmp = patch.compareTo(other.patch)
        if (patchCmp != 0) return patchCmp
        // All numeric parts equal; preRelease
        // decides. A version with preRelease is
        // LESS than the same version without.
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null && other.preRelease != null -> 1
            preRelease != null && other.preRelease == null -> -1
            else -> comparePreRelease(preRelease!!, other.preRelease!!)
        }
    }

    /**
     * The string form. The string is the
     * canonical form (e.g. `"1.2.3"`,
     * `"1.2.3-alpha.1"`, `"1.2.3+build.42"`).
     */
    override fun toString(): String = canonical

    companion object {
        /**
         * The pre-release pattern. The pattern
         * permits dot-separated identifiers; each
         * identifier is either numeric or
         * alphanumeric.
         */
        const val PRE_RELEASE_PATTERN: String = "^[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*$"

        /**
         * The build metadata pattern. The pattern
         * permits dot-separated identifiers; each
         * identifier is alphanumeric + hyphen.
         */
        const val BUILD_PATTERN: String = "^[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*$"

        /**
         * Parse a canonical version string. The
         * function is **total**: every valid
         * canonical form is parsed; an invalid
         * form returns a `Result.failure` with a
         * typed error.
         */
        fun parse(canonical: String): Result<ElysiumPackageVersion> {
            // Strip the build metadata.
            val mainPart = canonical.substringBefore("+")
            val buildPart = canonical.substringAfter("+", missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() }

            // Split MAJOR.MINOR.PATCH from the pre-release.
            val coreAndPreRelease = mainPart.split("-", limit = 2)
            val corePart = coreAndPreRelease[0]
            val preReleasePart = coreAndPreRelease.getOrNull(1)

            val coreTokens = corePart.split(".")
            if (coreTokens.size != 3) {
                return Result.failure(
                    IllegalArgumentException(
                        "expected MAJOR.MINOR.PATCH, got: $canonical"
                    ),
                )
            }
            val major = coreTokens[0].toIntOrNull()
                ?: return Result.failure(
                    IllegalArgumentException("major is not an integer: ${coreTokens[0]}"),
                )
            val minor = coreTokens[1].toIntOrNull()
                ?: return Result.failure(
                    IllegalArgumentException("minor is not an integer: ${coreTokens[1]}"),
                )
            val patch = coreTokens[2].toIntOrNull()
                ?: return Result.failure(
                    IllegalArgumentException("patch is not an integer: ${coreTokens[2]}"),
                )
            return try {
                Result.success(
                    ElysiumPackageVersion(
                        major = major,
                        minor = minor,
                        patch = patch,
                        preRelease = preReleasePart,
                        build = buildPart,
                    ),
                )
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            }
        }

        private fun comparePreRelease(a: String, b: String): Int {
            val aTokens = a.split(".")
            val bTokens = b.split(".")
            for (i in 0 until maxOf(aTokens.size, bTokens.size)) {
                if (i >= aTokens.size) return -1  // a is shorter → a < b
                if (i >= bTokens.size) return 1   // b is shorter → a > b
                val aTok = aTokens[i]
                val bTok = bTokens[i]
                val aIsNum = aTok.all { it.isDigit() }
                val bIsNum = bTok.all { it.isDigit() }
                val cmp = when {
                    aIsNum && bIsNum -> aTok.toInt().compareTo(bTok.toInt())
                    aIsNum && !bIsNum -> -1  // numeric < alphanumeric
                    !aIsNum && bIsNum -> 1
                    else -> aTok.compareTo(bTok)
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}
