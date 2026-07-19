# Phase 77 — HttpRemoteBuildClientImpl (the real remote build client)

The remote build path was a stub. An agent action that called
`build()` got 64 bytes of `0x42` back, every time. Phase 77
closes the gap with a real HTTP client + a transport seam +
typed errors + 25 JVM tests.

## What shipped

### 1. The transport seam

`app/src/main/java/com/elysium/vanguard/core/runtime/cloud/RemoteBuildTransport.kt`

A `RemoteBuildTransport` interface (test seam) and
`RemoteBuildResponse` data class. The transport's only
function is `execute(method, url, headers, body)`. The
production impl (`HttpRemoteBuildTransport` in
`RemoteBuildTransportImpl.kt`) wraps `HttpURLConnection`;
the test impl (`RecordingRemoteBuildTransport` in
`HttpRemoteBuildClientImplTest.kt`) records the call and
returns a pre-configured response.

### 2. The real client

`app/src/main/java/com/elysium/vanguard/core/runtime/cloud/HttpRemoteBuildClientImpl.kt`

`HttpRemoteBuildClientImpl(baseUrl, authToken, transport)`:

- **Validates inputs** at construction: `baseUrl` is
  non-blank + `http(s)://`; `authToken` is non-blank.
  Misconfiguration fails at Hilt graph build.
- **Serializes** the `BuildRequest` to a JSON envelope.
  The `env` map is sorted for deterministic output.
- **POSTs** to `<baseUrl>/v1/builds` with the
  `Authorization: Bearer <authToken>` +
  `Content-Type: application/json` headers.
- **Parses** the response:
  - 2xx → decode base64 artifact bytes, return
    `Result.success(RemoteBuildResult)`.
  - 4xx / 5xx → return
    `Result.failure(RemoteBuildError.HttpStatus)`.
- **Catches** transport IOExceptions and translates to
  `Result.failure(RemoteBuildError.TransportFailure)`.

### 3. The server contract

The endpoint, request, and response schemas are
documented in the `HttpRemoteBuildClientImpl` KDoc:

- **Endpoint**: `POST <baseUrl>/v1/builds`
- **Request**: `{projectPath, kind, command, env, forceRemote}`
- **Response** (200): `{exitCode, artifactName, artifactBase64, sbom}`

A 2xx response with `exitCode != 0` is a contract
violation; the client refuses to construct a
`RemoteBuildResult` with a non-zero exit code and
returns `RemoteBuildError.InvalidResponse` instead.

### 4. Typed errors

`app/src/main/java/com/elysium/vanguard/core/runtime/cloud/RemoteBuildError.kt`

A sealed class with four variants:

- `HttpStatus(statusCode, body)` — server returned a
  non-2xx status.
- `TransportFailure(details)` — `HttpURLConnection`
  raised (network error, DNS, TLS, etc.).
- `InvalidResponse(details)` — server returned 2xx but
  the body was not parseable JSON or the JSON had an
  invalid base64 artifact.
- `MissingField(field, details)` — the response JSON is
  parseable but a required field is absent or empty.

### 5. Test coverage

`app/src/test/java/com/elysium/vanguard/core/runtime/cloud/HttpRemoteBuildClientImplTest.kt`

25 new tests, all green:

- 3 construction tests
- 4 URL/header tests
- 7 request body tests (incl. deterministic env sort)
- 8 response parsing tests (2xx, 4xx, 5xx, missing
  fields, non-zero exitCode, invalid base64, invalid JSON)
- 2 transport failure tests (IOException + generic
  `Throwable`)
- 1 deterministic-output test

Test count: **2596 → 2621** (25 new + 0 broken).

## What we are NOT doing (yet)

- **Hilt wiring** of `HttpRemoteBuildClientImpl` (the
  `@Binds` for the `HttpRemoteBuildClient` interface).
  No consumer is wired yet. A follow-up phase adds a
  `CloudModule` + migrates the gateway's HTTP client
  + the agent's `runBuild` collaborator to the impl.
- **The build server itself** (a separate project; the
  Phase 60+ scope). The server's API is documented in
  the client KDoc; until the server exists, the
  client can be tested with `RecordingRemoteBuildTransport`
  but not used end-to-end.
- **Retry / exponential backoff** on
  `TransportFailure`. A future phase adds it at the
  client level.

## Files changed

- **NEW** `app/src/main/java/com/elysium/vanguard/core/runtime/cloud/HttpRemoteBuildClientImpl.kt`
- **NEW** `app/src/main/java/com/elysium/vanguard/core/runtime/cloud/RemoteBuildTransport.kt`
- **NEW** `app/src/main/java/com/elysium/vanguard/core/runtime/cloud/RemoteBuildTransportImpl.kt`
- **NEW** `app/src/test/java/com/elysium/vanguard/core/runtime/cloud/HttpRemoteBuildClientImplTest.kt`
- **NEW** `docs/adr/ADR-031-http-remote-build-client.md`
- **NEW** `docs/changelogs/PHASE_77_HTTP_REMOTE_BUILD_CLIENT.md` (this file)
- (unchanged) `app/src/main/java/com/elysium/vanguard/core/runtime/cloud/HttpRemoteBuildClient.kt` —
  the `HttpRemoteBuildClientStub` is kept (not deleted)
  for the existing test seam + offline development.

## Build status

- `compileDebugKotlin`: ✓
- `compileDebugUnitTestKotlin`: ✓
- `testDebugUnitTest`: 2621/2621 (25 new + 2596 existing)
- `assembleDebug`: ✓ (debug APK built)

## What's next

The next gap-closure items (in priority order):

1. **Hilt wiring** of `HttpRemoteBuildClientImpl` +
   migration of the gateway's HTTP client to the new
   impl (one-file change in a Hilt module).
2. **Real Elysium Vanguard Linux distro** (placeholder
   hash → built rootfs).
3. **Real market downloader** (HTTP byteSource for
   `MarketInstaller`).
4. **SecurityAudit persistence** (NDJSON; the audit is
   in-memory only today).
5. **Real Desktop Shell** (windowed Compose shell, not
   text list).
6. **Kill switch UI** for signature mismatch.
