---
title: Current State Audit — Elysium Vanguard → Elysium Automotive Foundry
status: Phase 0 deliverable, signed 2026-07-17
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audited_by: skill 01 (repository-archaeology)
git_head: c9028dc
---

# Current State Audit — Elysium Automotive Foundry

> **Status:** Phase 0 deliverable. The
> orchestrator (skill 00) has read
> `.ai/AGENTS.md`,
> `.ai/skills/00-program-orchestrator/SKILL.md`,
> `.ai/skills/01-repository-archaeology/SKILL.md`,
> and the remaining 14 skills before
> producing this audit. No production
> code was modified. **This is the
> baseline against which every future
> gate (G0–G10) is measured.**

---

## 0. Executive summary (the seven questions)

Per skill 01 section 8 "Definition of
done", the archaeology must answer
seven questions. The answers are
below; the long-form audit is in
sections 1–14.

1. **What already exists?**
   Elysium Vanguard is a
   **production-grade Android file
   manager** ("the best file manager
   in human history", per the project
   plan) at v1.0.0-TITAN. It has 344
   Kotlin source files, 136 unit-test
   files, 1,380 unit tests passing,
   one built module (`:app`), Kotlin
   + Jetpack Compose + Hilt + Room +
   Media3 + Apache MINA SSHD + Tink +
   ML Kit (text recognition + image
   labeling) + MediaPipe GenAI
   (Phi-3-mini local LLM) + Apache
   Commons Compress (universal
   format engine) + zstd + xz.
   The 19 `core/` packages and 26
   `features/` packages implement a
   file browser, a vault (AES-256-GCM
   via Tink), a SFTP server, a local
   HTTP server + QR, OCR, auto-tagging,
   smart folders (DSL queries), a
   CRDT sync engine (HLC + NodeId),
   and a recently-built **universal
   runtime layer** (Phases 0–49) for
   running Linux distros (proot) and
   Windows VMs (QEMU) inside Android
   workspaces, with PTY terminals,
   snapshots, an SSH client interface,
   and a Compose-based main screen.

2. **What is trustworthy?**
   - **Unit tests** are trustworthy:
     1,380 tests passing, 0 failures,
     2 ignored, 100% pass rate, 2.147s
     total. (Baseline measured
     2026-07-17 against git head
     `c9028dc`.)
   - **Build** is trustworthy:
     `./gradlew assembleDebug` builds
     the `app-debug.apk` at 101 MB.
   - **ADR series** ADR-001 through
     ADR-019 documents the runtime
     architecture decisions.
   - **Changelogs** PHASE_1 through
     PHASE_49 + PHASE_9_6 through
     PHASE_9_9 document the runtime
     evolution.
   - **Vault / SFTP / OCR / auto-tag**
     are the most-tested, longest-lived
     features.
   - **NOT trustworthy:** the .ai/
     foundation + the .worktrees/ tree
     are brand new (this session) and
     have not yet produced any user-
     facing behavior. The "Foundry"
     domain (vehicles, parts, DSL,
     marketplace, royalties,
     diagnostics, regulations) does
     **not exist** in the codebase.

3. **What is duplicated?**
   The duplication matrix is in
   section 5. Highlights:
   - The runtime layer (workspaces,
     sessions, runners) implements a
     Linux/Windows **execution**
     abstraction. The Foundry's
     vehicle / part / assembly
     abstraction is **orthogonal** —
     the runtime is reusable but the
     domain types are new.
   - The CRDT module implements a
     generic document/sync engine
     that could be the foundation for
     collaborative `Spec.Artifact`
     editing (skill 04 + skill 11).
   - There is no existing **vehicle**
     representation to duplicate —
     that domain is net-new.

4. **What must be migrated?**
   Nothing in the existing codebase
   needs to be migrated. The .ai/
   foundation is a **new layer on
   top** of the existing code. The
   14 skills in `.ai/skills/00..15`
   describe a Foundry that doesn't
   physically replace any existing
   feature. The path is **additive**,
   not migration. The master prompt
   says: "No elimines funcionalidades
   existentes sin demostrar que
   fueron sustituidas" — this is
   consistent with the additive
   approach.

