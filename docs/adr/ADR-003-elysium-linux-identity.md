# ADR-003: Elysium Linux identity and rootfs derivation

- Status: Accepted
- Date: 2026-07-13
- Owners: Elysium Vanguard runtime
- Depends on: ADR-001

## Context

Running arbitrary Linux distributions inside the app requires knowing what was
installed, where it came from, which packages were included and whether the
rootfs has been modified since installation. Without this identity, the app
cannot verify integrity, detect tampering, report the runtime environment to
guest applications or support incremental updates.

The DistroInstaller currently fetches rootfs tarballs and extracts them without
generating any identity artifact. A `RuntimeBackend` has no reliable way to
discover which distro variant, version or package set is mounted.

## Decision

Generate an ElysiumLinuxIdentity after every successful distro installation.
The identity is a set of three files written to `/etc/` inside the rootfs:

- `/etc/os-release` — standard freedesktop.org os-release(5) format identifying
  the distro as `Elysium Vanguard Linux` with `VARIANT_ID`, `BUILD_ID` and
  `ELYTIUM_*` fields for provenance tracking.
- `/etc/elysium-manifest.json` — a machine-readable SBOM listing every file
  path, file size, modification time, SHA-256 hash, package origin and install
  timestamp. The manifest is the single source of truth for rootfs contents.
- `/etc/elysium-manifest.signed.json` — HMAC-SHA256 signature over the
  canonical JSON-serialized manifest bytes, using a per-release key derived from
  a device-local secret. The signature proves the manifest has not been tampered
  with since installation.

### Identity fields

| Field | Source | Example |
|---|---|---|
| `ID` | Constant | `elysium-linux` |
| `VERSION_ID` | Rootfs metadata | `24.04` |
| `VARIANT_ID` | DistroCatalog variant | `ubuntu-minimal` |
| `BUILD_ID` | Install timestamp + nonce | `20260713T061200Z-a1b2c3` |
| `ELYTIUM_MANIFEST_HASH` | SHA-256 of manifest | `abc123...` |
| `ELYTIUM_SIGNATURE_ALG` | Constant | `HMAC-SHA256` |
| `ELYTIUM_INSTALL_SOURCE` | URL or local path | `https://example.com/rootfs.tar.xz` |

### Signature scheme

The signing key is derived from `AndroidGuard.ATTEST_KEY` (a device-local secret
stored in EncryptedSharedPreferences) and the distro UUID via HKDF-SHA256. The
key is never serialized. The signed output is the HMAC value encoded as hex.

## Invariants

1. Every installed rootfs has exactly one identity set.
2. The manifest is generated before any guest process executes inside the rootfs.
3. Verification re-computes the HMAC over the on-disk manifest and rejects if
   mismatched.
4. A failed verification disables the distro and surfaces a tampered status.
5. The identity survives re-installation of the same distro variant (BUILD_ID
   changes).

## Alternatives considered

### Rely on os-release from the tarball

Rejected. Downstream os-release may be missing, generic or stale. The app needs
its own identity to track provenance and integrity regardless of upstream.

### Use GPG signatures

Rejected. GPG introduces key management, expiry and verification complexity on
Android with no integrity benefit over HMAC for the device-local use case.

## Consequences

- Every install takes ~O(n) additional time to hash each file. This is
  unavoidable for integrity.
- The identity must be regenerated on rootfs repair or migration.
- Future delta updates can cross-reference the manifest to compute diffs.
- ADR-004 defines the full verification and tamper-response workflow.

## Revisit triggers

- HKDF derivation uses a dependency not available on older API levels.
- Performance of file hashing blocks the install for large rootfs images.
- A future verifiable boot or remote attestation replaces device-local HMAC.
