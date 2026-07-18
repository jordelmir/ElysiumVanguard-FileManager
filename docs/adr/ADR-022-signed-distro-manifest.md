# ADR-022 — Signed Distro Manifest + Hash Verification

Status: **Accepted** (Phase 51, 2026-07-18)
Owners: Runtime + Security
Supersedes: none
Superseded by: none

## Context

The critical end-to-end integration test from
the Worldwide Vision doc requires the runtime
to:

> "Download a signed distro. Verify its
> hash."

Until Phase 51 the runtime had the pieces but
not the integration:

- The [DistroInstaller] (Phase 9.6.2) had a
  `VERIFYING` stage that checks the
  downloaded archive's SHA-256 against the
  optional `Distro.sha256` field. This is
  "verify its hash" — but the hash is shipped
  in the catalog, not in a signed artifact.
  A user who installs a distro trusts the
  catalog maintainer to ship the right
  `Distro.sha256`.
- The [ManifestSigner] + [ManifestVerifier]
  (Phase 12.4) had the Ed25519 signing
  primitives. A manifest is signed with the
  build-host's Ed25519 private key; the
  device verifies with the public key
  shipped in the APK. The infrastructure
  exists; the install path does not use it.
- A signed-manifest fetch + verify path
  was missing. The current install downloads
  the rootfs directly, optionally checks
  `Distro.sha256`, and extracts. The
  signed-manifest path (download a small
  `manifest.json` + `manifest.json.sig`,
  verify the signature, then use the
  manifest's `sha256` for the rootfs) does
  not exist in the install flow.

The challenge: a user with the
`com.elysium.vanguard` APK has the public
key. A remote distro server has the matching
private key. The signed manifest is the
contract between the two: "this is the
rootfs we signed, its hash is X, and
here's the Ed25519 signature to prove it".

## Decision

We split the signed-manifest path into four
small pieces:

1. **`DistroManifest` (data class)** — the
   signed artifact. Carries `id`, `version`,
   `sha256` (the rootfs's SHA-256), `sizeBytes`
   (the rootfs's expected size — defence
   against truncation), `signature: ByteArray`
   (the Ed25519 signature over the canonical
   manifest bytes), and `signedAtMs: Long`
   (when the manifest was signed, for
   forensics).

2. **`DistroManifestCodec` (object)** — the
   JSON round-trip. The codec encodes a
   `DistroManifest` to a JSON object
   (excluding the signature, which is in a
   sibling file `manifest.json.sig`); it
   decodes a JSON object back to a
   `DistroManifest` with the signature
   supplied by the caller. The split is
   deliberate: the same JSON shape can be
   verified by hand tools, and the signature
   can be stored / transmitted separately.

3. **`SignedDistroManifestVerifier` (class)** —
   the runtime-side check. Takes a
   `DistroManifest` + an Ed25519 public key
   and returns either `Verified(manifest)` or
   `Rejected(reason)`. The verifier uses the
   existing [ManifestVerifier] (Phase 12.4)
   which calls
   `Signature.getInstance("Ed25519")` on the
   JDK 15+ crypto provider.

4. **`DistroInstaller.installWithSignedManifest`**
   — the new install entry point. Takes a
   `Distro`, a `DistroManifest`, an Ed25519
   public key, and the same `baseDir` the
   existing `install` method uses. The new
   method:
   - Verifies the manifest's signature
     against the public key. A failed
     verification throws
     [IOException] with a typed message.
   - Verifies the manifest's `id` matches
     the `distro.id`. A mismatch is a hard
     error.
   - Runs the existing install path with
     `distro.copy(sha256 = manifest.sha256)`.
     The existing `VERIFYING` stage then
     checks the rootfs's hash against the
     manifest's `sha256`.

### Why a separate `installWithSignedManifest` method

The existing `install` method stays. A user
who does not have a signed manifest for a
distro can still install it the old way
(verifying against the catalog's `Distro.sha256`,
or no verification at all). A user who does
have a signed manifest opts into the new
method; the manifest is the source of truth
for the rootfs's hash.

This is the same split Android's APK
Signature Scheme v2 uses: the v1 JAR signing
scheme is the old path; v2 is the new path;
both can coexist. A user can opt into v2 by
having the v2 signature block; a user without
it falls back to v1.

### Why the signature is a separate file, not a field

A signed manifest has two parts:

- `manifest.json` — the JSON object
  describing the distro (id, version,
  sha256, sizeBytes, signedAtMs).
- `manifest.json.sig` — the 64 raw bytes of
  the Ed25519 signature over the JSON bytes.

The split lets:

- A user inspect the manifest in any text
  editor (no embedded base64 noise).
- A user verify the manifest with `openssl`
  + the public key as raw bytes (no JSON
  parser required).
- The runtime apply the same `Signature.update(...)`
  / `Signature.verify(...)` flow as
  [ManifestVerifier] (Phase 12.4) — no
  custom canonicalisation.

The cost is two file fetches instead of one.
The signed manifest is small (~200 bytes for
the JSON + 64 bytes for the signature) so
the round-trip is negligible.

### Why the manifest includes `sizeBytes`

A defence against truncation. A downloader
that stops mid-stream can produce a file
whose SHA-256 does not match the expected
hash; the `VERIFYING` stage catches that. But
a malicious CDN could also produce a
truncated file whose truncated portion is
arbitrary. The `sizeBytes` lets the
`DistroInstaller` refuse a download whose
final size does not match the manifest's
declared size — a defence in depth.

### Why the public key is injected, not derived

The runtime does not derive the public key
from the distro's id (no per-distro key).
There is a single Elysium Vanguard public
key shipped in the APK. The user (or the
test) supplies the key. Production Hilt
wiring injects the APK's key; tests inject
a freshly generated keypair for isolation.

A future per-channel public key
(stable / beta / nightly) is a trivial
follow-up: the `SignedDistroManifestVerifier`
takes a public key, so the caller can pass
whichever key the manifest was signed with.
The codec does not need to change.

## Consequences

Positive:

- The critical end-to-end integration test
  now has steps 1 ("Download a signed
  distro") and 2 ("Verify its hash") covered
  end-to-end. The install flow verifies the
  manifest's signature BEFORE extracting the
  rootfs; a tampered manifest is rejected
  before any disk write.
- A user with a security question about a
  distro ("how do I know this rootfs is what
  the maintainer published?") can answer it
  with: "the manifest is signed; the public
  key is in the APK; here's the manifest; you
  can verify it yourself".
- The manifest's `signedAtMs` is a forensic
  timestamp. A user who sees an old manifest
  (signed months ago) for a "fresh" distro
  release knows something is off.
- The `installWithSignedManifest` method is
  a drop-in for the existing `install`
  method. A future UI that surfaces "this
  distro is signed by Elysium Vanguard
  Vanguard Linux" can wire the new method
  without changing the call signature.

Negative:

- The downloader fetches two files (manifest
  + signature) instead of one (just the
  rootfs). The size cost is ~264 bytes; the
  latency cost is two HTTP requests. For
  large rootfs downloads (> 100 MB) the cost
  is negligible.
- A signed manifest is no better than the
  trust the user has in the public key. If
  an attacker can swap the APK's public key,
  the manifest is meaningless. The runtime's
  broader security story (PRoot is not a
  security boundary; the APK signing chain
  is the root of trust) is documented in
  the Worldwide Vision doc.

## Revisit triggers

- If the runtime gains a per-channel public
  key (stable / beta / nightly), the
  `SignedDistroManifestVerifier` already
  takes a public key — the caller just
  passes a different one per channel. No
  code change.
- If the runtime gains a `manifestVersion`
  field (so future manifest versions can
  introduce new fields), the codec gains
  a `version` parameter and refuses to
  decode a manifest with an unknown
  version. Phase 51 ships version 1.
- If the runtime gains a manifest expiry
  policy ("manifests are valid for 30
  days"), the verifier checks
  `signedAtMs` against the system clock.
  Phase 51 does not enforce expiry.