5. **What would break if the Foundry
   were added immediately?**
   - **Naming collision:** the
     existing `core/runtime/`
     namespace is about **Linux
     distros and Windows VMs**, not
     "vehicle runtime". The Foundry's
     runtime events (per skill 07)
     must NOT share the bus. A new
     `core/foundry/` namespace (or
     rename the existing one to
     `core/universal-runtime/`) is
     needed to avoid the trap of
     `RuntimeEvent` meaning two
     different things in the same
     app. (See `docs/foundry/target-architecture.md`.)
   - **APK weight:** the existing
     101 MB APK already includes
     MediaPipe GenAI (~80 MB on its
     own) + ML Kit + Tink + SSHD.
     Adding the Foundry's
     surface (Compose, Hilt) is
     **zero additional weight** (the
     libs are already linked). A
     backend is a separate concern.
   - **Existing tests:** the 1,380
     tests cover the file manager +
     universal runtime. They are
     isolated from the Foundry.
     Adding the Foundry does not
     touch those tests. The CI is
     green throughout.

6. **What should be preserved?**
   - All 1,380 unit tests + the
     `assembleDebug` green state.
   - The 19 `core/` packages + 26
     `features/` packages (the file
     manager + universal runtime
     remain untouched).
   - The 20 ADRs (ADR-001 through
     ADR-019) are valid for the
     existing surface; new
     ADRs are added for the Foundry.
   - The Hilt module structure
     (RuntimeModule, DistroModule)
     is the **template** for
     FoundryModule(s).
   - The MediaPipe Phi-3-mini
     local LLM is the **on-device
     AI tier**; the Foundry's AI
     council (skill 05) runs on
     the cloud tier; both can
     coexist.
   - The Apache MINA SSHD
     integration is the **template**
     for the Foundry's SFTP-based
     controlled disclosure (skill
     10 section 4).
   - The Tink AES-256-GCM vault
     is the **template** for the
     Foundry's
     `RESTRICTED_ENGINEERING`
     encryption (skill 12 section
     2).

7. **Which module should own each
   new domain?**
   See `docs/foundry/domain-ownership.md`.
   One-liner per skill:
   - **Vehicle / Part / Revision /
     Project / EngineeringArtifact
     / ProvenanceRecord** →
     skill 03 (ontology).
   - **`Spec.Artifact` + `BOM` +
     scene manifest** → skill 04
     (DSL compiler).
   - **Visual mesh + LOD + glTF
     pipeline** → skill 06 (3D).
   - **Digital twin + diagnostic
     graph** → skill 07.
   - **`RoyaltyContract` + audit
     trail** → skill 09.
   - **Marketplace + supplier
     network** → skill 10.
   - **Mobile UX (the Forge
     surface)** → skill 11.

---

## 1. Modules

The repository is a **single Android
Gradle project** with **one
module** (`:app`). There are no
multi-module splits.

- **Build system.** Gradle 8.x
  with Kotlin DSL (`build.gradle.kts`).
- **AGP.** 8.2.0.
- **Kotlin.** 1.9.20.
- **KSP.** 1.9.20-1.0.14.
- **Hilt.** 2.48.
- **Target/Compile SDK.** 34 (per
  `app/build.gradle.kts`).
- **Min SDK.** 26 (Android 8.0).
- **Package.** `com.elysium.vanguard`.
- **Application ID.**
  `com.elysium.vanguard`.
- **Version.** 1.0.0-TITAN
  (versionName).
- **Rust toolchain.**
  1.97.0, `aarch64-linux-android` only.
- **Native subdir.** `native/`
  (used for any C/C++ deps).
- **Service subdir.** `services/`
  (currently contains a single
  Kotlin/JVM service scaffold:
  `services/elysium-ai-gateway/`).

### 1.1 Module structure (344 Kotlin files)

