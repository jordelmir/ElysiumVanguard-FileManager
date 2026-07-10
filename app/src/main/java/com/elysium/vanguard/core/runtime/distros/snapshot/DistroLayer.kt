package com.elysium.vanguard.core.runtime.distros.snapshot

import java.io.File

/**
 * PHASE 9.6.7 — A single layer in a layered distro install.
 *
 * Layers let us share bytes between distros that share the same base
 * (e.g. all Ubuntu noble-mini-roots share busybox, libc). Today's
 * Phase 9.6.7 ships the **registry and metadata**; the actual
 * `linkat()`-based sharing lands once the underlying filesystem
 * supports it (or we add a userspace CoW).
 *
 * Each layer carries:
 *   - a stable id (we use content-hash once we have one; for now,
 *     we use the source distro id + a kind tag);
 *   - a [kind]: BASE (shared) or HEAD (distro-specific);
 *   - the directory on disk that holds its files.
 *
 * Phase 9.6.7 — first build; intentionally minimal.
 */
data class DistroLayer(
    val id: String,
    val distroId: String,
    val kind: Kind,
    val rootfsDir: File,
    val bytes: Long,
    val sha256: String?
) {
    enum class Kind { BASE, HEAD }
}

/**
 * PHASE 9.6.7 — What ships inside a layer.
 *
 * The manifest we write to `<layer>/manifest.json` so that a future
 * linker (when one exists) can reconstruct cross-distro references.
 *
 * For the BASE case (shared), we record what packages/binaries the
 * base ships so other distros can layer on top without overwriting.
 *
 * Phase 9.6.7 — first build; intentionally minimal.
 */
data class LayerManifest(
    val layerId: String,
    val distroId: String,
    val kind: DistroLayer.Kind,
    val installedAtMs: Long,
    val packages: List<String> = emptyList(),
    val markers: List<String> = emptyList()
) {
    fun toJson(): String = buildString {
        append("{\"layerId\":\"").append(layerId).append("\",")
        append("\"distroId\":\"").append(distroId).append("\",")
        append("\"kind\":\"").append(kind.name).append("\",")
        append("\"installedAtMs\":").append(installedAtMs).append(',')
        append("\"packages\":[")
        append(packages.joinToString(",") { "\"$it\"" })
        append("],\"markers\":[")
        append(markers.joinToString(",") { "\"$it\"" })
        append("]}")
    }

    companion object {
        /** Parse a manifest back from text; returns null on malformed input. */
        fun parse(text: String): LayerManifest? {
            // Hand-rolled mini-parser, same approach as DistroManifestParser.
            val pairs = text.split(',').mapNotNull { entry ->
                val cleaned = entry.trim().removePrefix("{").removeSuffix("}")
                if (cleaned.isEmpty()) return@mapNotNull null
                val colon = cleaned.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val key = cleaned.substring(0, colon).trim().trim('"')
                val value = cleaned.substring(colon + 1).trim().trim('"')
                key to value
            }.toMap()
            val layerId = pairs["layerId"] ?: return null
            val distroId = pairs["distroId"] ?: return null
            val kindName = pairs["kind"] ?: return null
            val kind = try {
                DistroLayer.Kind.valueOf(kindName)
            } catch (_: Exception) {
                return null
            }
            val installedAt = pairs["installedAtMs"]?.toLongOrNull() ?: 0L
            return LayerManifest(
                layerId = layerId,
                distroId = distroId,
                kind = kind,
                installedAtMs = installedAt,
                packages = emptyList(),
                markers = emptyList()
            )
        }
    }
}
