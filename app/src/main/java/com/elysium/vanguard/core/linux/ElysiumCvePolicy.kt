package com.elysium.vanguard.core.linux

/**
 * Phase 73 third half (I-73.3.5) — the **Elysium CVE Policy**.
 *
 * Per sección 17 of the user's Elysium Linux vision
 * doc: the distro has a typed **vulnerability
 * policy** with:
 *   - **Severity levels** (CRITICAL / HIGH /
 *     MEDIUM / LOW) with CVSS score ranges.
 *   - **Response SLA** (the maximum time from a
 *     CVE disclosure to a patched Elysium Linux
 *     release, per severity).
 *   - **Disclosure timeline** (the time from a
 *     CVE patch to a public CVE record, per
 *     severity).
 *   - **Affected package tracking** (the typed
 *     list of packages affected by a CVE; a CVE
 *     is associated with one or more packages +
 *     a fixed-in version).
 *
 * The policy is the **distro's commitment to
 * security**. A user who installs Elysium Linux
 * has a typed answer to "when will my device be
 * patched?" — the answer is the policy's
 * `severityResponseHours`.
 */
data class ElysiumCvePolicy(
    /**
     * The response SLA per severity. The map
     * is keyed by severity; the value is the
     * maximum number of hours between a CVE
     * disclosure and a patched Elysium Linux
     * release.
     *
     * Example: `CRITICAL → 24` means a CRITICAL
     * CVE is patched within 24 hours of disclosure.
     */
    val severityResponseHours: Map<ElysiumCveSeverity, Int>,

    /**
     * The disclosure timeline per severity.
     * The map is keyed by severity; the value
     * is the minimum number of hours between a
     * CVE patch release and the public CVE
     * record.
     *
     * Example: `CRITICAL → 0` means a CRITICAL
     * CVE is disclosed immediately (no
     * embargo).
     */
    val severityDisclosureDelayHours: Map<ElysiumCveSeverity, Int>,
) {
    init {
        require(severityResponseHours.isNotEmpty()) {
            "ElysiumCvePolicy.severityResponseHours must not be empty"
        }
        require(severityDisclosureDelayHours.isNotEmpty()) {
            "ElysiumCvePolicy.severityDisclosureDelayHours must not be empty"
        }
        // Every severity has both a response SLA
        // and a disclosure delay (a missing key
        // is a misconfiguration).
        for (severity in ElysiumCveSeverity.values()) {
            require(severity in severityResponseHours) {
                "ElysiumCvePolicy.severityResponseHours missing " +
                    "severity: $severity"
            }
            require(severity in severityDisclosureDelayHours) {
                "ElysiumCvePolicy.severityDisclosureDelayHours missing " +
                    "severity: $severity"
            }
        }
        // Every SLA value is positive (a
        // negative SLA is meaningless).
        for ((severity, hours) in severityResponseHours) {
            require(hours > 0) {
                "ElysiumCvePolicy.severityResponseHours[$severity] " +
                    "must be > 0, got $hours"
            }
        }
        for ((severity, hours) in severityDisclosureDelayHours) {
            require(hours >= 0) {
                "ElysiumCvePolicy.severityDisclosureDelayHours[$severity] " +
                    "must be >= 0, got $hours"
            }
        }
    }

    /**
     * The response SLA for a specific severity.
     * The function is **total**: every severity
     * is in the policy's `severityResponseHours`
     * (the init block enforces this).
     */
    fun responseSlaFor(severity: ElysiumCveSeverity): Int =
        severityResponseHours.getValue(severity)

    /**
     * The disclosure delay for a specific
     * severity. The function is **total**: every
     * severity is in the policy's
     * `severityDisclosureDelayHours` (the init
     * block enforces this).
     */
    fun disclosureDelayFor(severity: ElysiumCveSeverity): Int =
        severityDisclosureDelayHours.getValue(severity)

    /**
     * Check whether a CVE was responded to within
     * the SLA. The function compares the
     * `disclosedAtMs` (the CVE's public
     * disclosure time) + the `patchedAtMs` (the
     * Elysium Linux release that fixed the CVE)
     * + returns `true` if the patch was released
     * within the SLA hours.
     */
    fun meetsResponseSla(
        severity: ElysiumCveSeverity,
        disclosedAtMs: Long,
        patchedAtMs: Long,
    ): Boolean {
        val slaMs = responseSlaFor(severity).toLong() * 60L * 60L * 1000L
        return patchedAtMs - disclosedAtMs <= slaMs
    }

    companion object {
        /**
         * The default Elysium Linux CVE policy.
         * The defaults are the distro's standard
         * commitment:
         *   - CRITICAL: 24h response, 0h disclosure delay.
         *   - HIGH: 7d response, 24h disclosure delay.
         *   - MEDIUM: 30d response, 7d disclosure delay.
         *   - LOW: 90d response, 30d disclosure delay.
         *
         * A future Phase 73 increment may add
         * per-domain policies (e.g. a tighter
         * policy for security-critical packages
         * like OpenSSL or sudo).
         */
        val DEFAULT: ElysiumCvePolicy = ElysiumCvePolicy(
            severityResponseHours = mapOf(
                ElysiumCveSeverity.CRITICAL to 24,
                ElysiumCveSeverity.HIGH to 24 * 7,
                ElysiumCveSeverity.MEDIUM to 24 * 30,
                ElysiumCveSeverity.LOW to 24 * 90,
                // NONE is informational; no response
                // SLA is needed (the distro doesn't
                // act on NONE-severity CVEs).
                ElysiumCveSeverity.NONE to 24 * 365,
            ),
            severityDisclosureDelayHours = mapOf(
                ElysiumCveSeverity.CRITICAL to 0,
                ElysiumCveSeverity.HIGH to 24,
                ElysiumCveSeverity.MEDIUM to 24 * 7,
                ElysiumCveSeverity.LOW to 24 * 30,
                ElysiumCveSeverity.NONE to 24 * 365,
            ),
        )
    }
}

