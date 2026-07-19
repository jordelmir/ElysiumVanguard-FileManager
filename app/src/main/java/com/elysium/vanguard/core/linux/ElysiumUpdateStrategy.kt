package com.elysium.vanguard.core.linux

/**
 * Phase 73 third half (I-73.3.4) — the **Elysium Update Strategy**.
 *
 * Per sección 11 of the user's Elysium Linux vision
 * doc: the distro supports two update strategies
 * for the rootfs:
 *
 *   1. **A/B updates** — two rootfs slots
 *      (`slot_a` + `slot_b`); an update writes the
 *      new rootfs to the inactive slot; the device
 *      reboots into the new slot; the old slot is
 *      preserved for rollback. A/B is **fast**
 *      (no full re-install — the inactive slot is
 *      already allocated) but requires **dual
 *      storage** (the device needs 2x the rootfs
 *      size).
 *
 *   2. **Versioned images** — every rootfs is a
 *      content-addressed image
 *      (`rootfs-v1.2.3.tar.zst`); the device holds
 *      the last N versions; a rollback is
 *      `pm rollback rootfs-v1.2.2`. Versioned
 *      images are **simpler** (no slot management)
 *      but **slower** (a rollback re-extracts the
 *      image from the cache).
 *
 * The strategy is a **user preference + a
 * device-class preference**:
 *   - A/B requires dual storage (the device must
 *     have 2x the rootfs size available).
 *   - Versioned images work on any storage.
 *
 * The strategy is a **sealed class** (two cases,
 * not a string id). The orchestrator + the update
 * manager use `when` on the strategy; adding a
 * third strategy is a compile error in every
 * consumer that hasn't been updated.
 */
sealed class ElysiumUpdateStrategy {

    /**
     * A/B updates with two rootfs slots. The
     * device has a `currentSlot` (the slot the
     * device booted from) + an `inactiveSlot`
     * (the slot the next update writes to).
     */
    data class ABUpdate(
        val currentSlot: ElysiumRootfsSlot,
        val inactiveSlot: ElysiumRootfsSlot,
        val rollbackOnFailure: Boolean = true,
    ) : ElysiumUpdateStrategy() {
        init {
            require(currentSlot != inactiveSlot) {
                "ABUpdate.currentSlot and inactiveSlot must be different, " +
                    "got both: $currentSlot"
            }
        }
    }

    /**
     * Versioned images. The device has a list of
     * available rootfs versions; the current
     * version is the active one; the next update
     * downloads a new version.
     */
    data class VersionedImage(
        val currentVersion: ElysiumRootfsVersion,
        val availableVersions: List<ElysiumRootfsVersion>,
        val maxRetained: Int = DEFAULT_MAX_RETAINED,
    ) : ElysiumUpdateStrategy() {
        init {
            require(availableVersions.isNotEmpty()) {
                "VersionedImage.availableVersions must not be empty"
            }
            require(currentVersion in availableVersions) {
                "VersionedImage.currentVersion $currentVersion must be in " +
                    "availableVersions, got: $availableVersions"
            }
            require(maxRetained >= 1) {
                "VersionedImage.maxRetained must be >= 1, got $maxRetained"
            }
        }

        companion object {
            /** The default number of rootfs versions
             *  retained on disk. A higher value
             *  costs more storage; a lower value
             *  reduces rollback options. */
            const val DEFAULT_MAX_RETAINED: Int = 3
        }
    }
}

/**
 * A typed rootfs slot. The slot is one of the
 * two physical partitions used in A/B updates
 * (`a` or `b`).
 *
 * The slot is **distinct from the version**: a
 * slot is a *physical location* (a partition);
 * a version is the *content* of the rootfs. A
 * single slot can hold any version.
 */
enum class ElysiumRootfsSlot(val symbol: String) {
    /** Slot A (the first physical partition). */
    A("a"),

    /** Slot B (the second physical partition). */
    B("b");

    /**
     * The other slot (the slot NOT this one).
     * Returns `A` for `B`, `B` for `A`.
     */
    val other: ElysiumRootfsSlot
        get() = when (this) {
            A -> B
            B -> A
        }

    /**
     * The string form. The string is the symbol.
     */
    override fun toString(): String = symbol
}