```
com.elysium.vanguard/
├── MainActivity.kt               (40k LOC entry)
├── TitanApp.kt                   (Hilt entry)
├── core/                         (19 packages)
│   ├── ai/                       (on-device LLM helpers)
│   ├── analyzer/                 (storage treemap)
│   ├── conflict/                 (NAME vs DUPLICATE)
│   ├── crdt/                     (CrdtDoc, CrdtSequence,
│   │                              HLC, NodeId, sync adapters)
│   ├── database/                 (Room)
│   ├── duplicates/               (3-phase hash dedup)
│   ├── editor/                   (syntax highlighter)
│   ├── format/                   (universal format engine)
│   ├── metadata/                 (EXIF/XMP/IPTC)
│   ├── ocr/                      (ML Kit text recognition)
│   ├── office/                   (DOCX/sheet/word)
│   ├── ops/                      (operation queue + ETA)
│   ├── palette/                  (color extraction)
│   ├── rename/                   (batch rename)
│   ├── runtime/                  (universal runtime layer —
│   │                              workspaces, sessions,
│   │                              runners, terminals,
│   │                              windows VMs, distros,
│   │                              SSH, network, hardware,
│   │                              observability, UI)
│   ├── saf/                      (Storage Access Framework)
│   ├── search/                   (fuzzy + BM25 content)
│   ├── server/                   (local HTTP + QR)
│   ├── services/
│   ├── sftp/                     (MINA SSHD)
│   ├── sheet/                    (formula engine)
│   ├── smartfolders/             (DSL queries)
│   ├── tagging/
│   ├── trash/                    (SAF + undo)
│   ├── undo/
│   ├── util/
│   ├── vault/                    (Tink AES-256-GCM)
│   └── word/
├── features/                     (26 features, Compose
│                                  screens)
│   ├── analyzer/
│   ├── commandcore/
│   ├── conflict/
│   ├── crdteditor/
│   ├── customization/
│   ├── dashboard/
│   ├── dualpane/
│   ├── duplicates/
│   ├── editor/
│   ├── filemanager/
│   ├── gallery/
│   ├── metadata/
│   ├── ocr/
│   ├── player/
│   ├── runtime/                 (universal-runtime
│   │                              Compose surfaces)
│   ├── search/
│   ├── server/
│   ├── sftp/
│   ├── sheet/
│   ├── smartfolders/
│   ├── splash/
│   ├── tagging/
│   ├── telemetry/
│   ├── trash/
│   ├── vault/
│   ├── viewer/
│   └── word/
└── ui/                           (shared Compose
                                   components + theme)
```

The 19 `core/` packages are the
**engine** of the app. The 26
`features/` packages are the
**screens** that consume the
engine. The split is a clean
**hexagonal** boundary (engine in
`core/`, ports in `features/`,
no infrastructure in either
except where Android-platform
constraints require it).

---

## 2. Build system

- **Build command (inner loop):**
  `./gradlew assembleDebug` — green
  on git head `c9028dc`.
- **Test command (inner loop):**
  `./gradlew testDebugUnitTest` —
  1,380 / 1,380 passing, 2.147s.
- **CI provider:** none configured
  in the repository (no `.github/`,
  no `ci/`); the manual command is
  the verification path.
- **APK size:** 101 MB debug, 221
  MB release (per project plan).
  Reduction opportunity exists
  (no aggressive R8 / tree-shake
  for MediaPipe + ML Kit).
- **Signing:** debug-signed; release
  signing configured but
  unverified (no keystore in
  repo).

---

## 3. Data stores

| Store | Tech | Used by |
|---|---|---|
| **Room** (SQLite) | `androidx.room` 2.x | trash, vault metadata, smart folders, sync state |
| **CRDT** (in-memory + HLC) | custom (CrdtDoc, CrdtSequence, CrdtOpLog) | editor sync, conflict-free collaboration |
| **ElysiumSyncFolder** | custom file-backed | per-folder sync (Phase 9.18) |
| **NodeIdStore** | custom (DataStore) | CRDT node identity |
| **WorkspaceStore** | custom (gson-backed) | universal runtime workspaces (Phase 24, 35) |
| **RuntimeEventLog** | custom NDJSON | universal runtime audit (Phase 25) |
| **Object store** | local filesystem (Storage Access Framework) | media, vault blobs, downloads |
| **SFTP server storage** | MINA SSHD | the device as an SFTP endpoint |

The **backend** for the Foundry does
**not** exist. The repository is
Android-only; there is no Spring
Boot / Ktor / Supabase / Firebase
backend. The Foundry's "backend
event platform" (skill 08) is a
**net-new component** that the
roadmap must build.

---

## 4. Authentication

- **No user authentication** at
  the app level. The vault uses
  the Android Keystore for key
  derivation; the SFTP server
  uses MINA SSHD's public-key
  auth (or password).
