package com.elysium.vanguard.core.runtime.cloud

import com.elysium.vanguard.core.runtime.build.BuildRequest
import com.elysium.vanguard.core.runtime.build.RemoteBuildResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/**
 * Phase 77 — the production impl of
 * [HttpRemoteBuildClient].
 *
 * Until Phase 77 the only impl was
 * [HttpRemoteBuildClientStub], which
 * returned a placeholder artifact
 * without ever talking to a server.
 * The agent's `runBuild` collaborator
 * (Phase 73) and the gateway Command
 * Core's HTTP client both relied on
 * the stub. A real build never
 * happened.
 *
 * Phase 77 wires the real client:
 *
 * - Endpoint: `POST <baseUrl>/v1/builds`.
 * - Request body: a JSON envelope
 *   (see [encodeRequest] for the
 *   schema).
 * - Auth: `Authorization: Bearer
 *   <authToken>`.
 * - Response: a JSON envelope
 *   (see [decodeResponse] for the
 *   schema).
 *
 * **Server contract** (documented
 * here so the build server side can
 * match it):
 *
 * Request:
 * ```json
 * {
 *   "projectPath": "/abs/path",
 *   "kind": "RUST",
 *   "command": ["build", "--release"],
 *   "env": {"KEY": "VALUE"},
 *   "forceRemote": true
 * }
 * ```
 *
 * Response (200 OK):
 * ```json
 * {
 *   "exitCode": 0,
 *   "artifactName": "build-output.bin",
 *   "artifactBase64": "QkJC...",  // base64 of the artifact bytes
 *   "sbom": "{\"name\":\"build-output.bin\",\"deps\":[...]}"
 * }
 * ```
 *
 * Response (4xx / 5xx): a JSON
 * envelope (or plain text) with the
 * server's error message. The client
 * surfaces the raw body in the
 * [RemoteBuildError.HttpStatus]
 * error.
 *
 * **Test seam**: the client does
 * not call [HttpURLConnection]
 * directly. It calls a
 * [RemoteBuildTransport] that the
 * constructor accepts; production
 * uses [HttpRemoteBuildTransport],
 * tests use a fake. This split
 * exists so the JVM test suite can
 * drive every (URL, headers, body)
 * combination without standing up a
 * real HTTP server.
 */
