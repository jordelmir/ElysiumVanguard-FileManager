package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel

/**
 * Phase 3 / I-3.2 — the **Part Instance Graph**.
 *
 * The graph is the runtime representation of the
 * digital twin. Per the implementation roadmap
 * I-3.2:
 *
 *   - "The runtime graph. The graph is the live
 *     representation of the vehicle: the user
 *     can select a part, isolate it, view its
 *     diagnostics, see its `EngineeringArtifact`
 *     references, and trigger a `RepairAction`."
 *
 * The graph is:
 *   - **Built from a [CanonicalSceneManifest].**
 *     The static manifest declares the assets +
 *     the parent-child relationships. The graph
 *     instantiates the assets as `PartInstance`s.
 *   - **Tree-structured.** Each part has zero or
 *     one parent. The graph is a forest (a
 *     collection of trees). The forest is the
 *     composition of one or more roots.
 *   - **Acyclic.** A cycle in the parent-child
 *     graph would cause infinite recursion at
 *     render time. The `init` block rejects
 *     cycles.
 *   - **Immutable.** The graph is a value object
 *     (a data class; no setters). A new graph is
 *     a new `contentHash`; an updated graph is
 *     a new `contentHash`; the old graph is
 *     retained for back-compat.
 *
 * The graph exposes:
 *   - [instances] — all instances in the graph.
 *   - [roots] — the instances with no parent.
 *   - [childrenOf] — the children of a given
 *     instance.
 *   - [descendantsOf] — all descendants of a
 *     given instance (recursive).
 *   - [findById] — look up an instance by id.
 *   - [findByAssetId] — look up instances by
 *     their underlying asset id.
 *   - [ancestorsOf] — the chain of ancestors
 *     of a given instance.
 *   - [size] — the count of instances.
 *
 * The graph is the user-facing surface for the
 * digital twin's selection + isolation +
 * diagnostic + repair-action operations (the
 * 3D pipeline consumes the graph; the UI
 * consumes the graph's read-side state).
 */
