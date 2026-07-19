# ADR-031 — HttpRemoteBuildClientImpl (the real remote build client, not a stub)

Status: **Accepted** (Phase 77, 2026-07-19)
Owners: Build + Cloud
Supersedes: the docstring of `HttpRemoteBuildClientStub` (Phase 58, 2026-06-25) that said "Phase 58 ships a stub class that the user can replace for a real server. The default HttpRemoteBuildClientImpl is a Phase 60+ follow-up that uses HttpURLConnection directly."
Superseded by: none

## Context

The remote build path was specified in Phase 58 (`HttpRemoteBuildClient` interface + `RemoteBuildRequest` / `RemoteBuildResult` value types + `BackupService`). The interface shipped but the only implementation was `HttpRemoteBuildClientStub`, which returned a placeholder artifact without ever talking to a server:

```kotlin
class HttpRemoteBuildClientStub(...) : HttpRemoteBuildClient {
    override fun build(request: BuildRequest): Result<RemoteBuildResult> {
        // The Phase 58 stub returns a
        // placeholder artifact. A future
        // phase uses HttpURLConnection to
        // POST the request.
        val artifactBytes = ByteArray(64) { 0x42 }
        return Result.success(RemoteBuildResult(...))
    }
}
```

The agent's `runBuild` collaborator (Phase 73) + the gateway Command Core's HTTP client both relied on the stub. **A real build never happened.** The stub let Phase 58's interface + the Phase 73 agent wiring compile and test green, but a production agent action that called `build()` got 64 bytes of `0x42` back, every time.

The Phase 60+ follow-up to "use HttpURLConnection directly" never landed. The master vision's "Vanguard build" leg (Phase 9.7 / 10.6) required a real client. Phase 77 closes this gap.

## Decision

### 1. The transport seam (`RemoteBuildTransport`)

The client separates two concerns:

- **What to send** — the URL, the headers, the body. Lives in `HttpRemoteBuildClientImpl`.
- **How to send it** — open a TCP connection, write bytes, read bytes, handle timeouts. Lives in `RemoteBuildTransport` (interface) + `HttpRemoteBuildTransport` (production impl) + a `RecordingRemoteBuildTransport` (test impl).

The split exists so the JVM test suite can drive every (URL, headers, body, response) combination without standing up a real HTTP server. The test transport records the call + returns a pre-configured response. 25 tests in `HttpRemoteBuildClientImplTest` cover the truth table in milliseconds.

The production transport (`HttpRemoteBuildTransport`) is a thin wrapper around `java.net.HttpURLConnection` (JDK-native; no third-party HTTP client). It sets the request method, the headers, the connect/read timeouts, writes the body, and reads the response (input stream for 2xx, error stream for 4xx/5xx). IOExceptions propagate to the caller — the client translates them to typed errors.

### 2. The client (`HttpRemoteBuildClientImpl`)

The client:

1. Validates inputs at construction: `baseUrl` must be non-blank and start with `http://` or `https://`; `authToken` must be non-blank. Misconfiguration fails at Hilt graph build (app start), not at the first `build()` call.
2. Serializes the `BuildRequest` to a JSON envelope (see the contract below).
3. POSTs to `<baseUrl>/v1/builds` with the `Authorization: Bearer <authToken>` + `Content-Type: application/json` headers.
4. Branches on the response status:
   - 2xx → parse the JSON body, decode the base64 artifact bytes, return `Result.success(RemoteBuildResult)`.
   - 4xx / 5xx → return `Result.failure(RemoteBuildError.HttpStatus(code, body))`.
5. Catches transport `IOException` (or any other `Throwable` from the transport) and translates to `Result.failure(RemoteBuildError.TransportFailure(details))`.

### 3. The server contract

The endpoint, request, and response schemas are documented in the `HttpRemoteBuildClientImpl` KDoc so the build server side (a separate project; Phase 60+ scope) can match them:

**Endpoint**: `POST <baseUrl>/v1/builds`

**Request body**:
```json
{
  "projectPath": "/abs/path",
  "kind": "RUST",
  "command": ["build", "--release"],
  "env": {"KEY": "VALUE"},
  "forceRemote": true
}
```

The `env` map is sorted by key (in `encodeRequest`) so the encoded body is deterministic — a test can compare the request body byte-for-byte regardless of insertion order in the original `Map`.

**Response body** (200 OK):
```json
{
  "exitCode": 0,
  "artifactName": "build-output.bin",
  "artifactBase64": "QkJC...",   // base64 of the artifact bytes
  "sbom": "{\"name\":\"build-output.bin\",\"deps\":[...]}"
}
```