/**
 * The CVE severity. The severity is the typed
 * classification of a CVE's impact, mapped from
 * the CVSS v3.1 base score.
 *
 * The CVSS ranges follow the official NVD
 * classification:
 *   - CRITICAL: 9.0-10.0
 *   - HIGH: 7.0-8.9
 *   - MEDIUM: 4.0-6.9
 *   - LOW: 0.1-3.9
 *   - NONE: 0.0 (informational; no real impact)
 */
enum class ElysiumCveSeverity(
    val displayLabel: String,
    val cvssRange: ClosedFloatingPointRange<Double>,
) {
    CRITICAL(
        displayLabel = "Critical",
        cvssRange = 9.0..10.0,
    ),
    HIGH(
        displayLabel = "High",
        cvssRange = 7.0..8.99,
    ),
    MEDIUM(
        displayLabel = "Medium",
        cvssRange = 4.0..6.99,
    ),
    LOW(
        displayLabel = "Low",
        cvssRange = 0.1..3.99,
    ),
    NONE(
        displayLabel = "None",
        cvssRange = 0.0..0.0,
    );

    /**
     * Map a CVSS score to a severity. The
     * function is **total**: every CVSS score
     * in [0.0, 10.0] maps to exactly one
     * severity.
     */
    companion object {
        fun fromCvss(score: Double): ElysiumCveSeverity = when {
            score < 0.0 -> throw IllegalArgumentException(
                "CVSS score must be >= 0, got $score"
            )
            score > 10.0 -> throw IllegalArgumentException(
                "CVSS score must be <= 10, got $score"
            )
            score >= 9.0 -> CRITICAL
            score >= 7.0 -> HIGH
            score >= 4.0 -> MEDIUM
            score >= 0.1 -> LOW
            else -> NONE
        }
    }
}

/**
 * A typed CVE id. The id follows the official
 * CVE Numbering Authority (CNA) format
 * (`CVE-YYYY-NNNN`); a future Elysium Linux
 * may receive its own CNA designation and
 * allocate ids from its own block.
 */
data class ElysiumCveId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "ElysiumCveId.value must not be blank"
        }
        require(value.matches(Regex(PATTERN))) {
            "ElysiumCveId.value must match $PATTERN, got: $value"
        }
    }

    /**
     * The string form. The string is the id.
     */
    override fun toString(): String = value

    companion object {
        /** The CVE id pattern (CNA format). */
        const val PATTERN: String = "^CVE-\\d{4}-\\d{4,}$"
    }
}