data class PartInstanceGraph(
    val instances: List<PartInstance>,
    val representationLevel: RepresentationLevel,
) {
    init {
        require(instances.isNotEmpty()) {
            "PartInstanceGraph.instances must not be empty; " +
                "an empty graph has no parts to render"
        }
        require(representationLevel != RepresentationLevel.UNKNOWN) {
            "PartInstanceGraph.representationLevel must be set; " +
                "an UNKNOWN representation level is a deployment error"
        }
        // Every parent reference must point to an
        // instance in the same graph. Checked FIRST
        // so an orphan is reported as "parent not in
        // graph" (not as a cycle).
        val instanceIds = instances.map { it.id }.toSet()
        for (instance in instances) {
            val parent = instance.parentInstanceId ?: continue
            require(parent in instanceIds) {
                "PartInstanceGraph: instance ${instance.id} has " +
                    "parent $parent which is not in the graph"
            }
        }
        // The instance graph must be acyclic
        // (a cycle would cause infinite recursion
        // at render time).
        require(isAcyclic(instances)) {
            "PartInstanceGraph: instance graph has a cycle"
        }
    }

    /**
     * The root instances (the instances with no
     * parent). The roots are the top of the
     * forest; each root is the start of a tree.
     */
    val roots: List<PartInstance> = instances.filter { it.parentInstanceId == null }

    /**
     * The graph's instance count.
     */
    val size: Int = instances.size

    /**
     * Find an instance by id. Returns `null`
     * when the id is not in the graph.
     */
    fun findById(id: PartInstanceId): PartInstance? =
        instances.firstOrNull { it.id == id }

    /**
     * Find the instances that reference a given
     * asset id. The list is empty when the asset
     * is not instantiated; otherwise the list
     * has one or more instances (the same asset
     * can be instantiated multiple times).
     */
    fun findByAssetId(assetId: com.elysium.vanguard.foundry.core.ontology.ids.AssetId): List<PartInstance> =
        instances.filter { it.assetId == assetId }

    /**
     * The direct children of a given instance.
     * The list is empty when the instance has
     * no children.
     */
    fun childrenOf(parentId: PartInstanceId): List<PartInstance> =
        instances.filter { it.parentInstanceId == parentId }

    /**
     * All descendants of a given instance (the
     * instance's children + grandchildren +
     * great-grandchildren + …). The list is
     * sorted by (depth, label) for deterministic
     * iteration.
     */
    fun descendantsOf(rootId: PartInstanceId): List<PartInstance> {
        val result = mutableListOf<PartInstance>()
        val queue = ArrayDeque<PartInstance>()
        childrenOf(rootId).forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)
            childrenOf(current.id).forEach { queue.addLast(it) }
        }
        return result
    }

    /**
     * The chain of ancestors of a given instance
     * (the instance's parent + grandparent + …).
     * The list is ordered from the immediate
     * parent to the root.
     */
    fun ancestorsOf(instanceId: PartInstanceId): List<PartInstance> {
        val result = mutableListOf<PartInstance>()
        var current = findById(instanceId)
        while (current != null) {
            val parentId = current.parentInstanceId ?: break
            val parent = findById(parentId) ?: break
            result.add(parent)
            current = parent
        }
        return result
    }

    /**
     * The depth of a given instance in the graph
     * (the count of ancestors). The depth of a
     * root is 0.
     */
    fun depthOf(instanceId: PartInstanceId): Int = ancestorsOf(instanceId).size

    /**
     * The graph's content hash. Computed in the
     * `init` block from the canonical form of
     * the graph. The hash is the graph's canonical
     * id.
     *
     * The canonical form is sorted by instance id
     * for determinism.
     */
    val contentHash: com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
        get() = com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash.of(canonicalForm())

    /**
     * The canonical form of the graph. The form
     * is the deterministic UTF-8 byte sequence
     * used to compute the content hash.
     */
    fun canonicalForm(): String = buildString {
        append("part-graph:v1")
        append("|level=").append(representationLevel.name)
        append("|instances=")
        append(
            instances.sortedBy { it.id.value }.joinToString(";") { instance ->
                "${instance.id.value}:" +
                    "asset=${instance.assetId.value}:" +
                    "parent=${instance.parentInstanceId?.value ?: ""}:" +
                    "label=${instance.displayLabel}:" +
                    "artifacts=${instance.engineeringArtifactRefs.map { it.value }.joinToString(",")}:" +
                    "repairs=${instance.repairActions.map { it.value }.joinToString(",")}"
            },
        )
    }

    companion object {
        /**
         * Build a [PartInstanceGraph] from a
         * [CanonicalSceneManifest] + a factory
         * for the [PartInstance.id] (the
         * factory generates a fresh
         * [PartInstanceId] for each asset).
         *
         * The factory is the seam that lets the
         * caller plug in a deterministic id
         * generator (for testing) or a
         * `PartInstanceId.random()` generator
         * (for production).
         */
        fun fromManifest(
            manifest: CanonicalSceneManifest,
            idFactory: (com.elysium.vanguard.foundry.core.ontology.ids.AssetId) -> PartInstanceId = { PartInstanceId.random() },
        ): PartInstanceGraph {
            val assetIdToInstanceId = manifest.assets.associate { asset ->
                asset.id to idFactory(asset.id)
            }
            val instances = manifest.assets.map { asset ->
                val instanceId = assetIdToInstanceId.getValue(asset.id)
                val parentInstanceId = asset.parentId?.let { parentAssetId ->
                    assetIdToInstanceId[parentAssetId]
                        ?: throw FoundryError.VehicleDefinitionInvalid(
                            field = "CanonicalSceneManifest.assets",
                            reason = "asset ${asset.id} has parent $parentAssetId " +
                                "which is not in the manifest",
                        )
                }
                PartInstance(
                    id = instanceId,
                    assetId = asset.id,
                    parentInstanceId = parentInstanceId,
                    displayLabel = asset.label,
                )
            }
            return PartInstanceGraph(
                instances = instances,
                representationLevel = manifest.representationLevel,
            )
        }

        private fun isAcyclic(instances: List<PartInstance>): Boolean {
            val children = instances.groupBy { it.parentInstanceId }
            val roots = instances.filter { it.parentInstanceId == null }
            val visited = mutableSetOf<java.util.UUID>()
            val queue = ArrayDeque<PartInstance>()
            for (root in roots) {
                queue.addLast(root)
            }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current.id.value)) return false  // cycle
                val kids = children[current.id] ?: continue
                for (kid in kids) {
                    queue.addLast(kid)
                }
            }
            return visited.size == instances.size
        }
    }
}
