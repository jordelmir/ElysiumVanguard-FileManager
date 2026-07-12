package com.elysium.vanguard.core.runtime.distros

import java.io.IOException
import java.io.FilterInputStream
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
        const val MAX_REDIRECTS = 5
    }

    @Throws(IOException::class)
    override fun open(url: String): InputStream {
        var currentUrl = URL(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ElysiumVanguard/1.0 Android")
            }
            val code = conn.responseCode
            if (code in 200..299) {
                return object : FilterInputStream(conn.inputStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            conn.disconnect()
                        }
                    }
                }
            }
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("HTTP $code without Location for $currentUrl")
                }
                if (redirectCount == MAX_REDIRECTS) {
                    throw IOException("Too many redirects for $url")
                }
                val next = URL(currentUrl, location)
                if (currentUrl.protocol == "https" && next.protocol != "https") {
                    throw IOException("Refusing insecure redirect from $currentUrl to $next")
                }
                currentUrl = next
            } else {
                conn.disconnect()
                throw IOException("HTTP $code for $currentUrl")
            }
        }
        throw IOException("Too many redirects for $url")
    }
}
