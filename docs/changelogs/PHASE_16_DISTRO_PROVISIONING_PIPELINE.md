# Phase 16 — Distro Provisioning Pipeline

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1102 tests, 0 failures, 2 skipped.

## What landed

The four provisioning steps (overlay, profile, layer, sign) are
now orchestrated by a single testable class:
`DistroProvisioningPipeline`. Until Phase 16 the steps lived in
four separate classes wired by hand inside the installer; the
wiring was untested and a refactor in any one piece could
silently break the others. The pipeline formalises the wiring
and makes it JVM-testable end-to-end.

### Files

**Production (1 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/pipeline/DistroProvisioningPipeline.kt`
  — the orchestrator. Takes a real rootfs dir, a real layer
  tarball (optional), and runs:
    1. `ElysiumOsReleaseOverlay.apply(rootfsDir)`
    2. `ProfileInstaller.plan(profile, family)`
    3. `SystemLayerApplier.apply(layer, rootfsDir)` (if a layer
       was supplied)
    4. Write `manifest.json` (hand-rolled JSON serializer).
    5. Sign with `ManifestSigner` (Ed25519, JDK 17).
    6. **Re-verify the signature** with `ManifestVerifier`. A
       misbehaving signer cannot reach the caller unnoticed.
  Returns a `DistroProvisioningResult` carrying the manifest,
  the signature, the per-step artifacts, and the timestamps.

**Production (1 small change):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/layer/ManifestSigner.kt`
  — added `ManifestSigner.sha256Hex(file)` as a public helper
  (the pipeline and `SystemLayerApplier` both need it; the
  applier has its own private copy that the pipeline now
  reuses through the public helper).

**Test deps (1 line):**

- `app/build.gradle.kts` — declared
  `testImplementation("org.json:json:20231013")` so the JVM
  test classpath has the real `org.json` implementation (not
  the Android stub that returns `null` from `toString(int)`).

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/distros/pipeline/DistroProvisioningPipelineTest.kt`
  — 8 tests covering the happy path, the no-layer synthetic
  identity layer, the logger call sequence, the channel
  propagation, the re-verify failure path, input validation,
  and the official-loader round-trip.

**ADR (1 new):**

- `docs/adr/ADR-007-distro-provisioning-pipeline.md` — context,
  decision, consequences, alternatives, revisit triggers.

### Why this matters

Before Phase 16, "is this rootfs provisioned correctly?" had no
single answer. The four pieces worked individually but their
*wiring* was an untested `try` block. Phase 16 closes that gap
by making the wiring a class with a test suite. The
`DistroProvisioningPipeline` is also the seam the installer's
chroot work calls into; replacing the hand-wired `try` block
with a single `pipeline.provision(...)` call is the
installer's next refactor (a separate phase).

The re-verify step is the most important new property. A
misbehaving `ManifestSigner` (or a byte-handoff bug between
sign and verify) can no longer pass the pipeline without the
device re-deriving the same answer. The cost is one signature
operation per install; the value is a hard guarantee that
the device will re-verify on every boot.

### What the pipeline does

For a real rootfs dir + an optional layer tarball, the
pipeline:

| Step | Output |
|---|---|
| 1. Overlay | `/etc/os-release.d/elysium.conf` + `/etc/elysium/{VERSION,BASE_DISTRO,CHANNEL}` |
| 2. Plan | `ProfileInstaller.Plan` (install command + layer metadata) |
| 3. Apply (optional) | `elysium-layer-<id>/` extracted in the rootfs; tarball copied next to manifest |
| 4. Manifest | `manifest.json` written with the layers |
| 5. Sign | `manifest.json.sig` (64 raw Ed25519 bytes) |
| 6. Re-verify | A second `ManifestVerifier.verify()` call; failure throws `IOException` |

The synthetic "identity" layer (when no layer tarball was
supplied) carries the os-release file as its tarball and its
SHA-256 in the manifest is the real hash of the file. The
device can re-verify the layer just like any other.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `DistroProvisioningPipelineTest` | 8 | 0 |
| **Project total** | **1102** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bugs found and fixed during this phase

1. **Android `org.json` stub returns null `toString(int)`.** The
   first cut of `toJson` used `org.json.JSONObject.toString(2)`.
   On the unit-test classpath, the Android stub returns `null`
   under `isReturnDefaultValues = true`, which surfaces as a
   NPE deep in the pipeline. Replaced with a 30-line
   hand-rolled `StringBuilder` serializer that has no
   classpath dependencies.
2. **`SystemLayer.copy(tarball = File(basename))` rejected at
   init.** `SystemLayer`'s init requires `tarball.isFile`, so
   re-creating the layer with just the basename (a relative
   path that doesn't resolve) was a hard fail. Removed the
   `copy`; the in-memory `SystemLayer` keeps the absolute
   path of the shipped tarball, and the JSON's `"tarball"`
   field is the basename that the official
   `SystemLayerManifest.load` resolves at parse time.
3. **Logger test expected `verify-manifest` step call.** The
   pipeline only logged `done("verify-manifest", ...)` for
   the re-verify step, not `step("verify-manifest")`. Added
   the step call so the call sequence is symmetric.

## Next phase

**Phase 17** — wire `DistroManager.installBlocking` to call the
`DistroProvisioningPipeline.provision()` after the rootfs is
extracted. The installer's chroot work (running `apt-get` /
`apk` / `pacman`) stays in the installer; the pipeline owns
the file work that follows. The re-verify guarantee is the
new invariant the installer now inherits for free.

Then **§18 hardware broker** (master order) — the broker that
mediates USB / Bluetooth / NFC access to the host from a
guest session. The pattern is the same as `NetworkBroker`:
pure-JVM decision engine + policy data class.
