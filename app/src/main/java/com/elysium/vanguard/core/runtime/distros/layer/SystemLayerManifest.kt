package com.elysium.vanguard.core.runtime.distros.layer

import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/**
 * Phase 12.2 — ordered list of [SystemLayer]s with metadata.
 *
 * A manifest is a JSON file the build pipeline produces. The
 * schema is intentionally small:
 *
 * ```json
 * {
 *   "version": 1,
 *   "channel": "stable",
 *   "generatedAtMs": 1721000000000,
 *   "layers": [
 *     {
 *       "id": "elysium-cli",
 *       "displayName": "Elysium CLI",
 *       "version": "1.0.0",
 *       "tarball": "elysium-cli-1.0.0.tar.xz",
 *       "sha256": "ab12...",
 *       "notes": "Adds elysium-cli to /opt/elysium/bin."
 *     }
 *   ]
 * }
 * ```
 *
 * The tarball paths are resolved relative to the manifest's
 * directory unless they are absolute.
 *
 * Why JSON and not protobuf: the master order §11.5 says
 * "manifest firmado" but does not require a binary format, and
 * JSON is human-readable for the support / diagnostic use case
 * where a user pastes their manifest into a bug report.
 */
data class SystemLayerManifest(
    val version: Int,
    val channel: UpdateChannel,
    val generatedAtMs: Long,
    val layers: List<SystemLayer>
) {
    init {
        require(version >= 1) { "manifest version must be >= 1" }
        require(layers.isNotEmpty()) { "manifest must declare at least one layer" }
        // Two layers with the same id and version is a configuration
        // error — the higher version always wins and a duplicate is
        // almost certainly a copy-paste mistake.
        val seen = mutableMapOf<Pair<String, String>, Unit>()
        for (layer in layers) {
            val key = layer.id to layer.version
            require(key !in seen) {
                "duplicate layer entry: id=${layer.id} version=${layer.version}"
            }
            seen[key] = Unit
        }
    }

    companion object {
        const val CURRENT_VERSION = 1

        /**
         * Load a manifest from [file]. The tarball paths inside are
         * resolved against [tarballDir] (defaults to [file]'s parent
         * directory). Throws [IllegalArgumentException] on schema
         * violations; throws [java.io.IOException] on read errors.
         */
        @Throws(IllegalArgumentException::class)
        fun load(file: File, tarballDir: File = file.parentFile ?: File(".")): SystemLayerManifest {
            require(file.isFile) { "manifest file not found: $file" }
            val root = JSONObject(file.readText())
            val manifestVersion = root.getInt("version")
            require(manifestVersion == CURRENT_VERSION) {
                "unsupported manifest version: $manifestVersion (expected $CURRENT_VERSION)"
            }
            val channel = UpdateChannel.fromId(root.optString("channel", UpdateChannel.STABLE.id))
            val generatedAtMs = root.optLong("generatedAtMs", System.currentTimeMillis())
            val layersArray: JSONArray = root.getJSONArray("layers")
            val layers = (0 until layersArray.length()).map { i ->
                val obj = layersArray.getJSONObject(i)
                val tarballName = obj.getString("tarball")
                val tarballFile = File(tarballName).let {
                    if (it.isAbsolute) it else File(tarballDir, tarballName)
                }
                SystemLayer(
                    id = obj.getString("id"),
                    displayName = obj.optString("displayName", obj.getString("id")),
                    version = obj.getString("version"),
                    tarball = tarballFile,
                    sha256 = obj.getString("sha256"),
                    notes = obj.optString("notes", "")
                )
            }
            return SystemLayerManifest(
                version = manifestVersion,
                channel = channel,
                generatedAtMs = generatedAtMs,
                layers = layers
            )
        }
    }
}
