package com.elysium.vanguard.core.runtime.capsule.catalog

import com.elysium.vanguard.core.runtime.capsule.Architecture
import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.capsule.Distribution
import com.elysium.vanguard.core.runtime.capsule.Runtime

/**
 * Phase 69 — the search / filter helper for the
 * [CapsuleCatalog].
 *
 * The search is a **pure function** (no I/O, no state).
 * The caller hands the search a list of capsules +
 * a query; the search returns the filtered list. The
 * search is JVM-testable end-to-end with a hand-rolled
 * fixture.
 *
 * The search supports the queries the user actually
 * asks (per the master vision's "Marketplace universal"
 * section):
 *   - "Show me all Linux capsules."
 *   - "Show me all ARM64 capsules."
 *   - "Show me all capsules for the Elysium Vanguard
 *     Linux distro."
 *   - "Show me all capsules I can run on this device."
 *
 * The "can I run on this device" query is a
 * **capability match** — the capsule's
 * `Architecture` must match the device's actual
 * arch, and the capsule's `Distribution` must be
 * installed locally.
 */
object CapsuleSearch {

    /**
     * The query for filtering capsules. All fields
     * are optional; a null field means "no filter on
     * this dimension".
     */
    data class Query(
        val runtime: Runtime? = null,
        val architecture: Architecture? = null,
        val distribution: Distribution? = null,
        val text: String? = null,
    ) {
        init {
            require(text == null || text.isNotBlank()) {
                "Query.text must be non-blank if provided"
            }
        }
    }

    /**
     * Filter the [capsules] by the [query]. The
     * returned list is in the same order as the
     * input (the caller is responsible for sorting).
     */
    fun search(capsules: List<Capsule>, query: Query): List<Capsule> {
        return capsules.filter { capsule ->
            (query.runtime == null || capsule.runtime == query.runtime) &&
                (query.architecture == null ||
                    capsule.architecture == query.architecture ||
                    capsule.architecture == Architecture.ANY) &&
                (query.distribution == null ||
                    capsule.distribution == query.distribution ||
                    capsule.distribution == Distribution.ANY) &&
                (query.text.isNullOrBlank() || matchesText(capsule, query.text))
        }
    }

    /**
     * Filter the [capsules] by the device's actual
     * capabilities. A capsule is "runnable" if:
     *   - The runtime matches the device's runtime
     *     policy (the caller's decision).
     *   - The architecture matches the device's
     *     arch OR the capsule is `Architecture.ANY`.
     *   - The distribution is `Distribution.ANY` OR
     *     the user has the distribution installed
     *     locally (the caller passes
     *     [installedDistros] to declare this).
     */
    fun runnableOn(
        capsules: List<Capsule>,
        deviceArch: Architecture,
        installedDistros: Set<String>,
    ): List<Capsule> {
        return capsules.filter { capsule ->
            val archMatches = capsule.architecture == deviceArch ||
                capsule.architecture == Architecture.ANY
            val distroInstalled = capsule.distribution == Distribution.ANY ||
                installedDistros.contains(capsule.distribution.id)
            archMatches && distroInstalled
        }
    }

    private fun matchesText(capsule: Capsule, text: String): Boolean {
        val needle = text.lowercase()
        return capsule.name.lowercase().contains(needle) ||
            capsule.description.lowercase().contains(needle) ||
            capsule.id.value.lowercase().contains(needle)
    }
}
