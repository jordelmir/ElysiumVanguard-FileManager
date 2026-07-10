package com.elysium.vanguard.core.runtime.distros

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * PHASE 9.6.2 — Default [DistroHttpDownloader] backed by
 * [HttpURLConnection].
 *
 * Lives in its own file (Phase 9.6.3) so the Hilt module can provide it
 * as a singleton without dragging along the RuntimeViewModel. Honest
 * 30-second connect timeout and 5-minute read timeout (rootfs tarballs
 * can be 50+ MB; 5 min covers that on a slow link).
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
class RealDistroHttpDownloader : DistroHttpDownloader {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 5 * 60_000
    }

    @Throws(IOException::class)
    override fun open(url: String): InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code for $url")
        }
        return conn.inputStream
    }
}
