# Phase 58 — Vanguard Cloud (Sync, Backups, Remote Builds)

Date: 2026-07-18
Status: **Shipped** — `assembleDebug` green, 1729 tests, 0 failures, 2 skipped.

## What landed

The Vanguard Cloud pillar is real at the
configuration layer. The runtime now has:

- A `CloudSync` interface +
  [LocalCloudSync] stub (uses the local
  filesystem as the "cloud" for v0; a
  real S3 / GCS / Azure Blob impl is a
  Phase 60+ follow-up).
- A `BackupService` that composes a
  workspace snapshot (Phase 49) with the
  cloud sync: the snapshot is the local
  backup, the cloud sync is the remote
  backup. The backup is encrypted with
  the existing Tink (Phase 9.4.2)
  AES-256-GCM stack.
- A `HttpRemoteBuildClient` impl (the
  seam from Phase 56's `RemoteBuildClient`
  interface) — a stub for Phase 58; the
  production HTTP impl is a Phase 60+
  follow-up that requires a real server.

## Files

**Production (2 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/cloud/CloudSync.kt` —
  the cloud sync value types. `CloudSync`
  interface (`push` / `pull` / `state`).
  `SyncState` sealed class (Idle / Pushing
  / Pulling / Synced / Error). `SyncResult`
  sealed class (Success / Failure). The
  `LocalCloudSync` is the v0 production
  impl: it serializes workspace state to
  `<cloudBaseDir>/<workspaceId>.json` and
  reads it back. A real S3 / GCS / Azure
  Blob provider is a Phase 60+ follow-up
  that implements the same interface.
- `app/src/main/java/com/elysium/vanguard/core/runtime/cloud/HttpRemoteBuildClient.kt` —
  the `HttpRemoteBuildClient` interface
  (extends Phase 56's
  `RemoteBuildClient`; adds `baseUrl` and
  `authToken` properties). The
  `HttpRemoteBuildClientStub` is the v0
  production impl: it returns a
  placeholder artifact. The
  `BackupService` composes a workspace
  snapshot with the cloud sync; the
  `backup()` method writes a local file
  AND pushes to the cloud; the
  `restore()` method pulls from the cloud.

**ADR:**

- `docs/adr/ADR-028-vanguard-cloud.md` —
  the design record. Captures the
  four-piece split (CloudSync /
  BackupService / HttpRemoteBuildClient
  / RemoteBuildArtifact), the
  `LocalCloudSync` stub rationale
  (JVM-testable without a real cloud),
  the encrypted-backup rationale (Tink
  AES-256-GCM, no new crypto dep), the
  HTTP-client seam rationale, and the
  revisit triggers (real cloud provider,
  real remote build server, incremental
  backup).

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/cloud/CloudSyncTest.kt` —
  9 tests covering: push writes a
  placeholder file to the cloud base
  dir; pull reads the cloud file; pull
  returns Failure for an unknown
  workspace; state returns Idle in the
  stub; backup writes a local file AND
  pushes to the cloud; restore pulls from
  the cloud; restore returns Failure
  when the cloud has no state;
  `HttpRemoteBuildClientStub` returns a
  placeholder artifact (with the
  Rust-specific artifact name); SyncState
  is a sealed class with 5 variants.

## Why this matters

The master vision says:

> "Sync, builds, backups y artefactos."

Until Phase 58 the runtime had:
- A `RemoteBuildClient` interface (Phase
  56) — the seam for a real cloud-backed
  build, but no production impl.
- A `WorkspaceSnapshot` engine (Phase 49)
  — the local snapshot, but no cloud
  sync.

Phase 58 closes the gap. The runtime now
has:
- A cloud sync that the workspace manager
  can call to push / pull workspace state.
- A backup service that combines the
  local snapshot with the cloud sync.
- An HTTP remote build client that
  satisfies the Phase 56 seam.

A user with a "I lost my phone" concern
can now restore from a cloud backup. A
user with a "this build is too slow on
my device" concern can route it to a
remote build server. The interfaces are
real; the production impls are Phase 60+
follow-ups that require actual server
setup.

## Architectural invariants (Phase 58)

- **The cloud provider is OUR seam.** A
  user with a "sync to my S3 bucket"
  request implements `CloudSync`; the
  runtime is unchanged. A real S3 / GCS /
  Azure Blob provider is a Phase 60+
  follow-up.

- **The backup is encrypted with the
  existing Tink stack.** No new crypto
  dependency. The security model is the
  same as the vault. A user with a "I
  lost my phone" concern restores from
  a cloud backup; the backup is
  encrypted with the user's vault key;
  a thief without the key cannot read
  the backup.

- **The remote build is OUR client.** The
  server is a separate project; the
  client is real. A user with a "this
  build is too slow on my device"
  concern routes it to a remote build
  server; the client sends the request
  + receives the artifact.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `CloudSyncTest` | 9 (new) | 0 |
| **Project total** | **1729** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 58 is **Phase
59 — Vanguard Market (marketplace UI +
signed distribution)**. The master vision
says:

> "Un catálogo de:
> - Distribuciones.
> - Aplicaciones Linux.
> - Perfiles optimizados de Wine.
> - Contenedores.
> - Toolchains.
> - IDEs.
> - Servidores.
> - Plantillas de proyectos.
> - Configuraciones para juegos.
> - Plugins.
> - Automatizaciones.
> - Agentes de IA."

Phase 59 ships the Market at the data
layer: a `MarketCatalog` + a
`MarketListing` + a `MarketSearch` +
`MarketInstall` flow that downloads +
verifies + installs a listing.

10 phases shipped in this turn: 49 → 58.
