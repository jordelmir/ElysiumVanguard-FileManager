# Phase 51 — Signed Distro Manifest + Hash Verification

Date: 2026-07-18
Status: **Shipped** — `assembleDebug` green, 1594 tests, 0 failures, 2 skipped.

## What landed

The runtime can now install a distro via a
signed manifest. The install path verifies
the manifest's Ed25519 signature against a
public key (shipped in the APK) and then
uses the manifest's declared `sha256` to
verify the downloaded rootfs. This is steps
1 and 2 of the critical end-to-end
integration test from the Worldwide Vision
doc ("Download a signed distro. Verify its
hash.").

The `DistroManifest` is the contract between
the build-pipeline side (which has the
Elysium Vanguard Linux offline signing key)
and the device (which has the public key
shipped in the APK). The manifest carries
`id`, `version`, `sha256` (the rootfs's
SHA-256), `sizeBytes` (defence against
truncation), `signedAtMs` (forensic
timestamp), and `signature` (the 64-byte
Ed25519 signature). The manifest is a JSON
object; the signature is in a sibling file
`manifest.json.sig` as raw bytes (NOT
base64).

The verifier uses the existing
[com.elysium.vanguard.core.runtime.distros.layer.ManifestVerifier]
(Phase 12.4) which calls
`Signature.getInstance("Ed25519")` on the
JDK 15+ crypto provider. No third-party
crypto dependency; the Ed25519 support is
JDK-native.

## Files

**Production (4 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/manifest/DistroManifest.kt` —
  the signed artifact. Carries the parsed
  fields (`id`, `version`, `sha256`,
  `sizeBytes`, `signedAtMs`) + `signature:
  ByteArray` + `bodyBytes: ByteArray` (the
  EXACT JSON bytes the signature is computed
  over, stored verbatim to avoid JSON
  canonicalization drift). The init block
  enforces the field invariants: 64-char
  lowercase hex sha256, 64-byte Ed25519
  signature, positive sizeBytes +
  signedAtMs, non-empty bodyBytes. Custom
  `equals` / `hashCode` use
  `contentEquals` / `contentHashCode` on the
  byte arrays.
- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/manifest/DistroManifestCodec.kt` —
  JSON round-trip. `encodeBody(manifest)`
  produces a canonical JSON object (no
  signature field; the signature is in a
  sibling file). `decode(json, signature)`
  parses the JSON, populates the parsed
  fields, and stores the exact body bytes
  on the manifest. Throws
  `IllegalArgumentException` on a malformed
  body.
- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/manifest/SignedDistroManifestVerifier.kt` —
  the runtime-side check. Takes a manifest +
  an Ed25519 public key and returns
  `Verified(manifest)` or `Rejected(reason)`.
  The verifier uses `manifest.bodyBytes` (not
  a re-serialized form) to avoid JSON
  canonicalization drift. The Ed25519 verify
  call is delegated to the existing
  [ManifestVerifier].
- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/manifest/SignedDistroManifestInstaller.kt` —
  the new install entry point. Top-level
  function `installWithSignedManifest(...)`
  that:
  1. Verifies the manifest's signature
     against the public key. A failure throws
     `IOException` with a typed message; the
     install aborts before any disk write.
  2. Verifies the manifest's `id` matches
     the distro's `id`. A mismatch is a hard
     error.
  3. Delegates to the existing
     [DistroInstaller.install] with
     `distro.copy(sha256 = manifest.sha256)`.
     The existing `VERIFYING` stage then
     checks the rootfs's hash against the
     manifest's declared `sha256`.
- `docs/adr/ADR-022-signed-distro-manifest.md` —
  the architectural decision record. Captures
  the four-piece split (DistroManifest,
  DistroManifestCodec, SignedDistroManifestVerifier,
  install entry point), the rationale for the
  signature being a separate file, the
  rationale for `bodyBytes` over a
  re-serialized form, and the revisit
  triggers (per-channel public key,
  manifestVersion, manifest expiry).

**Tests (3 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/distros/manifest/DistroManifestTest.kt` —
  18 tests covering: DistroManifest init-block
  invariants (blank id / version / sha256
  rejected, sha256 must be 64 lowercase hex
  chars, non-positive sizeBytes /
  signedAtMs rejected, signature must be 64
  bytes, bodyBytes must be non-empty);
  equals / hashCode use content equality on
  the byte arrays; codec round-trip preserves
  every field including the signature bytes;
  encoded body is JSON without the signature
  field; decode rejects missing id / sha256,
  non-positive sizeBytes / signedAtMs.
- `app/src/test/java/com/elysium/vanguard/core/runtime/distros/manifest/SignedDistroManifestVerifierTest.kt` —
  5 tests covering: verifier returns
  `Verified` for a correctly-signed manifest;
  verifier returns `Rejected` when the body
  is tampered after signing; verifier returns
  `Rejected` when the public key does not
  match the signer; verifier returns
  `Rejected` when the signature is all
  zeros; the `Verified` result carries the
  original manifest.
- `app/src/test/java/com/elysium/vanguard/core/runtime/distros/manifest/SignedDistroManifestInstallerTest.kt` —
  5 integration tests covering: the install
  succeeds when the signature is valid and
  the hash matches; the install fails when
  the manifest body is tampered (the
  signature was computed over a different
  body); the install fails when the public
  key does not match the signer; the install
  fails when the manifest id does not match
  the distro id; the install fails when the
  rootfs hash does not match the manifest
  hash. The integration test builds a
  minimal valid rootfs (tar.gz with
  `/etc/os-release` + `/bin/sh`), computes
  its real SHA-256, signs a manifest, and
  uses a fake `DistroHttpDownloader` to
  serve the rootfs bytes.

## Why this matters

The critical end-to-end integration test from
the Worldwide Vision doc requires:

> "Download a signed distro. Verify its
> hash."

Until Phase 51 the runtime had the pieces but
not the integration:

- The [DistroInstaller] (Phase 9.6.2) had
  hash verification against an optional
  `Distro.sha256` field. But the hash was
  shipped in the catalog, not in a signed
  artifact. A user trusted the catalog
  maintainer to ship the right hash.
- The [ManifestSigner] + [ManifestVerifier]
  (Phase 12.4) had the Ed25519 primitives.
  The infrastructure existed; the install
  path didn't use it.

Phase 51 closes the gap. The install path
verifies the manifest's signature BEFORE
extracting the rootfs; a tampered manifest
is rejected before any disk write. The
manifest's declared `sha256` becomes the
source of truth for the rootfs hash check.

A user with a "how do I know this rootfs is
what the maintainer published?" question
can answer it with: "the manifest is signed;
the public key is in the APK; here's the
manifest; you can verify it yourself."

## What the test suite caught

- **JSON canonicalization drift.** The
  original verifier called
  `DistroManifestCodec.encodeBody(manifest)`
  to re-serialize the manifest before
  verification. The result was different
  bytes from what was signed (key order,
  whitespace). The test
  `verifier returns Verified for a
  correctly-signed manifest` caught the
  drift on the first run. Fixed by adding
  `bodyBytes: ByteArray` to the manifest
  and having the verifier use the EXACT
  bytes the signature was computed over.
- **Tampered test helper.** The test helper
  originally signed the tampered body but
  decoded the original body, producing a
  manifest whose signature matched the
  decoded body (a "correct" verification).
  The split into `signedManifest` (signs +
  decodes the same body) and
  `tamperedManifest` (signs one body, decodes
  a different body) caught the bug on the
  first run.
- **ByteArray `equals` semantics.** Kotlin's
  default `data class` equality uses
  `Any?.equals` which is reference equality
  for `ByteArray`. Two manifests with the
  same parsed fields and the same signature
  bytes but constructed at different times
  would not be `equals`. The test
  `DistroManifest equals uses content
  equality on the signature bytes` caught
  this; fixed by overriding `equals` /
  `hashCode` to use `contentEquals` /
  `contentHashCode`.

## Architectural invariants (Phase 51)

- **`bodyBytes` is the source of truth.**
  The verifier uses `manifest.bodyBytes`,
  not a re-serialization. JSON
  canonicalization is fragile; the
  exact-bytes approach is robust.
- **Signature in a sibling file, not in the
  JSON.** The split keeps the manifest
  human-readable; the signature is the raw
  Ed25519 output the runtime feeds to
  `Signature.verify`.
- **The existing `install` path is
  untouched.** `installWithSignedManifest`
  is a top-level function that wraps the
  existing path. A user without a signed
  manifest can still use `install`; a user
  with one opts into the new path.
- **Public key is injected, not derived.**
  The runtime does not derive the public key
  from the distro's id. A single Elysium
  Vanguard public key ships in the APK; the
  caller passes it. A future per-channel
  public key (stable / beta / nightly) is a
  trivial follow-up.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `DistroManifestTest` | 18 (new) | 0 |
| `SignedDistroManifestVerifierTest` | 5 (new) | 0 |
| `SignedDistroManifestInstallerTest` | 5 (new) | 0 |
| **Project total** | **1594** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 51 is **Phase 52 —
the critical end-to-end integration test**:
A single test that, from a clean Android
state, downloads a signed distro, verifies
its hash, creates an isolated workspace,
executes a Linux ARM64 binary, mounts only
the user-selected folder, stops the
process, restores a snapshot, and confirms
no writes happened outside the authorized
workspace. The test stitches together the
work of Phases 49 (snapshot / rollback),
50 (mount allowlist + audit), and 51
(signed manifest + hash).

Phase 52 is the "definition of done" the
master vision asked for.
