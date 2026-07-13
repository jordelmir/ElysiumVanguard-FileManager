# ADR-004: Rootfs verification chain

- Status: Accepted
- Date: 2026-07-13
- Owners: Elysium Vanguard runtime
- Depends on: ADR-003

## Context

ADR-003 establishes that every distro installation generates a signed manifest.
This ADR defines how that manifest is verified at session start, what happens
on verification failure and how the user is informed.

A rootfs may be tampered with by:
- Physical access to the device storage while the app is not running.
- A malicious app exploiting shared storage or root on a compromised device.
- Corruption during write-back, migration or incremental update.

## Decision

Implement a three-layer verification chain evaluated before any guest process
executes inside a distro rootfs:

### Layer 1 — Manifest existence

The session manager checks that all three identity files exist:
`/etc/os-release`, `/etc/elysium-manifest.json`,
`/etc/elysium-manifest.signed.json`. If any file is missing, the distro is
rejected with `IntegrityError.MissingIdentity`.

### Layer 2 — Signature verification

Re-compute the HMAC-SHA256 over the canonical JSON manifest bytes using the
per-distro key derived as described in ADR-003. Compare against the stored
hex-encoded HMAC. A mismatch produces `IntegrityError.SignatureMismatch` and
the distro is disabled with a tampered status.

### Layer 3 — File integrity

Sample-hash a configurable percentage of files listed in the manifest (default
100% for the first session after install, 10% for subsequent sessions). If any
sampled file does not match its SHA-256 in the manifest, the distro is
rejected with `IntegrityError.FileTampered`. The failed file path and expected
vs actual hash are recorded in the session diagnostics.

### Failure response

| Error | UI action | Recovery |
|---|---|---|
| MissingIdentity | Show "Installation incomplete" with reinstall option | Reinstall distro |
| SignatureMismatch | Show "Rootfs tampered" with factory reset option | Wipe and reinstall |
| FileTampered | Show "Files modified" with verify-all and repair options | Re-download modified files or reinstall |

All failures are logged to the session diagnostic buffer and emitted as typed
events.

## Invariants

1. Verification runs before any execve into the distro.
2. A verified-boot session caches the result for the process lifetime.
3. Verification failure leaves the rootfs unmounted and unexecuted.
4. Sampling rate is user-configurable (0–100%) in Settings.
5. A full re-verify can be triggered manually from the distro detail screen.

## Alternatives considered

### Verify only on install

Rejected. Integrity is a runtime concern, not a build-time concern. An install
that was valid can become invalid before the first session starts.

### Full hash on every session

Rejected for large rootfs images. Sampling provides a practical trade-off
between security and startup latency. The default 100% first-session hash gives
a baseline, and subsequent 10% sampling detects drift over time.

## Consequences

- Session startup latency increases by the sampling time.
- Diagnostics provide actionable information for recovery.
- The verification chain is testable without an actual rootfs (using fake files).
- Future remote attestation can extend the chain to a trusted server.

## Revisit triggers

- Startup latency with 10% sampling exceeds 5 seconds on reference hardware.
- A file-system-level integrity mechanism (dm-verity, fs-verity) becomes
  available on the target API level.