class HttpRemoteBuildClientImpl(
    override val baseUrl: String,
    override val authToken: String,
    private val transport: RemoteBuildTransport = HttpRemoteBuildTransport(),
) : HttpRemoteBuildClient {

    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "baseUrl must start with http:// or https://; got '$baseUrl'"
        }
        require(authToken.isNotBlank()) { "authToken must not be blank" }
    }

    /**
     * Send [request] to the remote build
     * server; return the artifact +
     * manifest. The HTTP response code
     * branches the result:
     * - 2xx → parse the JSON body, return
     *   the [RemoteBuildResult].
     * - 4xx / 5xx → return
     *   [RemoteBuildError.HttpStatus].
     * - non-2xx with non-parseable body
     *   → still return
     *   [RemoteBuildError.HttpStatus]
     *   with the raw body.
     *
     * The HTTP transport's IOExceptions
     * are caught + translated to
     * [RemoteBuildError.TransportFailure].
     *
     * The [RemoteBuildResult] requires
     * `exitCode == 0` (see
     * [com.elysium.vanguard.core.runtime.build.RemoteBuildResult.init]).
     * A server that returns 200 OK with
     * a non-zero `exitCode` is a contract
     * violation: the client returns a
     * [RemoteBuildError.InvalidResponse]
     * with a message that names the
     * unexpected `exitCode`.
     */
    override fun build(request: BuildRequest): Result<RemoteBuildResult> {
        val url = "${baseUrl.trimEnd('/')}/v1/builds"
        val requestBody = encodeRequest(request)
        val headers = mapOf(
            "Authorization" to "Bearer $authToken",
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json",
        )
        val response = try {
            transport.execute(method = "POST", url = url, headers = headers, body = requestBody)
        } catch (e: IOException) {
            return Result.failure(
                RemoteBuildError.TransportFailure(
                    details = HttpRemoteBuildTransport.describeFailure(e)
                )
            )
        } catch (e: Throwable) {
            // Other transport errors (RuntimeException, etc.) — wrap
            // generically. The contract is that any non-IOException
            // thrown by the transport is a programming error; we still
            // surface it as a typed failure rather than letting it
            // propagate.
            return Result.failure(
                RemoteBuildError.TransportFailure(
                    details = HttpRemoteBuildTransport.describeFailure(e)
                )
            )
        }
        if (response.statusCode !in 200..299) {
            return Result.failure(
                RemoteBuildError.HttpStatus(
                    statusCode = response.statusCode,
                    body = response.body,
                )
            )
        }
        return decodeResponse(response.body)
    }

    /**
     * Serialize a [BuildRequest] to the
     * server's JSON contract. The
     * serialization is deterministic
     * (the same [BuildRequest] always
     * produces the same JSON) so the
     * test suite can compare the
     * request body byte-for-byte.
     *
     * The `env` map is sorted by key
     * to make the body deterministic;
     * without sorting, a request with
     * `{"A": "1", "B": "2"}` and a
     * request with `{"B": "2", "A": "1"}`
     * would produce different byte
     * sequences (and fail a
     * byte-equality test).
     */
    internal fun encodeRequest(request: BuildRequest): String {
        val json = JSONObject()
        json.put("projectPath", request.projectPath.absolutePath)
        json.put("kind", request.kind.name)
        json.put("command", JSONArray(request.command))
        val envJson = JSONObject()
        request.environmentVariables.toSortedMap().forEach { (k, v) -> envJson.put(k, v) }
        json.put("env", envJson)
        json.put("forceRemote", request.forceRemote)
        return json.toString()
    }

    /**
     * Parse a server response body into a
     * [RemoteBuildResult]. The
     * [statusCode] is the raw HTTP code
     * (already 2xx by the time the
     * caller reaches this function).
     *
     * Required fields:
     * - `exitCode` (int): the build
     *   process's exit code. Must be
     *   0; non-zero is a contract
     *   violation.
     * - `artifactName` (string): the
     *   artifact's filename. Used by
     *   the caller to write the
     *   artifact to disk.
     * - `artifactBase64` (string):
     *   base64-encoded artifact bytes.
     *   Decoded with [Base64].
     * - `sbom` (string): the SBOM as a
     *   JSON string. Stored as-is on
     *   the [RemoteBuildResult].
     */
    internal fun decodeResponse(body: String): Result<RemoteBuildResult> {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return Result.failure(
                RemoteBuildError.InvalidResponse(details = e.message ?: "JSON parse failed")
            )
        }
        val exitCode = if (json.has("exitCode")) {
            try {
                json.getInt("exitCode")
            } catch (e: JSONException) {
                return Result.failure(
                    RemoteBuildError.MissingField(
                        field = "exitCode",
                        details = "expected int, got ${json.opt("exitCode")}",
                    )
                )
            }
        } else {
            return Result.failure(
                RemoteBuildError.MissingField(field = "exitCode", details = "field not present")
            )
        }
        if (exitCode != 0) {
            // A non-zero exit code on a 2xx response is a contract
            // violation: the server is saying "I succeeded, but the
            // build failed". The client surfaces this as a typed
            // error rather than constructing an invalid
            // RemoteBuildResult.
            return Result.failure(
                RemoteBuildError.InvalidResponse(
                    details = "server returned 2xx with non-zero exitCode=$exitCode"
                )
            )
        }
        val artifactName = json.optString("artifactName", "")
        if (artifactName.isEmpty()) {
            return Result.failure(
                RemoteBuildError.MissingField(
                    field = "artifactName",
                    details = "field missing or empty",
                )
            )
        }
        val artifactBase64 = json.optString("artifactBase64", "")
        if (artifactBase64.isEmpty()) {
            return Result.failure(
                RemoteBuildError.MissingField(
                    field = "artifactBase64",
                    details = "field missing or empty",
                )
            )
        }
        val artifactBytes = try {
            Base64.getDecoder().decode(artifactBase64)
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                RemoteBuildError.InvalidResponse(
                    details = "artifactBase64 is not valid base64: ${e.message}"
                )
            )
        }
        val sbom = json.optString("sbom", "")
        return Result.success(
            RemoteBuildResult(
                exitCode = exitCode,
                artifactBytes = artifactBytes,
                artifactName = artifactName,
                sbom = sbom,
            )
        )
    }
}
