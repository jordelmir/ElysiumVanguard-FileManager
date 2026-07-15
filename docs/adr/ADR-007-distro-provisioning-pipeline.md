# ADR-007 — Distro Provisioning Pipeline

Status: **Accepted** (Phase 16, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

Master order §11 says a guest rootfs goes through four steps to
be ready:

1. **Install** — extract the upstream tarball into a rootfs dir.
2. **Overlay** — write the Elysium identity (`os-release.d/elysium.conf`
   + `/etc/elysium/{VERSION,BASE_DISTRO,CHANNEL}`).
3. **Profile** — install the upstream packages and apply the
   Elysium SystemLayer for the chosen profile (LITE / BALANCED /
   DESKTOP / HEADLESS).
4. **Sign** — emit a manifest and sign it with Ed25519 so the
   device can re-verify on every boot.

Until Phase 16, each step lived in a separate class:

- `DistroInstaller` (download + extract).
- `ElysiumOsReleaseOverlay` (write identity files).
- `ProfileInstaller.plan` + `SystemLayerApplier` (profile + layer).
- `ManifestSigner` / `ManifestVerifier` (sign + verify).

The classes were individually testable, but the *wiring* was a
`try` block in the installer. A refactor that touched one piece
silently broke the others (e.g. the manifest's `tarball` field
stopped matching the file the layer applier had just produced).
The wiring had no test.

## Decision

A single class — `DistroProvisioningPipeline` — orchestrates the
four steps. It is the **last mile** of provisioning: the
`DistroInstaller` calls it after extracting the rootfs; the
pipeline returns a signed manifest the runtime writes to disk.

The pipeline is pure JVM-testable. It does not download the
rootfs and does not execute `apt-get` — those are the installer's
chroot work. The pipeline only does the file operations that
*can* be tested end-to-end without a real distro:

1. Apply the Elysium os-release overlay.
2. Plan the profile (compute install command + layer metadata).
3. If a layer tarball is given, mint a `SystemLayer` (id, name,
   version from the profile), apply it via `SystemLayerApplier`
   (which SHA-256 verifies + extracts), and copy the tarball
   next to where the manifest will live.
4. Build a `SystemLayerManifest` (1 layer = the profile layer,
   or a synthetic "identity" layer that records the os-release
   when no layer was supplied — the manifest schema requires at
   least one layer).
5. Write `manifest.json`, sign it (Ed25519), write
   `manifest.json.sig` (64 raw bytes).
6. **Re-verify the signature on the just-written file.** A
   misbehaving signer must not pass — the pipeline refuses to
   report success without a verifiable signature.

The pipeline takes a `ProvisioningLogger` (default: no-op) so
production can wire its structured-log bridge and the test suite
can assert on the call sequence.

### Why the pipeline signs-then-verifies

The `ManifestSigner` and `ManifestVerifier` are the same crypto
primitives (Ed25519 in the JDK 17), but a bug in the byte
hand-off between them is a real risk. The re-verify step closes
the loop: the pipeline never returns success without a fresh
`ManifestVerifier.verify()` call. The cost is one signature
operation (microseconds) per install; the value is a hard
guarantee that the device will re-verify on every boot.

### Why a synthetic identity layer

The manifest schema requires `layers.isNotEmpty()`. When the
caller did not supply a layer tarball (e.g. a HEADLESS install
with no packages and no layer), the pipeline emits a synthetic
layer whose "tarball" is the os-release file itself. The
synthetic layer's SHA-256 is the real hash of the os-release
file — the device can re-verify it just like any other layer.
This is a deliberate quirk: a missing-layer install still has
*something* to sign, so the manifest schema stays uniform.

### Why a hand-rolled `toJson` for the manifest

The runtime ships with the Android `org.json` library, which is
a *stub* on the JVM unit-test classpath (under
`isReturnDefaultValues = true`). Calls to `JSONObject.toString(int)`
on the stub return `null` (default), which surfaces as a
`NullPointerException: "toString(...) must not be null"` deep in
the test pipeline. The hand-rolled `toJson` (in the same
file, as a `SystemLayerManifest.toJson()` extension) is
~30 lines of `StringBuilder` that the official
`SystemLayerManifest.load` parses back without round-trip
issues. The dependency-free serializer stays out of the
test-runtime's classpath hole.

We declared `org.json:json:20231013` as a `testImplementation` so
the test *parse* path uses the real implementation; the runtime
*write* path uses the hand-rolled serializer.

### Why the in-memory `SystemLayer.tarball` is the absolute path

`SystemLayer`'s init block requires `tarball.isFile`. The
pipeline constructs the `SystemLayer` with the real tarball
(either the caller's `layerTarball` or the os-release file for
the synthetic layer) so the in-memory model is always backed by
a real file. The JSON's `"tarball"` field is the *basename*;
the official `SystemLayerManifest.load` resolves the basename
against the manifest's parent directory at load time. This
split — real file in memory, basename on disk — is what makes
the manifest portable (it doesn't embed absolute paths).

## Consequences

### Positive

- **One place to read for "how is a rootfs provisioned".** The
  pipeline is 100 lines of method body and a 30-line hand-rolled
  serializer. The whole flow is reviewable in one file.
- **Re-verify is a hard guarantee.** A misbehaving signer
  cannot pass the pipeline without the device re-deriving the
  same answer.
- **Pure JVM-testable end-to-end.** The 8 tests in
  `DistroProvisioningPipelineTest` build a real rootfs dir, a
  real layer tarball, a real Ed25519 keypair, and assert on
  the manifest's contents, the signature file, and the
  re-verification. No mocks, no Android stubs.
- **Manifest round-trip.** The pipeline writes a manifest that
  `SystemLayerManifest.load` reads back without modification —
  the synthetic layer, the channel, the SHA-256s all match.
- **Structured-log seam.** Production wires its logger; the
  test asserts on the call sequence.

### Negative

- **Hand-rolled JSON.** ~30 lines we'd otherwise inherit from
  `org.json`. The trade-off is dependency-free testability vs
  brevity. We accept it because the serializer is the
  simplest possible code and the manifest schema is small.
- **Synthetic identity layer is a quirk.** A user with a
  HEADLESS install gets a manifest whose only layer's tarball
  is `/etc/os-release.d/elysium.conf`. The signed-manifest
  audit trail still works, but the layer is not a "real"
  layer. A future phase can add a `Layer.MARKER_ONLY` flag
  and split the schema; for now the quirk is documented in
  the code.
- **No fallback for "signature algorithm not supported".** The
  pipeline expects Ed25519 in the JDK. A non-Android JDK
  without Ed25519 (e.g. JDK 8) would fail at sign time. The
  `jvmToolchain(17)` in `build.gradle.kts` already enforces
  the build side; the device side is JDK 17 via Android
  desugaring (or the platform's native crypto).

## Alternatives considered

1. **Skip the pipeline; let the installer call the four pieces
   directly.** This is the status quo before Phase 16. The
   wiring was untested and a refactor in any one piece
   silently broke the others. The pipeline formalises the
   wiring and makes it testable.
2. **A sealed class hierarchy of "step" classes, one per
   phase.** Adds polymorphism for one-shot use. The
   orchestrator-as-a-method is simpler; a sealed hierarchy
   would pay off if a third party needed to plug in a custom
   step, which we don't have today.
3. **Sign the entire rootfs tree (rsync-style signature over
   every file).** Way too expensive and out of scope. The
   manifest's per-layer SHA-256 + the Ed25519 signature over
   the manifest is the agreed contract (master order §11.5).

## Revisit triggers

- A new layer type (e.g. a per-app cgroup profile) needs to
  be in the manifest. We add a `SystemLayer.kind` field and
  the synthetic-layer trick stops being needed.
- A second signing algorithm (RSA) becomes a requirement. We
  add a `ManifestSigner.Algorithm` enum and the pipeline
  takes the algorithm as a parameter.
- The Android `org.json` stub is no longer the unit-test
  classpath default (e.g. AGP changes the `unitTests` config).
  We can drop the hand-rolled `toJson` and re-use
  `JSONObject.toString(2)`.