/**
 * The status of a CVE in the Elysium Linux
 * distro. The status is the **lifecycle state**
 * of the CVE — a CVE starts as UNDISCLOSED (a
 * patch is in development) + ends as
 * DISCLOSED_PATCHED (the patch is released +
 * the CVE is public).
 */
enum class ElysiumCveStatus {
    /**
     * The patch is in development; the CVE is
     * not yet public. A coordinated disclosure
     * is in progress.
     */
    UNDISCLOSED,

    /**
     * The patch is released; the CVE is public.
     * The normal end state.
     */
    DISCLOSED_PATCHED,

    /**
     * The CVE is public but no patch exists
     * yet (the distro is still investigating
     * the root cause).
     */
    DISCLOSED_UNPATCHED,

    /**
     * The distro declined to patch the CVE
     * (e.g. the affected package is end-of-life
     * + not shipped in the current rootfs).
     */
    WONT_FIX,
}

/**
 * The typed record of a CVE in the Elysium Linux
 * distro. The record has:
 *   - `cveId: ElysiumCveId` — the CVE's official id.
 *   - `severity: ElysiumCveSeverity` — the typed
 *     severity.
 *   - `cvssScore: Double` — the official CVSS
 *     v3.1 base score (0.0-10.0).
 *   - `description: String` — the user-facing
 *     description.
 *   - `affectedPackages: List<ElysiumCveAffectedPackage>`
 *     — the packages affected by the CVE.
 *   - `status: ElysiumCveStatus` — the lifecycle
 *     state.
 *   - `disclosedAtMs: Long?` — the public
 *     disclosure time (millis since epoch;
 *     `null` when the CVE is UNDISCLOSED).
 *   - `patchedAtMs: Long?` — the Elysium Linux
 *     release time that fixed the CVE (millis
 *     since epoch; `null` when no patch exists
 *     yet).
 */
data class ElysiumCveRecord(
    val cveId: ElysiumCveId,
    val severity: ElysiumCveSeverity,
    val cvssScore: Double,
    val description: String,
    val affectedPackages: List<ElysiumCveAffectedPackage>,
    val status: ElysiumCveStatus,
    val disclosedAtMs: Long?,
    val patchedAtMs: Long?,
) {
    init {
        require(cvssScore in 0.0..10.0) {
            "ElysiumCveRecord.cvssScore must be in 0.0..10.0, got $cvssScore"
        }
        require(description.isNotBlank()) {
            "ElysiumCveRecord.description must not be blank"
        }
        require(affectedPackages.isNotEmpty()) {
            "ElysiumCveRecord.affectedPackages must not be empty"
        }
        // Status invariants.
        if (status == ElysiumCveStatus.UNDISCLOSED) {
            require(disclosedAtMs == null) {
                "ElysiumCveRecord.disclosedAtMs must be null " +
                    "when status is UNDISCLOSED, got: $disclosedAtMs"
            }
        }
        if (status == ElysiumCveStatus.DISCLOSED_PATCHED) {
            require(disclosedAtMs != null) {
                "ElysiumCveRecord.disclosedAtMs must not be null " +
                    "when status is DISCLOSED_PATCHED"
            }
            require(patchedAtMs != null) {
                "ElysiumCveRecord.patchedAtMs must not be null " +
                    "when status is DISCLOSED_PATCHED"
            }
        }
        if (status == ElysiumCveStatus.WONT_FIX) {
            require(patchedAtMs == null) {
                "ElysiumCveRecord.patchedAtMs must be null " +
                    "when status is WONT_FIX, got: $patchedAtMs"
            }
        }
    }
}

/**
 * The typed record of a single package affected
 * by a CVE. The record has:
 *   - `packageName: String` — the package's
 *     reverse-DNS name.
 *   - `fixedInVersion: ElysiumPackageVersion` —
 *     the version that contains the fix.
 *   - `affectedVersions: VersionConstraint` —
 *     the version range that is vulnerable
 *     (e.g. `>= 1.0.0 < 1.2.4`).
 */
data class ElysiumCveAffectedPackage(
    val packageName: String,
    val fixedInVersion: ElysiumPackageVersion,
    val affectedVersions: VersionConstraint,
) {
    init {
        require(packageName.isNotBlank()) {
            "ElysiumCveAffectedPackage.packageName must not be blank"
        }
    }
}
