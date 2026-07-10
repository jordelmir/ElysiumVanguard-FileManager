# PHASE 9.17 — LocalServerSyncAdapter (HTTP sync)

Closed: 2026-07-10.

## What landed

### Server-side: `CrdtSyncRouteRegistrar`

- `POST /api/crdt/sync?path=<docPath>` route handler:
  - Enforces the same `Bearer <token>` auth that other
    `LocalFileServer` routes use (server-side auth runs in
    the [LocalFileServer] middleware, returning 401 before our
    handler even fires — the registrar does its own check as
    belt-and-suspenders).
  - Reads the request body — the peer's companion file as raw
    UTF-8 text.
  - Looks up the server-side companion file at
    `<docPath>.server.elysium.sync` next to the document (in
    the orchestrator's `fsRoot`).
  - Merges the peer's `CrdtOpLog` into the server's via
    `CrdtOpLog.merge` (dedup by `(hlc, kind)`, idempotent).
  - Persists the merged companion back to disk.
  - Returns a JSON envelope: `{ nodeId, lastSeen, log }` —
    `log` is the merged op-log serialization, `nodeId` is
    `"server"` (fixed for now), and `lastSeen` is the
    highest-known HLC serialized.
- `resolveDocument(rootDir, rawPath)` — guards against path
  traversal (relative + absolute paths that escape the root
  return `null`).
- Wired into `LocalServerOrchestrator.registerRoutes` so the
  endpoint is exposed the moment the server starts.

### Client-side: `LocalServerSyncAdapter` + `HttpSyncTransport`

- `LocalServerSyncAdapter(baseUrl, authToken, relativePath,
  transport: HttpSyncTransport = JdkHttpSyncTransport())`
  implements `CrdtSyncAdapter`.
  - `syncWith(session)`:
    1. Serializes the local companion file.
    2. POSTs it to `${baseUrl}/api/crdt/sync?path=<encoded>`
       with `Authorization: Bearer <token>`.
    3. Parses the JSON envelope into an `ElysiumSyncFile`
       bound to the local document.
    4. Routes through `session.absorbRemote(remoteSync)` which
       uses the Phase 9.10 / 9.12 replay-and-merge path.
    5. Returns the count of newly-absorbed ops.
  - Failures (network errors, 4xx/5xx, malformed envelope)
    surface as `0` and a human-readable `lastError` for the
    screen.
- `JdkHttpSyncTransport` — pure-JVM HttpURLConnection-backed
  implementation. No extra dependency, no OkHttp, no auth lib.
  Pluggable via `HttpSyncTransport` interface so tests can
  inject fakes (see `ElysiumSyncFolderTest`).
- `LocalServerSyncAdapter.buildUrl` is public so callers can
  inspect the URL the adapter will POST to (and unit tests
  can verify the path encoding).

### `Json.decode` (utility, paired with existing `Json.encode`)

- The hand-rolled `Json` codec in `core/server/Json.kt` was
  originally encode-only — the sync envelope needed a parser
  to read the response back on the client.
- Adds a recursive-descent decoder that handles `{…}`,
  `[…]`, strings (with escapes incl. `\u`), numbers, `true` /
  `false` / `null`.
- Throws `JsonException` on malformed input.

## Tests (8, all green, real HTTP server)

`LocalServerSyncAdapterTest`:

- Unit-tests the registrar directly without HTTP:
  `isAuthorized`, `parseLog` (blank input), `resolveDocument`
  (rejects `../` traversal, accepts nested absolute paths).
- Validates `adapter.buildUrl` URL-encoding for spaces and
  special chars (`?`, `&`).
- Unit-tests `adapter.parseAndAbsorb` directly with a
  synthetic envelope — bypasses HTTP so the parsing logic
  has its own coverage.
- **End-to-end**: stands up a real `LocalFileServer` bound
  to `127.0.0.1:0` with the `CrdtSyncRouteRegistrar` route
  registered + a pre-seeded server-side companion holding a
  single `SINS "X"` op. Drives a `LocalServerSyncAdapter`
  against it from a client-side `CrdtDocumentSession` holding
  `SINS "Y"`. Asserts 1 op absorbed and the body has both
  `X` and `Y`.
- **Auth-failure**: points the adapter at the same server
  with the wrong token; asserts `0` absorbed and `lastError`
  mentions `401`.

## Quality

- Tests: **654** (+8).
- Failures: **0**.
- `assembleDebug`: green, 173 MB APK.

## What this unlocks

Two Elysium documents on two devices, talking via HTTP. The
editor's "Sync" action button now has a real implementation
in production code — previously it surfaced `EditorResult
.SyncNoPeer` because the `SyncHost` seam defaulted to `null`.
A subsequent phase wires the LocalServer's BaseUrl + auth
token into the engine's `SyncHost`, so tapping "Sync" inside
the editor actually talks to the running server. Phase 9.18
automates this at the directory level.

— elysium-autopilot
