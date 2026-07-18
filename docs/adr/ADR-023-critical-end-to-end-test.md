# ADR-023 — Critical End-to-End Integration Test

Status: **Accepted** (Phase 52, 2026-07-18)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

The master vision doc (PHASE 9_WORLDWIDE_VISION)
ends with a single critical integration test
that "valida el fundamento real de la
plataforma":

> "Crear una prueba end-to-end que, desde
> un Android Snapdragon limpio:
> 1. Descargue una distro firmada.
> 2. Verifique su hash.
> 3. Cree un workspace aislado.
> 4. Ejecute un binario Linux ARM64.
> 5. Monte únicamente la carpeta elegida
>    por el usuario.
> 6. Detenga el proceso.
> 7. Restaure el snapshot.
> 8. Confirme que no hubo escrituras fuera
>    del workspace autorizado."

Until Phase 52 this test did not exist. The
pieces were shipped in Phases 49 (snapshot /
rollback), 50 (mount allowlist), 51 (signed
manifest) — but no single test stitches
them together.

The challenge: the test, as written in the
master vision, requires a real Android
Snapdragon device. A real `proot` binary
must execute a real ARM64 ELF; a real
download must come from a real HTTP server;
a real mount must be visible to a real
process. A pure JVM unit test cannot
exercise the full flow.

## Decision

We split the test into two layers:

1. **`CriticalEndToEndTest` (JVM unit test)** —
   the **logic** test. Every step of the
   master vision's flow is exercised with
   fakes:

   - The downloader is a fake that returns
     a pre-built tar.gz.
   - The manifest is pre-signed with a
     test keypair.
   - The "Linux ARM64 binary" is a JVM
     function that simulates the binary's
     behaviour: it writes to the authorized
     mount path, and it attempts to write to
     a forbidden path. The mount policy
     enforcer validates the proposed mount
     list; the audit log records the
     decisions.
   - The snapshot / rollback is the real
     [FilesystemSnapshotEngine] — no fake.
   - The "no writes outside the authorized
     workspace" assertion checks the
     [MountAuditLog]: the only entries are
     for the authorized path.

   The test asserts every step's invariants
   and the post-conditions. A failure
   anywhere produces a clear test name that
   points at the broken piece.

2. **`androidTest/.../CriticalEndToEndInstrumentedTest`**
   (Phase 53+, not in Phase 52) — the
   **reality** test. Runs on a real Android
   device or emulator. Downloads a real
   signed distro from a real URL, executes a
   real ARM64 binary inside a real proot
   session, and asserts the same post-
   conditions. The instrumented test is
   gated on the JVM test passing; a device
   test only runs after the logic is
   proven.

### Why split into logic + reality

The JVM test is fast, deterministic, and
JVM-runnable. It catches every regression
in the business logic — the orchestration
of install, snapshot, policy, and rollback.
It runs in CI on every commit.

The instrumented test is slow, requires
hardware, and depends on the device. It
catches the "but on a real Android it
behaves differently" regressions — proot
binary quirks, real kernel syscalls, real
mount behaviour. It runs nightly on a
dedicated device pool, not on every commit.

A test that is only instrumented is slow
and rarely run. A test that is only JVM
misses real-world bugs. The split is the
right trade-off.

### What the JVM test simulates vs what it actually runs

The test runs:

- The actual `installWithSignedManifest`
  flow (signed manifest verify + rootfs
  hash verify + extract).
- The actual [FilesystemSnapshotEngine]
  (real `cp -al` hardlink + rollback).
- The actual [MountPolicyEnforcer].
- The actual [FileMountAuditLog].
- The actual [WorkspaceManager] (create
  workspace + add session + enforce policy).

The test fakes:

- The HTTP downloader (a `DistroHttpDownloader`
  lambda that returns pre-built bytes).
- The "Linux ARM64 binary execution" — a
  JVM closure that writes to the
  authorized path and to a forbidden path
  on the host's temp directory. The "process
  lifecycle" is a try/finally.
- The proot launcher — there is no proot
  in a JVM test. The test asserts the
  *invariants* of the integration (the
  policy was enforced, the audit log
  recorded the decisions, the snapshot /
  rollback worked) but does not actually
  shell out to proot.

The test's value is in the invariants, not
in the bits. A real proot test (Phase 53+)
will exercise the bits; the JVM test
exercises the integration.

## Consequences

Positive:

- The master vision's "definition of done"
  is now real. The JVM test fails when the
  orchestration is broken; a passing JVM
  test is the precondition for shipping the
  next phase.
- The test is fast (< 5 s) and runs in CI
  on every commit. A regression in the
  install / snapshot / policy / rollback
  pipeline is caught at PR time, not at
  release time.
- The test exercises every piece the master
  vision names. The audit log assertion
  (step 8) is the one that's hardest to
  fake — a real
  [FileMountAuditLog] is consulted and the
  test asserts the entries match the
  workspace's allowed mount list.

Negative:

- The JVM test does not exercise real proot
  or real syscalls. A regression in the
  proot launcher's flag construction would
  not be caught. Phase 53+ lands the
  instrumented test that closes that gap.
- The test depends on the host's `cp`
  binary for the snapshot engine. On
  stripped-down CI runners without `/bin/cp`,
  the test would fail. The fallback
  (JVM-only `walkFileTree` recursion)
  keeps the test passing but the snapshot
  copy is slower.

## Revisit triggers

- If a future phase adds a real
  syscall-tracing subsystem (the "write
  audit" the master vision calls for), the
  test's step 8 assertion can be tightened
  to require an actual `WriteAttemptedEvent`
  per filesystem syscall, not just an
  `MountAllowedEvent` per mount decision.
- If the test is moved to a CI matrix that
  includes real device hardware, the
  instrumented test becomes the primary and
  the JVM test becomes the smoke test.
- If the test is run on a real Android
  device, the dependency on the host's `cp`
  is replaced by a real Android `cp`
  (`/system/bin/cp`).