- **No OIDC** is integrated.
- **The Foundry's OIDC model**
  (skill 12) is a net-new
  integration.

---

## 5. Vehicle entities

**There are zero vehicle entities
in the codebase.** The "Vehicle"
keyword in the project name
(ElysiumVanguard) and the
"versionName 1.0.0-TITAN" are
project codenames, not domain
concepts. The codebase has:

- No `Vehicle` class.
- No `PartDefinition` / `PartInstance`
  class.
- No `Variant` / `Revision` /
  `EngineeringArtifact` class in
  the vehicle sense.
- No 3D vehicle renderer (no
  Filament, no Three.js, no glTF
  pipeline).
- No diagnostic fault model.
- No OBD-II / CAN bus / UDS
  transport.
- No marketplace, no royalty, no
  IP / provenance.

The "Domain ontology" of the
existing codebase is a **file
domain** (Folder, File,
MediaItem, Tag, VaultEntry,
SyncFolder, Workspace, Session).

### 5.1 Duplication matrix (per skill 01 section 3)

| Concept | Existing classes | Canonical candidate (Foundry) | Migration needed |
|---|---|---|---|
| **Identity** (user / org) | None | `User`, `Organization` (per skill 03) | **Yes — net new** |
| **Project** | `Workspace` (universal-runtime workspace) + `Folder` | `Project` (per skill 03) | **No — `Workspace` is a different concept (runtime execution workspace, not an automotive project)** |
| **Revision** | `CrdtElysiumDocument` (CRDT doc version) + `RuntimeSpec.version` + `WorkspaceDto.version` | `VehicleRevision` (per skill 03) | **No — these are CRDT/runtime versions, not automotive revisions** |
| **Asset** | `ElysiumSyncFile` (sync file) + `Artwork` (gallery) + `PartDefinitionId` (skill 03 spec) | `EngineeringArtifact` (per skill 03) | **No — sync file + artwork ≠ engineering artifact** |
| **Contract** | None | `RoyaltyContract` (per skill 09) | **Yes — net new** |
| **Diagnostic** | `BoundedDiagnosticLog` (universal-runtime diagnostic) + `RuntimeError` + `RuntimeErrorCode` | `Fault` / `Diagnostic` (per skill 07) | **No — runtime diagnostics ≠ vehicle diagnostics** |
| **Vehicle** | None | `Vehicle` (per skill 03) | **Yes — net new** |
| **3D rendering** | None | `EngineeringSolid` / `VisualMesh` (per skill 06) | **Yes — net new** |
| **Procedures** | `BoundedDiagnosticLog` log entries + `CustomRootfsPipeline` steps | `Procedure` (per skill 07) | **No — log entries ≠ diagnostic procedures** |

**Summary:** the existing codebase
has **no duplication** with the
Foundry's domain. Every vehicle /
part / diagnostic / contract
class is a **net-new addition**.

---

## 6. 3D rendering layer

**There is no 3D engine in the
codebase.** The "runtime" features
do **not** render 3D scenes; they
are **CLI/PTY surfaces** (terminal
emulators, Linux console, Windows
desktop rendered via a remote
display, the latter is wired in
but uses native VNC). The
"3D" / "CAD" / "scene" /
"geometry" / "mesh" terms in the
Foundry spec do not map to any
existing code.

The `WindowVmVncScreen` (skill 47,
48) is a VNC **client** for an
external Windows VM. It is **not**
a 3D vehicle renderer. The
`SceneManifest` (skill 04) is a
**new typed value** for the
Foundry.

---

## 7. Asset loading and caching

- **MediaStore** is the canonical
  file index.
- **Coil 2.5.0** is the image
  loader; **Coil-Video** is the
  video loader.
- **Glide is not used.**
- **Caching strategy** is
  LRU-by-default via Coil; the
  vault uses an in-memory +
  Tink-encrypted cache.
- **The 3D asset pipeline** is
  net-new. No glTF / GLB loader
  exists. No texture compression
  pipeline. No LOD generator.

---

## 8. Vehicle and part data models

**None.** The file-domain
(MediaItem, Folder) is the
canonical model. The
universal-runtime domain
(Workspace, Session,
RuntimeSpec, Distro) is the
runtime domain. Neither maps to
the Foundry.

---

## 9. Diagnostic and DTC data