/**
 * A typed rootfs version. The version is the
 * content-addressed identifier of a rootfs
 * (a `rootfs-v1.2.3.tar.zst` file). The version
 * is **distinct from the package version** — a
 * rootfs is a *content bundle* (a tarball); a
 * package is a *software unit* (an .so, a
 * binary, a library). A rootfs contains many
 * packages.
 *
 * The version is `MAJOR.MINOR.PATCH` semver (per
 * the rootfs release cadence). A patch release
 * (1.2.3 → 1.2.4) is a security fix; a minor
 * release (1.2.3 → 1.3.0) is a feature; a major
 * release (1.2.3 → 2.0.0) is a breaking change
 * (e.g. a new FHS layout, a new package manager
 * version).
 */
data class ElysiumRootfsVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    init {
        require(major >= 0) {
            "ElysiumRootfsVersion.major must be >= 0, got $major"
        }
        require(minor >= 0) {
            "ElysiumRootfsVersion.minor must be >= 0, got $minor"
        }
        require(patch >= 0) {
            "ElysiumRootfsVersion.patch must be >= 0, got $patch"
        }
    }

    /** The canonical semver form. */
    val canonical: String = "$major.$minor.$patch"

    /**
     * The versioned image file name.
     * Format: `rootfs-v<MAJOR>.<MINOR>.<PATCH>.tar.zst`
     * (the `.tar.zst` extension is the Elysium
     * Linux canonical image format — a tarball
     * compressed with zstd).
     */
    val imageFileName: String = "rootfs-v$canonical.tar.zst"

    /**
     * Compare two versions per semver. The
     * comparison is the lexicographic order of
     * `(major, minor, patch)`.
     */
    operator fun compareTo(other: ElysiumRootfsVersion): Int {
        val majorCmp = major.compareTo(other.major)
        if (majorCmp != 0) return majorCmp
        val minorCmp = minor.compareTo(other.minor)
        if (minorCmp != 0) return minorCmp
        return patch.compareTo(other.patch)
    }

    /**
     * The string form. The string is the
     * canonical semver form.
     */
    override fun toString(): String = canonical
}

/**
 * The typed update plan. The plan is the
 * orchestrator's recipe for executing an update.
 * The plan is generated from the current
 * strategy + the target version.
 */
data class ElysiumUpdatePlan(
    val strategy: ElysiumUpdateStrategy,
    val targetVersion: ElysiumRootfsVersion,
    val estimatedBytes: Long,
    val requiresReboot: Boolean,
    val rollbackPlan: ElysiumRollbackPlan,
) {
    init {
        require(targetVersion.canonical.isNotBlank()) {
            "ElysiumUpdatePlan.targetVersion.canonical must not be blank"
        }
        require(estimatedBytes >= 0) {
            "ElysiumUpdatePlan.estimatedBytes must be >= 0, got $estimatedBytes"
        }
    }
}

/**
 * The typed rollback plan. The rollback plan is
 * the orchestrator's recipe for undoing an update.
 *
 * The rollback plan is **always present** (every
 * update must have a rollback path). The plan's
 * `canRollback` flag is `false` only in the
 * "no rollback possible" edge case (e.g. the user
 * explicitly opted out of rollback).
 */
data class ElysiumRollbackPlan(
    val canRollback: Boolean,
    val targetVersion: ElysiumRootfsVersion?,
    val estimatedBytes: Long,
    val estimatedDurationMs: Long,
) {
    init {
        require(estimatedBytes >= 0) {
            "ElysiumRollbackPlan.estimatedBytes must be >= 0, got $estimatedBytes"
        }
        require(estimatedDurationMs >= 0) {
            "ElysiumRollbackPlan.estimatedDurationMs must be >= 0, " +
                "got $estimatedDurationMs"
        }
        if (canRollback) {
            require(targetVersion != null) {
                "ElysiumRollbackPlan.targetVersion must not be null " +
                    "when canRollback is true"
            }
        }
    }

    companion object {
        /** A rollback plan that cannot be executed
         *  (e.g. the user opted out of rollback,
         *  or the strategy does not support
         *  rollback). */
        val NO_ROLLBACK: ElysiumRollbackPlan = ElysiumRollbackPlan(
            canRollback = false,
            targetVersion = null,
            estimatedBytes = 0L,
            estimatedDurationMs = 0L,
        )
    }
}
