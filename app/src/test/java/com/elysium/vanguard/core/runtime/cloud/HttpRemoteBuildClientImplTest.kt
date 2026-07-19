package com.elysium.vanguard.core.runtime.cloud

import com.elysium.vanguard.core.runtime.build.BuildRequest
import com.elysium.vanguard.core.runtime.build.ToolchainKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Base64

/**
 * Phase 77 — the test suite for the
 * [HttpRemoteBuildClientImpl]. The
 * suite drives every (request,
 * response, error) combination
 * without standing up a real HTTP
 * server. The
 * [HttpRemoteBuildClientImpl] takes
 * a [RemoteBuildTransport] in its
 * constructor; the test injects a
 * [RecordingRemoteBuildTransport]
 * that records the call + returns a
 * pre-configured response.
 */
class HttpRemoteBuildClientImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- Construction ---

    @Test
    fun `init rejects blank baseUrl`() {
        try {
            HttpRemoteBuildClientImpl(baseUrl = "", authToken = "tok")
            fail("expected IllegalArgumentException for blank baseUrl")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("baseUrl must not be blank"))
        }
    }

    @Test
    fun `init rejects baseUrl without scheme`() {
        try {
            HttpRemoteBuildClientImpl(baseUrl = "build.example.com", authToken = "tok")
            fail("expected IllegalArgumentException for scheme-less baseUrl")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("http://") || e.message!!.contains("https://"))
        }
    }

    @Test
    fun `init rejects blank authToken`() {
        try {
            HttpRemoteBuildClientImpl(baseUrl = "https://build.example.com", authToken = "")
            fail("expected IllegalArgumentException for blank authToken")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("authToken must not be blank"))
        }
    }

    @Test
    fun `init accepts a well-formed baseUrl and authToken`() {
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = RecordingRemoteBuildTransport(),
        )
        assertEquals("https://build.example.com", client.baseUrl)
        assertEquals("tok", client.authToken)
    }

    // --- URL construction ---

    @Test
    fun `build posts to v1 builds endpoint with trailing slash stripped`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com/",
            authToken = "tok",
            transport = transport,
        )
        client.build(sampleRequest(tmp))
        assertEquals("https://build.example.com/v1/builds", transport.lastUrl)
    }

    @Test
    fun `build sends POST method`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        client.build(sampleRequest(tmp))
        assertEquals("POST", transport.lastMethod)
    }

    @Test
    fun `build sends the bearer token in the Authorization header`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "my-secret-token",
            transport = transport,
        )
        client.build(sampleRequest(tmp))
        val authHeader = transport.lastHeaders["Authorization"]!!
        assertEquals("Bearer my-secret-token", authHeader)
    }

    @Test
    fun `build sends Content-Type application json`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        client.build(sampleRequest(tmp))
        val contentType = transport.lastHeaders["Content-Type"]!!
        assertTrue(
            "Content-Type must be application/json: $contentType",
            contentType.startsWith("application/json"),
        )
    }

    // --- Request body ---

    @Test
    fun `build encodes the project path as absolute in the request body`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val project = tmp.newFolder("my-project")
        client.build(sampleRequest(tmp, project = project))
        val json = JSONObject(transport.lastBody)
        assertEquals(project.absolutePath, json.getString("projectPath"))
    }

    @Test
    fun `build encodes the kind as a string in the request body`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        client.build(sampleRequest(tmp, kind = ToolchainKind.RUST))
        val json = JSONObject(transport.lastBody)
        assertEquals("RUST", json.getString("kind"))
    }

    @Test
    fun `build encodes the command as a JSON array in the request body`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        client.build(
            sampleRequest(
                tmp,
                command = listOf("build", "--release"),
            )
        )
        val json = JSONObject(transport.lastBody)
        val cmd = json.getJSONArray("command")
        assertEquals(2, cmd.length())
        assertEquals("build", cmd.getString(0))
        assertEquals("--release", cmd.getString(1))
    }

    @Test
    fun `build encodes env vars as a JSON object in the request body`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        client.build(
            sampleRequest(
                tmp,
                env = mapOf("FOO" to "bar", "BAZ" to "qux"),
            )
        )
        val json = JSONObject(transport.lastBody)
        val env = json.getJSONObject("env")
        assertEquals("bar", env.getString("FOO"))
        assertEquals("qux", env.getString("BAZ"))
    }

    @Test
    fun `build encodes env vars sorted by key for deterministic output`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        // Insertion order: B, A. The encoded body must put A first
        // (alphabetical) for deterministic comparison.
        client.build(
            sampleRequest(
                tmp,
                env = linkedMapOf("B" to "2", "A" to "1"),
            )
        )
        val body = transport.lastBody
        val aIndex = body.indexOf("\"A\"")
        val bIndex = body.indexOf("\"B\"")
        assertTrue("A must come before B in $body", aIndex < bIndex)
    }

    @Test
    fun `build encodes forceRemote as a boolean in the request body`() {
        val transport = RecordingRemoteBuildTransport().respondWith(okResponse())
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        client.build(sampleRequest(tmp, forceRemote = true))
        val json = JSONObject(transport.lastBody)
        assertTrue(json.getBoolean("forceRemote"))
    }

    // --- Response parsing ---

    @Test
    fun `build returns the parsed result on a 2xx response`() {
        val artifact = "hello-artifact".toByteArray()
        val transport = RecordingRemoteBuildTransport().respondWith(
            okResponse(
                artifactName = "out.bin",
                artifactBytes = artifact,
                sbom = "{\"name\":\"out.bin\"}",
            )
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isSuccess)
        val value = result.getOrThrow()
        assertEquals(0, value.exitCode)
        assertEquals("out.bin", value.artifactName)
        assertTrue(artifact.contentEquals(value.artifactBytes))
        assertEquals("{\"name\":\"out.bin\"}", value.sbom)
    }

    @Test
    fun `build returns HttpStatus error on a 4xx response`() {
        val transport = RecordingRemoteBuildTransport().respondWith(
            RemoteBuildResponse(statusCode = 401, body = "unauthorized")
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.HttpStatus)
        err as RemoteBuildError.HttpStatus
        assertEquals(401, err.statusCode)
        assertEquals("unauthorized", err.body)
    }

    @Test
    fun `build returns HttpStatus error on a 5xx response`() {
        val transport = RecordingRemoteBuildTransport().respondWith(
            RemoteBuildResponse(statusCode = 502, body = "{\"error\":\"build crashed\"}")
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.HttpStatus)
        err as RemoteBuildError.HttpStatus
        assertEquals(502, err.statusCode)
    }

    @Test
    fun `build returns InvalidResponse error on non-JSON 2xx body`() {
        val transport = RecordingRemoteBuildTransport().respondWith(
            RemoteBuildResponse(statusCode = 200, body = "<html>oops</html>")
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.InvalidResponse)
    }

    @Test
    fun `build returns MissingField error when exitCode is absent`() {
        val body = JSONObject().apply {
            put("artifactName", "out.bin")
            put("artifactBase64", Base64.getEncoder().encodeToString("x".toByteArray()))
            put("sbom", "{}")
        }.toString()
        val transport = RecordingRemoteBuildTransport().respondWith(
            RemoteBuildResponse(statusCode = 200, body = body)
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.MissingField)
        err as RemoteBuildError.MissingField
        assertEquals("exitCode", err.field)
    }

    @Test
    fun `build returns InvalidResponse error when exitCode is non-zero on a 2xx response`() {
        // A contract violation: the server is saying "I succeeded
        // (2xx), but the build failed (exitCode != 0)". The client
        // refuses to construct a RemoteBuildResult with a non-zero
        // exitCode (the data class's init requires 0).
        val body = JSONObject().apply {
            put("exitCode", 1)
            put("artifactName", "out.bin")
            put("artifactBase64", Base64.getEncoder().encodeToString("x".toByteArray()))
            put("sbom", "{}")
        }.toString()
        val transport = RecordingRemoteBuildTransport().respondWith(
            RemoteBuildResponse(statusCode = 200, body = body)
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.InvalidResponse)
        val errorMessage = err?.message ?: ""
        assertTrue(
            "error must mention the non-zero exit code: $errorMessage",
            errorMessage.contains("exitCode=1"),
        )
    }

    @Test
    fun `build returns MissingField error when artifactName is empty`() {
        val body = JSONObject().apply {
            put("exitCode", 0)
            put("artifactName", "")
            put("artifactBase64", Base64.getEncoder().encodeToString("x".toByteArray()))
            put("sbom", "{}")
        }.toString()
        val transport = RecordingRemoteBuildTransport().respondWith(
            RemoteBuildResponse(statusCode = 200, body = body)
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.MissingField)
        err as RemoteBuildError.MissingField
        assertEquals("artifactName", err.field)
    }

    @Test
    fun `build returns MissingField error when artifactBase64 is empty`() {
        val body = JSONObject().apply {
            put("exitCode", 0)
            put("artifactName", "out.bin")
            put("artifactBase64", "")
            put("sbom", "{}")
        }.toString()
        val transport = RecordingRemoteBuildTransport().respondWith(
            RemoteBuildResponse(statusCode = 200, body = body)
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.MissingField)
        err as RemoteBuildError.MissingField
        assertEquals("artifactBase64", err.field)
    }

    @Test
    fun `build returns InvalidResponse error when artifactBase64 is not valid base64`() {
        val body = JSONObject().apply {
            put("exitCode", 0)
            put("artifactName", "out.bin")
            put("artifactBase64", "not-valid-base64-!!!@@@")
            put("sbom", "{}")
        }.toString()
        val transport = RecordingRemoteBuildTransport().respondWith(
            RemoteBuildResponse(statusCode = 200, body = body)
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.InvalidResponse)
        val errorMessage = err?.message ?: ""
        assertTrue(
            "error must mention the base64 failure: $errorMessage",
            errorMessage.contains("base64"),
        )
    }

    // --- Transport failure ---

    @Test
    fun `build returns TransportFailure when the transport throws IOException`() {
        val transport = RecordingRemoteBuildTransport().respondWith(
            throwOnCall = IOException("connection refused")
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.TransportFailure)
        err as RemoteBuildError.TransportFailure
        assertTrue(
            "error must mention the underlying message: ${err.details}",
            err.details.contains("connection refused"),
        )
    }

    @Test
    fun `build returns TransportFailure when the transport throws a non-IOException`() {
        val transport = RecordingRemoteBuildTransport().respondWith(
            throwOnCall = RuntimeException("unexpected")
        )
        val client = HttpRemoteBuildClientImpl(
            baseUrl = "https://build.example.com",
            authToken = "tok",
            transport = transport,
        )
        val result = client.build(sampleRequest(tmp))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is RemoteBuildError.TransportFailure)
    }

    // --- Helpers ---

    private fun sampleRequest(
        tmp: TemporaryFolder,
        project: java.io.File = tmp.newFolder("project"),
        kind: ToolchainKind = ToolchainKind.RUST,
        command: List<String> = listOf("build", "--release"),
        env: Map<String, String> = emptyMap(),
        forceRemote: Boolean = true,
    ): BuildRequest = BuildRequest(
        projectPath = project,
        kind = kind,
        command = command,
        environmentVariables = env,
        forceRemote = forceRemote,
    )

    private fun okResponse(
        artifactName: String = "out.bin",
        artifactBytes: ByteArray = "hello".toByteArray(),
        sbom: String = "{}",
    ): RemoteBuildResponse {
        val body = JSONObject().apply {
            put("exitCode", 0)
            put("artifactName", artifactName)
            put(
                "artifactBase64",
                Base64.getEncoder().encodeToString(artifactBytes),
            )
            put("sbom", sbom)
        }.toString()
        return RemoteBuildResponse(statusCode = 200, body = body)
    }
}

