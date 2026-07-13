package com.elysium.vanguard.features.player

import java.util.UUID

/**
 * Allocates a process-unique Media3 session id for each audio view-model.
 *
 * Media3 rejects duplicate ids in the same process.  Its two-argument
 * builder defaults to an empty id, so every independent player must supply
 * one explicitly instead of relying on that default.
 */
internal object MediaSessionIdentity {
    private const val PREFIX = "elysium-audio-"

    fun newId(): String = PREFIX + UUID.randomUUID()
}
