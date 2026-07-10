package com.elysium.vanguard.core.runtime.distros.custom

import java.io.File

/**
 * PHASE 9.6.3.1 — Result of validating a user-supplied rootfs URL.
 *
 * Tells the UI:
 *   - whether the URL is reachable;
 *   - what content-type / size the server reports;
 *   - whether it looks like a tarball we can extract.
 *
 * The introspector / installers use this to choose the right extractor
 * without downloading the whole archive first. If a server advertises a
 * `Content-Length` we use it as a hint for progress, but we never trust
 * it for the actual size (servers lie).
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
data class UrlProbe(
    /** Original URL we probed. */
    val url: String,
    /** Whether HEAD returned 2xx. */
    val reachable: Boolean,
    /** Status code reported, e.g. 200, 404, 503. -1 means "no response". */
    val statusCode: Int,
    /** Server's Content-Length, when present. */
    val contentLengthBytes: Long?,
    /** Server's Content-Type, when present. */
    val contentType: String?,
    /** Server's ETag or Last-Modified, whichever we found first. */
    val etagOrLastModified: String?,
    /** True when the URL's filename ends with `.tar.gz`, `.tar.xz`, or `.tgz`. */
    val looksLikeTarball: Boolean,
    /** Default rootfs flavor (which [DistroKind]/extractor to use). */
    val suggestedKind: CustomRootfsKind
) {
    /**
     * Heuristic: ok if reachable + looks like a tarball + (size < 2GB
     * or unknown). The 2 GB ceiling keeps us from accidentally
     * downloading a 50 GB Docker layer the user pasted.
     */
    val isAcceptable: Boolean
        get() = reachable && looksLikeTarball &&
            (contentLengthBytes == null || contentLengthBytes < 2L * 1024L * 1024L * 1024L)
}

/**
 * PHASE 9.6.3.1 — Type of artifact the user is pointing us at.
 *
 * Phase 9.6.3.1 supports tar/tar.gz/tar.xz/tgz. Docker layers and
 * custom formats come in 9.6.3.2.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
enum class CustomRootfsKind {
    TarGz,
    TarXz,
    Tar,
    Tgz,
    Unknown
}

/**
 * PHASE 9.6.3.1 — Validates a URL's reachability and pulls out the
 * information we need to decide whether to install it.
 *
 * Why a separate probe step: a HEAD on a 400 MB tarball is essentially
 * free; downloading it blindly wastes bandwidth and storage. We also
 * get useful metadata (Content-Type, filename hints) to pick the right
 * extractor.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
class CustomRootfsValidator(
    /** HTTP opener; production injects [RealCustomRootfsHttpProbe]. */
    private val probe: CustomRootfsHttpProbe = RealCustomRootfsHttpProbe()
) {
    fun probe(url: String): UrlProbe {
        require(url.isNotBlank()) { "url must not be blank" }
        val head = probe.head(url)
        val fileName = url.substringAfterLast('/').substringBefore('?').lowercase()
        return UrlProbe(
            url = url,
            reachable = head.reachable,
            statusCode = head.statusCode,
            contentLengthBytes = head.contentLengthBytes,
            contentType = head.contentType,
            etagOrLastModified = head.etag ?: head.lastModified,
            looksLikeTarball = inferKind(fileName) != CustomRootfsKind.Unknown,
            suggestedKind = inferKind(fileName)
        )
    }

    private fun inferKind(fileName: String): CustomRootfsKind {
        return when {
            fileName.endsWith(".tar.gz") -> CustomRootfsKind.TarGz
            fileName.endsWith(".tgz") -> CustomRootfsKind.Tgz
            fileName.endsWith(".tar.xz") -> CustomRootfsKind.TarXz
            fileName.endsWith(".txz") -> CustomRootfsKind.TarXz
            fileName.endsWith(".tar") -> CustomRootfsKind.Tar
            else -> CustomRootfsKind.Unknown
        }
    }
}

/**
 * PHASE 9.6.3.1 — Minimal HTTP HEAD contract.
 *
 * Phase 9.6.3.1 keeps it small: just enough for the validator. The full
 * download path (with retry, auth, mirrors) lives in `DistroInstaller`
 * via [com.elysium.vanguard.core.runtime.distros.DistroHttpDownloader];
 * this class is for the lightweight pre-check.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
interface CustomRootfsHttpProbe {
    fun head(url: String): CustomRootfsHttpProbeResult
}

data class CustomRootfsHttpProbeResult(
    val reachable: Boolean,
    val statusCode: Int,
    val contentLengthBytes: Long?,
    val contentType: String?,
    val etag: String?,
    val lastModified: String?
)

/**
 * PHASE 9.6.3.1 — Default [CustomRootfsHttpProbe] backed by [HttpURLConnection].
 */
class RealCustomRootfsHttpProbe : CustomRootfsHttpProbe {
    override fun head(url: String): CustomRootfsHttpProbeResult {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            val len = conn.getHeaderField("Content-Length")?.toLongOrNull()
            val ct = conn.contentType
            val etag = conn.getHeaderField("ETag")
            val lm = conn.getHeaderField("Last-Modified")
            conn.disconnect()
            CustomRootfsHttpProbeResult(
                reachable = code in 200..299,
                statusCode = code,
                contentLengthBytes = len,
                contentType = ct,
                etag = etag,
                lastModified = lm
            )
        } catch (_: Exception) {
            CustomRootfsHttpProbeResult(
                reachable = false,
                statusCode = -1,
                contentLengthBytes = null,
                contentType = null,
                etag = null,
                lastModified = null
            )
        }
    }
}