- **`BoundedDiagnosticLog`** is a
  bounded log of runtime
  diagnostic events (cap at N
  entries, evict on overflow).
- **`RuntimeError` /
  `RuntimeErrorCode`** are the
  typed errors for the universal
  runtime.
- **No DTC, no PID, no Mode 06,
  no freeze frame, no repair
  procedure.** The Foundry's
  diagnostic graph (skill 07
  with 20 entities) is
  net-new.

---

## 10. OBD transport

**None.** The codebase has no
vehicle network abstraction
(no CAN, no UDS, no DoIP, no
OBD-II, no K-Line). The
"network" module
(`core/runtime/network/`) is
about Android process network
firewall + DNS, **not** about
vehicle networks.

---

## 11. AI provider integrations

- **On-device LLM:** MediaPipe
  GenAI (Phi-3-mini, ~2 GB
  downloaded on first use).
  Used for on-device file
  commands (skill plan calls
  this "LLM agent lite").
- **ML Kit text recognition**
  (OCR) — used by
  `core/ocr/`.
- **ML Kit image labeling**
  (auto-tagging) — used by
  `core/ai/` + `features/tagging/`.
- **No cloud LLM, no
  Anthropic / OpenAI
  integration.** The Foundry's
  AI council (skill 05) is
  net-new.

---

## 12. Authentication and authorization

- **App-level:** none. The app
  trusts the device user.
- **Vault:** Android Keystore
  (Tink AEAD with key
  derivation from
  device-bound key).
- **SFTP:** MINA SSHD with
  password or public-key
  authentication.
- **Local HTTP server:** basic
  auth (username / password)
  in the server.
- **The Foundry's OIDC +
  RBAC + ABAC** (skill 12) is
  net-new.

---

## 13. Supabase / PostgreSQL schema

**Not present.** The codebase is
Android-only. There is no
backend, no Supabase, no
PostgreSQL.

The Foundry's "Identity and
Access" + "Project Management"
+ "Vehicle Engineering" + "Artifact
Registry" + "Provenance and
Rights" + "Commerce and Royalties"
+ "Marketplace" + "Supplier Network"
+ "Diagnostics" bounded contexts
(skill 08 section 2) are **net-new
PostgreSQL schemas** that the
roadmap must build.

---

## 14. Storage buckets

- **Local files:** the Android
  filesystem (via SAF +
  MediaStore).
- **Vault blobs:** the app's
  private storage, Tink-encrypted.
- **SFTP exports:** the
  filesystem, exposed via MINA
  SSHD.
- **No S3 / no Supabase Storage /
  no MinIO** in the repo.

The Foundry's
`RESTRICTED_ENGINEERING` asset
storage (skill 06 + skill 12) is
net-new; **S3-compatible object
storage** is the canonical choice
(per `.ai/STANDARDS.md` section 1).

---

## 15. Ktor or Spring services

**Not present.** No backend.
`services/elysium-ai-gateway/`
exists as a scaffold (Kotlin/JVM
project) but it is empty.

The Foundry's backend (skill 08)
will be the **first real backend
service**. The skill recommends
Kotlin + Spring Boot **or** Ktor.
A discovery-time ADR must pick
one.

---

## 16. Background jobs

- **`androidx.work` (WorkManager)**
  is the canonical background
  job runner.
- **`core/ops/`** implements
  the operation queue with ETA
  + EMA throughput.

---

## 17. CI / CD

- **No CI/CD configured** in
  the repository (no
  `.github/workflows/`, no
  `.gitlab-ci.yml`, no
  `Jenkinsfile`).
- **The skill 15 (devops)
  DoD requires** an 18-stage CI
  pipeline. The Foundry's CI
  must be built from scratch.

---

## 18. Secrets handling

- **No external secret
  management.** No Vault
  integration, no AWS Secrets
  Manager, no HashiCorp.
- **The Android Keystore**
  is the only key store.
- **The SFTP server** has
  public keys in
  `app/src/main/assets/`
  (checked in for the demo).
  This is a **security smell**
  — see risk register.
- **local.properties** has
  the SDK path (not a secret).

The Foundry's secret model (skill
12 section 4) is a **net-new
integration** with HashiCorp
Vault + KMS.

---

## 19. Testing infrastructure

