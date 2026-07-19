package com.elysium.vanguard.core.linux

/**
 * Phase 73 first half — the **Elysium Package Dependency**.
 *
 * The dependency declares a relationship
 * between the current package and another
 * package. Per the user's vision: the package
 * manager is the runtime that resolves
 * dependencies.
 *
 * The dependency is **typed**: the constraint
 * is one of:
 *   - [ConstraintKind.EXACT] — `=` `1.2.3` (the
 *     installed version MUST be exactly `1.2.3`).
 *   - [ConstraintKind.GTE] — `>=` `1.2.3` (the
 *     installed version MUST be at least `1.2.3`).
 *   - [ConstraintKind.LTE] — `<=` `1.2.3` (the
 *     installed version MUST be at most `1.2.3`).
 *   - [ConstraintKind.GT] — `>` `1.2.3`.
 *   - [ConstraintKind.LT] — `<` `1.2.3`.
 *   - [ConstraintKind.CARET] — `^` `1.2.3` (the
 *     installed version MUST be `>= 1.2.3` AND
 *     `< 2.0.0`; the standard semver "compatible
 *     with" range).
 *   - [ConstraintKind.TILDE] — `~` `1.2.3` (the
 *     installed version MUST be `>= 1.2.3` AND
 *     `< 1.3.0`; the standard semver "patch
 *     range").
 *   - [ConstraintKind.ANY] — `*` (any version is
 *     acceptable; the package manager picks the
 *     latest compatible version).
 *
 * The dependency is **per-package + per-abi**: a
 * package MAY declare different dependencies
 * for different ABIs (a native package depends
 * on a native runtime; a script package depends
 * on a scripting runtime).
 */
data class ElysiumPackageDependency(
    /**
     * The name of the dependency package. The
     * name is a reverse-DNS identifier (e.g.
     * `"com.elysium.runtime.python"`).
     */
    val packageName: String,
    /**
     * The version constraint. The constraint is
     * the `kind` + the `version` (the version is
     * the operand of the constraint).
     */
    val constraint: VersionConstraint,
    /**
     * The ABI the dependency is for. The default
     * is `ANY` (the dependency is the same for
     * every ABI). A native package MAY declare
     * per-ABI dependencies (the constraint is
     * different for ARM64 vs X86_64).
     */
    val abi: ElysiumAbi = ElysiumAbi.ANY,
    /**
     * The dependency is `optional: true` when the
     * package is recommended but not required.
     * The package manager installs the optional
     * dependency when the user requests the
     * recommended packages; otherwise the
     * optional dependency is skipped.
     */
    val optional: Boolean = false,
) {
    init {
        require(packageName.isNotBlank()) {
            "ElysiumPackageDependency.packageName must not be blank"
        }
        require(packageName.matches(Regex(PACKAGE_NAME_PATTERN))) {
            "ElysiumPackageDependency.packageName must match " +
                "$PACKAGE_NAME_PATTERN, got: $packageName"
        }
    }

    /**
     * The canonical form of the dependency. The
     * form is the package name + the constraint
     * operator + the version, e.g.
     * `"com.elysium.runtime.python >= 3.11.0"`.
     *
     * The canonical form is the form the
     * package manager stores + the form the
     * dependency resolution compares.
     */
    val canonical: String = buildString {
        append(packageName)
        append(" ")
        append(constraint.canonical)
        if (abi != ElysiumAbi.ANY) {
            append(" (")
            append(ElysiumAbi.canonicalName(abi))
            append(")")
        }
        if (optional) append(" (optional)")
    }

    /**
     * The string form. The string is the
     * canonical form.
     */
    override fun toString(): String = canonical

    companion object {
        /**
         * The package name pattern. The pattern is
         * reverse-DNS: lower-case alphanumeric +
         * dots + hyphens + underscores. The first
         * segment is a domain (e.g. `com.elysium`).
         */
        const val PACKAGE_NAME_PATTERN: String =
            "^[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+$"
    }
}

/**
 * The version constraint — the kind + the
 * version operand of the constraint.
 */
data class VersionConstraint(
    val kind: ConstraintKind,
    val version: ElysiumPackageVersion,
) {
    /**
     * The canonical form. The form is the
     * constraint operator + the version
     * canonical form, e.g. `">= 1.2.3"`.
     */
    val canonical: String = "${kind.symbol} ${version.canonical}"

    /**
     * Test whether the constraint is satisfied
     * by a candidate version. The function is
     * **total** (every candidate is checked; a
     * candidate is either satisfied or not).
     */
    fun satisfiedBy(candidate: ElysiumPackageVersion): Boolean = when (kind) {
        ConstraintKind.EXACT -> candidate == version
        ConstraintKind.GTE -> candidate >= version
        ConstraintKind.LTE -> candidate <= version
        ConstraintKind.GT -> candidate > version
        ConstraintKind.LT -> candidate < version
        ConstraintKind.CARET -> candidate >= version &&
            candidate.major == version.major
        ConstraintKind.TILDE -> candidate >= version &&
            candidate.major == version.major &&
            candidate.minor == version.minor
        ConstraintKind.ANY -> true
    }

    /**
     * The string form. The string is the
     * canonical form.
     */
    override fun toString(): String = canonical
}

/**
 * The kind of version constraint. The kind
 * determines the operator + the resolution
 * rule.
 */
enum class ConstraintKind(val symbol: String) {
    /** `=` — exact version match. */
    EXACT("="),

    /** `>=` — greater than or equal. */
    GTE(">="),

    /** `<=` — less than or equal. */
    LTE("<="),

    /** `>` — strictly greater than. */
    GT(">"),

    /** `<` — strictly less than. */
    LT("<"),

    /** `^` — semver "compatible with" range. */
    CARET("^"),

    /** `~` — semver "patch range". */
    TILDE("~"),

    /** `*` — any version. */
    ANY("*"),
}
