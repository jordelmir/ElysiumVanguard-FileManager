package com.elysium.vanguard.core.runtime.cloud

/**
 * Phase 77 — the typed errors the
 * [HttpRemoteBuildClientImpl] returns. A
 * sealed class so the caller can
 * pattern-match on the variant. A
 * free-form string is never the error.
 *
 * The error variants cover the four
 * failure modes of a remote build:
 *
 * - **HTTP status**: the server
 *   returned a 4xx or 5xx. The client
 *   did its job; the server rejected
 *   the request (e.g. 400 Bad Request
 *   for a malformed `BuildRequest`,
 *   401 Unauthorized for a bad
 *   `authToken`, 502 Bad Gateway for
 *   a server-side crash).
 * - **Transport failure**: the
 *   underlying HTTP transport
 *   (`HttpURLConnection`) failed.
 *   This is a network / DNS / TLS
 *   problem on the client side, not
 *   a server problem. The caller may
 *   retry.
 * - **Invalid response**: the server
 *   returned a status 200 but the
 *   body was not a parseable JSON
 *   object. The server is misbehaving;
 *   retrying won't help until the
 *   server is fixed.
 * - **Missing field**: the response
 *   JSON is parseable but a required
 *   field (artifact name, exit code,
 *   base64 artifact bytes) is missing.
 *   Same recovery story as
 *   [InvalidResponse] — server bug.
 *
 * The `cause` is `null` for HTTP
 * status / invalid response / missing
 * field; it's the underlying
 * [java.io.IOException] (or similar)
 * for transport failures.
 */
sealed class RemoteBuildError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /**
     * The server returned an HTTP error
     * status. The [statusCode] is the
     * raw HTTP code; [body] is the
     * server's response body (often a
     * human-readable error message or
     * a JSON error envelope).
     */
    data class HttpStatus(
        val statusCode: Int,
        val body: String,
    ) : RemoteBuildError(
        "Remote build server returned HTTP $statusCode: ${body.take(200)}"
    )

    /**
     * The underlying HTTP transport
     * failed. The [details] string is
     * the exception's message (a
     * network error, DNS failure, TLS
     * handshake failure, etc.). The
     * caller may retry.
     */
    data class TransportFailure(
        val details: String,
    ) : RemoteBuildError(
        "Remote build transport failure: $details"
    )

    /**
     * The server returned HTTP 200 but
     * the body was not a parseable JSON
     * object. [details] is the parser's
     * error message. The server is
     * misbehaving; retrying won't help
     * until the server is fixed.
     */
    data class InvalidResponse(
        val details: String,
    ) : RemoteBuildError(
        "Remote build response was not valid JSON: $details"
    )

    /**
     * The response JSON is parseable
     * but a required field is missing
     * or has the wrong type. [field]
     * names the field; [details] is
     * the parser's error message. The
     * server is misbehaving.
     */
    data class MissingField(
        val field: String,
        val details: String,
    ) : RemoteBuildError(
        "Remote build response missing field '$field': $details"
    )
}