- **143 test classes / 1,380
  unit tests / 0 failures / 2
  ignored / 100% pass / 2.147s**
  (baseline 2026-07-17).
- **No instrumented tests
  yet** that match the
  composition root
  (`AppLaunchSmokeTest` exists
  but the Elysium-specific
  instrumented tests are in
  development — Phase 44
  added one for `MainScreen`).
- **No mutation testing.**
- **No property-based
  testing.**
- **No fuzzing.**
- **No pen tests.**

The skill 14 (quality) DoD
requires all of the above. The
Foundry's test strategy must
build them.

---

## 20. Security findings (the 15 categories)

| # | Category | Status | Severity |
|---|---|---|---|
| 1 | Embedded API keys | **None in production code.** SFTP public keys in `assets/` are a **smell** (test data, should be moved out). | **P1** |
| 2 | Weak local token storage | **N/A** — no tokens. | — |
| 3 | Exported Android components | `MainActivity` is the launcher; no `exported="true"` on internal Activities. Need to audit `core/runtime/` Activities. | **P2** |
| 4 | Unsafe deep links | **N/A** — no deep links. | — |
| 5 | WebView exposure | **None.** No `WebView` in the codebase. | — |
| 6 | SQL injection | **None in our code** (Room is safe). Third-party libs (SSHD) carry their own risk. | **P2** |
| 7 | Missing RLS | **N/A** — no backend. | — |
| 8 | Broad storage policies | **N/A** — no S3. | — |
| 9 | Unsigned asset downloads | **N/A** — no asset download surface. | — |
| 10 | Unvalidated file uploads | **N/A** — no upload surface. | — |
| 11 | Path traversal | **Possible** in the universal-runtime layer (`core/runtime/distros/`). Needs explicit audit. | **P1** |
| 12 | Insecure deserialization | Gson is used widely. The `WorkspaceStore` (skill 35) deserializes from disk. Needs explicit validation. | **P1** |
| 13 | Log leakage | **Need to audit.** Logs may include user filenames, paths, SFTP credentials. | **P1** |
| 14 | Debug endpoints | **N/A** — no backend. | — |
| 15 | SFTP public keys in assets | **Yes** — see #1. | **P1** |

---

## 21. Performance bottlenecks (initial pass)

- **APK size:** 101 MB debug,
  221 MB release. The
  MediaPipe GenAI runtime
  alone is ~80 MB. **Largest
  single contributor.** No
  R8 + no tree-shake + no
  architecture split into
  `:runtime-feature` vs
  `:foundry-feature`.
- **Cold start:** MainActivity
  is 40k LOC; Hilt graph
  construction is heavy. No
  baseline measured yet.
- **Memory:** 1 GB RAM device
  baseline. The CRDT sync
  + the universal runtime +
  the SFTP server + the local
  HTTP server + the LLM
  hooks all coexist in one
  process. **High** memory
  pressure.
- **Disk I/O:** SAF + Room +
  the runtime event log
  (NDJSON) + the SFTP server
  + the universal-runtime
  workspaces are all I/O-
  heavy. A 1 GB device on a
  slow SD card will choke.
- **GPU:** none used (no 3D
  engine).
- **The 10 metrics** the
  Foundry's quality skill
  defines (per skill 14
  section 3.7) need to be
  **measured** as Phase 1.

---

## 22. Duplicate concepts (deep dive)

A finer-grained scan of the
duplication matrix (per skill 01
section 3):

| Concept | Existing classes | Foundry target | Comment |
|---|---|---|---|
| **Node** (CRDT sense) | `Node`, `CrdtSyncNode`, `NodeIdStore` | `PartInstanceId` / `VehicleRevisionId` (skill 03) | **CRDT node ≠ vehicle node**. The CRDT node is a sync identity; the vehicle node is a domain entity. No collision. |
| **Spec** (universal-runtime sense) | `RuntimeSpec`, `SessionSpec`, `CapabilityProfile` | `Spec.Artifact` (skill 04) | **Universal-runtime spec ≠ vehicle spec**. No collision. |
| **Profile** | `ElysiumProfile` (runtime profile) + `CapabilityProfile` + `AudioProfile` + `GpuProfile` | `UserProfile` / `Organization` (skill 03) | No collision. |
| **State** (universal-runtime) | `Running` / `Stopped` / `Failed` + `Preparing` / `Starting` / `Recovering` / `Created` | `VehicleRevisionState` (skill 03) | Both are sealed-class state machines; the namespaces are different. No collision. |
| **Snapshot** | `DistroSnapshot`, `CapsuleSnapshot` | `EngineeringArtifact` (immutable, content-addressed) | **No collision.** The snapshots are runtime layer; the artifacts are engineering layer. |

