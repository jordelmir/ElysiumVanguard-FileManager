package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature

/**
 * Phase 3 / I-3.1 — the **Canonical Scene Manifest**.
 *
 * The manifest is the typed input the 3D pipeline
 * (skill 06) + the digital twin (skill 07)
 * consume. Per `.ai/skills/06-3d-cad-asset-pipeline/
 * SKILL.md` section 7 + the master vision's
 * scene manifest:
 *
 *   - The manifest is a list of `Canonical3DAsset`
 *     references + their `LOD` selection + their
 *     `Transform` + their `CoordinateSystem` + their
 *     parent-child relationship.
 *   - The manifest is signed; the manifest's content
 *     hash is the canonical id (per skill 04
 *     section 15 — Artifact hashing).
 *   - The manifest carries a `representationLevel`
 *     declaration (per `.ai/STANDARDS.md` 2.1).
 *
 * The manifest is **content-addressed**: same
 * assets + same LODs + same transforms + same
 * coordinate system + same parent-child graph
 * → same `contentHash` byte-for-byte.
 *
 * The manifest is **signed**: a `signature` field
 * binds the manifest to the producer. The signature
 * is verified at load time; an unsigned manifest
 * is a deployment error.
 *
 * The manifest is **append-only**: the manifest is
 * immutable; a new manifest is a new compilation.
 * A mutation is a new `CanonicalSceneManifest`
 * with a new `contentHash`.
 *
 * The manifest is **separate from the Phase 1
 * [SceneManifest]**: the Phase 1 manifest was a
 * stub (string IDs + LOD placeholders). The
 * canonical manifest is the Phase 3 typed shape
 * the 3D pipeline consumes.
 */