/**
 * A [RemoteBuildTransport] that records
 * the last call + returns a
 * pre-configured response. Used by
 * the [HttpRemoteBuildClientImplTest]
 * to drive every (URL, headers,
 * body) combination without standing
 * up a real HTTP server.
 */
private class RecordingRemoteBuildTransport : RemoteBuildTransport {
    var lastMethod: String? = null
    var lastUrl: String? = null
    var lastHeaders: Map<String, String> = emptyMap()
    var lastBody: String = ""

    private var response: RemoteBuildResponse = RemoteBuildResponse(200, "{}")
    private var throwOnCall: Throwable? = null

    fun respondWith(response: RemoteBuildResponse): RecordingRemoteBuildTransport {
        this.response = response
        this.throwOnCall = null
        return this
    }

    fun respondWith(throwOnCall: Throwable): RecordingRemoteBuildTransport {
        this.throwOnCall = throwOnCall
        return this
    }

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
    ): RemoteBuildResponse {
        lastMethod = method
        lastUrl = url
        lastHeaders = headers
        lastBody = body
        throwOnCall?.let { throw it }
        return response
    }
}

// Suppress unused warnings on the
// assertion helpers that document
// the test contract (the file's
// reader sees assertNotNull +
// assertNull + these helpers in the
// same scope).
@Suppress("unused")
private fun <T> assertNotNullForTypeInference(value: T): T {
    assertNotNull(value)
    return value
}

@Suppress("unused")
private fun <T> assertNullForTypeInference(value: T?): T? {
    assertNull(value)
    return value
}