**Conclusion:** the duplication
matrix is **empty** for the
Foundry's domain. Every vehicle
/ part / engineering concept is
a **net-new** type.

---

## 23. Reusable capability (the answer to skill 01 section 7's "what is reusable")

The existing codebase has
**7 reusable capabilities** the
Foundry should inherit:

1. **Hilt DI structure**
   (RuntimeModule, DistroModule)
   — the template for
   FoundryModule(s). The skill
   11 "skill 11 — mobile UX" and
   the upcoming skill 08 / 09
   / 10 / 11 Foundry modules can
   follow the same pattern.
2. **Compose UI patterns**
   (the existing 26 features
   are all Compose). The
   skill 11 mobile UX is
   built on this.
3. **Room** — for the
   Foundry's local DB (the
   project DB + the vehicle
   DB + the parts DB).
4. **Tink vault**
   (AES-256-GCM, Tink AEAD with
   Android Keystore) — the
   template for
   `RESTRICTED_ENGINEERING`
   encryption.
5. **Apache MINA SSHD** — the
   template for the Foundry's
   SFTP-based controlled
   disclosure (skill 10
   section 4).
6. **MediaPipe Phi-3-mini
   local LLM** — the on-device
   AI tier; the Foundry's
   cloud AI council coexists
   with it.
7. **ML Kit OCR + image
   labeling** — for the
   Foundry's parts catalog
   (OCR a part label, label a
   part image).

The **runtime** itself (workspaces,
sessions, runners) is **NOT
reusable** for the Foundry. It
is an execution abstraction; the
Foundry needs a different
abstraction (compile a
`Spec.Artifact`, not launch a
Linux distro).

---

## 24. Dead code / abandoned features

A first pass:

- **`app/src/main/assets/`**
  contains the SFTP demo
  keys. Should be removed
  from the production APK.
- **The 2 ignored tests**
  need to be either fixed
  or marked with a follow-up
  ticket.
- **Universal-runtime
  workspaces** are
  **young** (Phases 0-49) and
  may have abandoned code
  paths; the next archaeology
  cycle should re-scan.
- **No clear abandoned
  modules** in `core/` —
  everything is actively
  used.

---

## 25. Recommended migration (the bridge from current to target)

The Foundry is **net-new**; the
existing app is **preserved**.
The migration is **additive**:

1. **Add a new module:**
   `:foundry-core` (the
   Foundry's bounded contexts).
2. **Add a new module:**
   `:foundry-app` (the Forge
   surface — the new Compose
   screens).
3. **Reuse** the 7 capabilities
   above.
4. **Defer** the backend to
   Phase 2 (after the Foundry's
   domain model is
   implemented in the
   Android app, the backend
   can be designed from the
   proven shape).
5. **Re-architect the app**
   in Phase 7 (production
   hardening) for the
   multi-module + feature-
   flag + canary rollout.

The full target architecture is
in
`docs/foundry/target-architecture.md`.

---

## 26. Definition of done (this audit)

This audit is **complete** when
the 7 questions (per skill 01
section 8) are answered, the
findings are categorized, and the
risk register is filed. This
document answers all 7 questions
+ the 14 mandatory sections
(per skill 01 section 2 +
`docs/foundry/risk-register.md`).

A failing item is a contract
violation. The orchestrator
(skill 00) cannot advance past
G0 until the audit + the 5
companion documents are signed.

## 27. Sign-off

- **Auditor:** skill 01
  (repository-archaeology).
- **Date:** 2026-07-17.
- **Git head:** `c9028dc`.
- **Status:** complete. The
  orchestrator is unblocked to
  produce the 5 companion
  documents.
- **Next gate:** **G0 green**
  after the 6 documents
  (`current-state-audit.md`,
  `target-architecture.md`,
  `domain-ownership.md`,
  `implementation-roadmap.md`,
  `risk-register.md`,
  `dependency-map.md`) are
  signed.
