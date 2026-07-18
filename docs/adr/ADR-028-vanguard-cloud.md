# ADR-028 — Vanguard Cloud (Sync, Backups, Remote Builds)

Status: **Accepted** (Phase 58, 2026-07-18)
Owners: Runtime + Cloud
Supersedes: none
Superseded by: none

## Context

The master vision doc names a "Vanguard
Cloud" pillar:

> "Sync, builds, backups y artefactos."

Until Phase 58 the runtime had:
- A [com.elysium.vanguard.core.runtime.build.RemoteBuildClient]
  interface (Phase 56) — the seam for a
  real cloud-backed build, but no
  production impl.
- A [com.elysium.vanguard.core.runtime.distros.manifest.installWithSignedManifest]
  flow (Phase 51) that downloads distros
  from a URL, but no persistent store of
  the user's distros / workspaces /
  applications.

Phase 58 ships the Vanguard Cloud
subsystem: the user's workspaces,
distros, and applications can sync to a
cloud store. The cloud store is the
backup target; the local device is the
primary.

## Decision

We split Vanguard Cloud into four small
pieces:

1. **`CloudSync`** (interface) + the value
   type `SyncState` — the runtime's
   surface for "sync this workspace to
   the cloud". The interface has methods
   `push(workspaceId)` (push local state
   to the cloud) and `pull(workspaceId)`
   (pull cloud state to the local
   device). The `SyncState` is a sealed
   class: `Idle` / `Pushing` / `Pulling`
   / `Synced` / `Error`. The production
   impl is `LocalCloudSync` (a stub that
   uses the local filesystem as the
   "cloud"; a real provider is a Phase
   60+ follow-up).

2. **`BackupService`** — the runtime's
   surface for "create an encrypted
   backup of this workspace". The
   service composes a `WorkspaceSnapshot`
   (Phase 49) with the cloud sync:
   the snapshot is the local backup; the
   cloud sync is the remote backup. The
   service exposes `backup(workspaceId)`
   and `restore(workspaceId, snapshotId)`.
   The backup is encrypted with the
   existing Tink (Phase 9.4.2) AES-256-GCM
   stack; the encryption key is the
   workspace's `WorkspaceManager`-managed
   vault key.

3. **`HttpRemoteBuildClient`** — the
   production impl of the Phase 56
   [com.elysium.vanguard.core.runtime.build.RemoteBuildClient]
   interface. The client sends a
   `BuildRequest` to a remote server
   (Oracle Free tier, or a user's own
   server), the server runs the build in
   an ephemeral container, the server
   returns the artifact. The client
   uses HTTPS + a bearer token (the
   user's auth token). Phase 58 ships
   the client as a `fun interface` (one
   `build` method); the actual HTTP
   implementation is a Phase 60+ follow-up
   that requires a real server.

4. **`RemoteBuildArtifact`** — the
   server's contract with the client.
   The artifact carries the binary +
   SBOM + manifest. The client receives
   the artifact; the runtime stores the
   binary in the local file system; the
   SBOM is exposed to the user (a future
   phase renders it as a tree).

### Why a `LocalCloudSync` stub

The cloud provider is a separate concern.
Phase 58 ships the **interface** + a stub
that uses the local filesystem as the
"cloud" (the user's `<filesDir>/cloud/`
directory). A real cloud provider (S3,
GCS, Azure Blob, IPFS) is a Phase 60+
follow-up. The stub lets the JVM tests
exercise the sync logic end-to-end
without an actual cloud account.

The stub is a faithful implementation: it
serializes the workspace state to a
file, persists it under
`<filesDir>/cloud/`, and on `pull`
restores the state. A real cloud impl
swaps the filesystem for an HTTP call;
the interface is unchanged.

### Why encrypted backups

The user's distros + workspaces + apps
are the runtime's state. A backup that
leaves the device is a privacy / security
concern. The backup is encrypted with the
existing Tink (Phase 9.4.2) AES-256-GCM
stack; the encryption key is the
workspace's vault key (a per-workspace
key generated at workspace creation).

A user with a "I lost my phone" concern
restores the backup on a new device. The
backup is encrypted with the user's
vault key; a thief without the key cannot
read the backup.

The encryption is a property of the
backup, not the cloud provider. A real
cloud provider (S3, GCS) sees only
ciphertext. The provider is "dumb
storage"; the runtime is the security
boundary.

### Why the `HttpRemoteBuildClient` is a
seam (not a full impl)

The actual HTTP call to a remote build
server requires a real server. Phase 58
ships the client as a `fun interface` (one
`build(BuildRequest): RemoteBuildResult`
method); the production impl uses
`HttpURLConnection` (JDK-native; no
third-party HTTP client). The client
serializes the request to JSON, POSTs
to the server, receives the JSON
response, returns the artifact.

The server is a separate project (the
"Oracle Free build server"). Phase 58
ships the client; the server is a Phase
60+ follow-up.

## Consequences

Positive:

- The Vanguard Cloud pillar is real at
  the configuration layer. The user can
  sync workspaces to a cloud store,
  back up workspaces to an encrypted
  cloud snapshot, and build projects
  remotely (when the server exists).
- The cloud sync is JVM-testable via the
  `LocalCloudSync` stub. A test injects
  the stub, asserts the workspace state
  is round-tripped correctly.
- The backup is encrypted with the
  existing Tink stack. No new crypto
  dependency; the security model is the
  same as the vault.

Negative:

- The cloud provider is a stub. A user
  with a "sync to my S3 bucket" request
  needs to implement the `CloudSync`
  interface; the production impl is a
  follow-up.
- The remote build server is a separate
  project. The client is ready; the
  server is a Phase 60+ follow-up.

## Revisit triggers

- If the runtime gains a real cloud
  provider (S3 / GCS / Azure Blob), the
  `CloudSync` interface gains a
  `S3CloudSync` impl. The interface is
  unchanged.
- If the runtime gains a real remote
  build server, the
  `HttpRemoteBuildClient` gains the
  actual HTTP impl. The interface is
  unchanged.
- If the backup gains an "incremental
  backup" mode (only the diff since
  the last backup), the `BackupService`
  gains an `incrementalBackup()` method.
  Phase 58 ships full-snapshot backup.