data class CanonicalSceneManifest(
    /**
     * The compilation's content hash (the
     * `VehicleRevision`'s content hash). The
     * manifest is bound to a specific compilation.
     */
    val revisionContentHash: ContentHash,
    /**
     * The list of canonical 3D assets. The list
     * is the typed input to the 3D renderer + the
     * digital twin.
     */
    val assets: List<Canonical3DAsset>,
    /**
     * The manifest-level coordinate system.
     * Individual assets MAY override their own
     * coordinate system (per their `Transform`).
     */
    val coordinateSystem: CoordinateSystem = CoordinateSystem.LOCAL,
    /**
     * The representation level. The manifest is
     * the user-facing declaration of "what level
     * of detail the scene is".
     */
    val representationLevel: RepresentationLevel,
    /**
     * The manifest's signature. The signature
     * binds the manifest to the producer (a publisher,
     * a maintainer, a project). The signature is
     * verified at load time.
     */
    val signature: Signature,
) {
    init {
        require(assets.isNotEmpty()) {
            "CanonicalSceneManifest.assets must not be empty; " +
                "an empty manifest has no scene to render"
        }
        require(representationLevel != RepresentationLevel.UNKNOWN) {
            "CanonicalSceneManifest.representationLevel must be set; " +
                "an UNKNOWN representation level is a deployment error"
        }
        // The asset references must form a valid graph:
        // every asset's `parentId` (when set) must
        // reference an asset in the same manifest.
        val assetIds = assets.map { it.id }.toSet()
        for (asset in assets) {
            val parent = asset.parentId ?: continue
            require(parent in assetIds) {
                "CanonicalSceneManifest: asset ${asset.id} has " +
                    "parent $parent which is not in the manifest"
            }
        }
        // The asset graph must be acyclic (a cycle would
        // cause infinite recursion at render time).
        require(isAcyclic(assets)) {
            "CanonicalSceneManifest: asset graph has a cycle"
        }
    }

    /**
     * The manifest's content hash. Computed in
     * the `init` block from the canonical form
     * of the manifest. The hash is the manifest's
     * canonical id.
     *
     * The canonical form is:
     *
     * ```
     * scene-manifest:v2
     * |revision=<hash>
     * |coordinateSystem=<value>
     * |representationLevel=<value>
     * |assets=<sorted by id>
     * |signatures=<sorted by id>
     * ```
     */
    val contentHash: ContentHash

    init {
        val canonical = buildCanonicalForm(
            revisionContentHash = revisionContentHash,
            assets = assets,
            coordinateSystem = coordinateSystem,
            representationLevel = representationLevel,
            // The signature is NOT part of the canonical
            // form (the signature is computed over the
            // canonical form; including it in the
            // canonical form would be a circular
            // definition). The signature is the
            // producer's binding to the canonical form.
            includeSignature = false,
        )
        contentHash = ContentHash.of(canonical)
    }

    companion object {
        /**
         * Build the canonical form of a manifest. The
         * canonical form is the deterministic UTF-8
         * byte sequence used to compute the content
         * hash + to verify the signature.
         *
         * The function is **total**: every manifest
         * is built. The function is **deterministic**:
         * same inputs → same canonical string.
         *
         * The function is **public** so the signature
         * verifier can build the canonical form +
         * verify the signature against it.
         */
        fun buildCanonicalForm(
            revisionContentHash: ContentHash,
            assets: List<Canonical3DAsset>,
            coordinateSystem: CoordinateSystem,
            representationLevel: RepresentationLevel,
            includeSignature: Boolean,
            signature: Signature? = null,
        ): String = buildString {
            append("scene-manifest:v2")
            append("|revision=").append(revisionContentHash.value)
            append("|coordinateSystem=").append(coordinateSystem.name)
            append("|representationLevel=").append(representationLevel.name)
            append("|assets=")
            append(
                assets.sortedBy { it.id.value }.joinToString(";") { asset ->
                    asset.canonicalForm()
                },
            )
            if (includeSignature && signature != null) {
                append("|signature=").append(signature.value)
            }
        }

        /**
         * Verify the manifest's signature. The
         * function builds the canonical form +
         * compares the embedded signature to the
         * expected signature. A failed verification
         * is a hard rejection.
         */
        fun verifySignature(
            manifest: CanonicalSceneManifest,
            expectedSignature: Signature,
        ): Result<Unit> {
            val canonical = buildCanonicalForm(
                revisionContentHash = manifest.revisionContentHash,
                assets = manifest.assets,
                coordinateSystem = manifest.coordinateSystem,
                representationLevel = manifest.representationLevel,
                includeSignature = true,
                signature = expectedSignature,
            )
            val expectedCanonical = buildCanonicalForm(
                revisionContentHash = manifest.revisionContentHash,
                assets = manifest.assets,
                coordinateSystem = manifest.coordinateSystem,
                representationLevel = manifest.representationLevel,
                includeSignature = false,
            )
            // The signature is computed over the
            // canonical form WITHOUT the signature
            // field; the expected signature must
            // match.
            val recomputed = com.elysium.vanguard.foundry.core.ontology.primitives.Signature
                .sign(expectedCanonical, expectedSignature.value.toByteArray())
            val actual = manifest.signature
            return if (recomputed.value == actual.value) {
                Result.success(Unit)
            } else {
                Result.failure(
                    FoundryError.ArtifactIntegrityFailure(
                        artifactId = manifest.contentHash.value,
                        reason = "manifest signature verification failed: " +
                            "expected ${recomputed.value}, got ${actual.value}",
                    ),
                )
            }
        }

        private fun isAcyclic(assets: List<Canonical3DAsset>): Boolean {
            val children = assets.groupBy { it.parentId?.value }
            val roots = assets.filter { it.parentId == null }
            // BFS from each root; a visited node means
            // a cycle.
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            for (root in roots) {
                queue.addLast(root.id.value)
            }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) return false  // cycle
                val kids = children[current] ?: continue
                for (kid in kids) {
                    queue.addLast(kid.id.value)
                }
            }
            // Every node must be reachable from a root.
            return visited.size == assets.size
        }
    }
}

/**
 * The coordinate system of a manifest / an asset
 * / a transform. The coordinate system is the
 * basis the positions / rotations / scales are
 * expressed in.
 *
 * - [LOCAL] — relative to the parent's transform.
 *   The default for child assets.
 * - [WORLD] — relative to the scene's origin
 *   (the manifest's coordinate system).
 * - [MODEL] — relative to the model's bounding
 *   box (a normalized -1..1 cube). Useful for
 *   preview renders.
 * - [VIEW] — relative to the camera. Rare; only
 *   for HUD overlays.
 */
enum class CoordinateSystem {
    LOCAL,
    WORLD,
    MODEL,
    VIEW,
}