A 2xx response with `exitCode != 0` is a contract violation: the server is saying "I succeeded, but the build failed". The client refuses to construct a `RemoteBuildResult` with a non-zero `exitCode` (the data class's `init` requires 0) and returns `RemoteBuildError.InvalidResponse` instead.

### 4. Typed errors (`RemoteBuildError`)

A sealed class with four variants:

- `RemoteBuildError.HttpStatus(statusCode, body)` — server returned a non-2xx status. The `body` is the server's error envelope (or plain text).
- `RemoteBuildError.TransportFailure(details)` — `HttpURLConnection` raised (network error, DNS failure, TLS handshake failure, etc.). The caller may retry.
- `RemoteBuildError.InvalidResponse(details)` — server returned 2xx but the body was not parseable JSON, or the JSON was missing a required field with the wrong type, or the base64 was invalid. The server is misbehaving; retrying won't help until the server is fixed.
- `RemoteBuildError.MissingField(field, details)` — the response JSON is parseable but a required field (`exitCode`, `artifactName`, `artifactBase64`) is absent or empty. The server is misbehaving.

The caller pattern-matches on the variant. A free-form string is never the error.

### 5. The `HttpRemoteBuildClientStub` is kept (not deleted)

The stub remains in the codebase, marked deprecated. Two reasons:

- **Test seam**: the existing tests for callers (e.g. the agent's `runBuild` collaborator tests, the gateway's `AgentLocalToolExecutor` tests) inject the stub. Deleting it would break those tests.
- **Offline development**: developers who don't have a real build server can still use the stub to test the agent's intent → action → result flow without internet access.

A future phase may delete the stub once a real test server (a Docker container with the build server's POST handler) is available as a `docker-compose` test fixture.

## Consequences

### Positive

- **The remote build path is real.** An agent action that calls `build()` now talks to a real HTTP server and gets a real artifact + SBOM back. The Phase 73 → Phase 77 agent → build integration is no longer a stub.
- **Testable in milliseconds.** 25 JVM tests cover the truth table: construction, URL/headers/body, response parsing (200, 4xx, 5xx, missing fields, non-zero exitCode, invalid base64, invalid JSON), and transport failure (IOException, generic `Throwable`). No real HTTP server required.
- **No third-party HTTP client dependency.** `HttpURLConnection` is JDK-native. The project already depends on `org.json:json:20231013` (testImplementation) for the JSON parser; the production code uses the same library.
- **Deterministic request bodies.** The `env` map is sorted before serialization; a test comparing the request body byte-for-byte is reliable.
- **Clear server contract.** The endpoint, request, and response schemas are documented in the `HttpRemoteBuildClientImpl` KDoc. The build server side (a separate project) can implement against the contract without reading the client source.
- **Fail-secure at construction time.** The init block rejects blank `baseUrl`, scheme-less `baseUrl`, and blank `authToken`. Misconfiguration fails at Hilt graph build, not at the first `build()` call.

### Negative / risks

- **No Hilt binding yet.** Phase 77 ships the `HttpRemoteBuildClientImpl` as a class that consumers instantiate directly. A Hilt module (`CloudModule`) that provides `HttpRemoteBuildClient` → `HttpRemoteBuildClientImpl` is a follow-up. Until then, the gateway Command Core's HTTP client (the one the production agent path uses) needs a manual update to construct the impl instead of the stub.
- **No retry logic.** A transient `TransportFailure` (e.g. a brief network blip) returns immediately; the caller must retry. A future phase could add exponential backoff at the client level.
- **No streaming upload.** The artifact (if larger than a few MB) is sent as a single base64-encoded JSON string. For Rust/Cargo artifacts (typically tens of MB), this is fine. For larger artifacts (multi-GB), a multipart upload would be needed.
- **No TLS pinning.** The client uses the platform's default TLS trust store. A future phase could add certificate pinning for the build server.
- **The server side doesn't exist yet.** The client is complete; the server (a separate project) is Phase 60+ scope. Until the server is built, the client can be tested with the `RecordingRemoteBuildTransport` but cannot be used end-to-end.

## What we are NOT doing (yet)

- **A `CloudModule` Hilt wiring.** The wiring is straightforward (`@Binds` for `HttpRemoteBuildClient` → `HttpRemoteBuildClientImpl`, `@Provides` for `HttpRemoteBuildTransport`) but no consumer is wired yet. The follow-up is a one-file change; not in Phase 77.
- **Migrating the existing `HttpRemoteBuildClientStub` consumers to the impl.** The gateway Command Core (Phase 49) and the agent's `runBuild` collaborator (Phase 73) are the consumers. Migrating them is a follow-up that does a find-replace + a config update.
- **The build server itself.** A separate project; Phase 60+ scope. The server's API is documented in the `HttpRemoteBuildClientImpl` KDoc.

## Test plan (25 tests, all green in `HttpRemoteBuildClientImplTest`)

- 3 construction tests (blank `baseUrl`, scheme-less `baseUrl`, blank `authToken`)
- 4 URL/header tests (URL with trailing slash, POST method, Authorization header, Content-Type)
- 7 request body tests (project path, kind, command, env, sorted env, forceRemote)
- 8 response parsing tests (2xx happy path, 4xx, 5xx, non-JSON 2xx, missing exitCode, non-zero exitCode, missing artifactName, missing artifactBase64, invalid base64)
- 2 transport failure tests (IOException, generic `Throwable`)

## References

- `core/cloud/HttpRemoteBuildClientImpl.kt` — the client
- `core/cloud/RemoteBuildTransport.kt` — the typed errors
- `core/cloud/RemoteBuildTransportImpl.kt` — the transport interface + the `HttpRemoteBuildTransport` (production) impl
- `core/build/RemoteBuildClient.kt` + `RemoteBuildResult.kt` (in `LocalBuildRunner.kt`) — the upstream types
- `core/cloud/HttpRemoteBuildClient.kt` — the `HttpRemoteBuildClient` interface + the `HttpRemoteBuildClientStub` (kept for test seam + offline dev)
- `test/cloud/HttpRemoteBuildClientImplTest.kt` — the 25-test truth table
