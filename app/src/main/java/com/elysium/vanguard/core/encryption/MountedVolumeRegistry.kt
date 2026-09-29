package com.elysium.vanguard.core.encryption

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-wide registry of **unlocked** EncFS volumes.
 *
 * An `EncFsVolume` instance holds the derived master key, so it must only
 * exist while the user has unlocked the volume. The registry lets the UI
 * browse a volume after mount (the mount action lives in
 * `FileActionViewModel`; the browser screen is `features/encfs`) without
 * re-prompting for the password on every navigation.
 *
 * Keys are canonical volume paths, so `/sdcard/../sdcard/x.encfs` and
 * `/sdcard/x.encfs` hit the same entry.
 */
@Singleton
class MountedVolumeRegistry @Inject constructor() {

    private val volumes = ConcurrentHashMap<String, EncFsVolume>()

    fun mount(volumePath: String, volume: EncFsVolume) {
        volumes[normalize(volumePath)] = volume
    }

    fun unmount(volumePath: String): EncFsVolume? = volumes.remove(normalize(volumePath))

    fun get(volumePath: String): EncFsVolume? = volumes[normalize(volumePath)]

    fun isMounted(volumePath: String): Boolean = volumes.containsKey(normalize(volumePath))

    fun mountedPaths(): Set<String> = volumes.keys.toSet()

    fun clear() {
        volumes.clear()
    }

    private fun normalize(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)
}
