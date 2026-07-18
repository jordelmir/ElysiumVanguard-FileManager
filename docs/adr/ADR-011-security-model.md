# ADR-011: Security model and privilege separation

- Status: Draft
- Date: 2026-07-13
- Owners: Elysium Vanguard security

## Context

The app runs arbitrary guest code (Linux binaries, Windows executables,
full VMs) on the user's device. A compromised guest could:
- Access the app's private data, Keystore or preferences.
- Escape the PRoot or VM boundary and access the Android system.
- Use the app's Android permissions to access camera, mic, GPS or network.
- Corrupt or ransom guest rootfs images.
- Exploit the JNI bridge to crash or compromise the app process.

The app does not provide a security boundary — it must use Android's existing
security mechanisms and add defense-in-depth layers where Android's model does
not extend to guest runtimes.

## Decision

Adopt a defense-in-depth security model with five layers:

### Layer 1 — Android sandbox (base)

The app runs in the standard Android application sandbox. All guest processes
inherit the app's UID and are subject to Android's SELinux policy, filesystem
permissions and kernel-level isolation. No additional privilege elevation is
attempted.

### Layer 2 — PRoot filesystem isolation (Linux native/PRoot)

PRoot provides chroot-like filesystem isolation. The guest rootfs is contained
within the app's private directory. Bind mounts are the only paths where guest
code can access Android storage. `-b` flags are validated to prevent
`/data/data/...` exposure.

### Layer 3 — JNI bridge hardening

The JNI bridge is the only native code entry point. Hardening measures:
- All input buffers are bounds-checked before use.
- Opaque 64-bit handles prevent raw pointer exposure.
- Handle validity is verified on every operation.
- No JNI reference is stored across threads without synchronization.
- Panics in Rust code are caught and converted to error codes, never
  unwinding across JNI.

### Layer 4 — Permission brokerage

Hardware access is brokered through typed interfaces (see ADR-009). Guest code
never holds Android permissions directly. Each access requires a session-scoped
token.

### Layer 5 — Integrity verification

Rootfs manifests (ADR-003/004) and application capsules (ADR-010) are verified
before every launch. Tampered images are rejected and reported.

### Additional measures

- All secrets (KEK, ATTEST_KEY, signing keys) are stored in Android Keystore
  or EncryptedSharedPreferences.
- Network traffic from guests is routed through the NetworkBroker, enabling
  per-app firewall rules.
- Session diagnostic buffers are in-process only; they are never written to
  disk by default.
- A watchdog timer kills any session that does not respond to a health check
  within 30 seconds.

### Threat model summary

| Threat | Mitigation | Residual risk |
|---|---|---|
| Guest reads app-private data | PRoot isolation, no `/data/data/` bind mount | PRoot escape (kernel bug) |
| Guest uses camera without consent | HardwareBroker token required | Malicious code in broker process |
| Guest modifies another guest's rootfs | Separate rootfs per guest, different paths | Android UID sharing |
| JNI buffer overflow | Bounds checks, opaque handles | Rust `unsafe` bugs |
| Tampered rootfs | HMAC-signed manifest | Key extraction from Keystore |
| Network exfiltration | NetworkBroker firewall | Guest tunnels over allowed port |

## Invariants

1. The app never requests root access.
2. No guest process runs with a higher UID than the app.
3. All inter-guest communication is explicitly configured (no default).
4. The watchdog timer fires independently of guest process state.
5. Security-sensitive operations are logged to the audit trail.

## Alternatives considered

### Run each guest in a separate process

Rejected. Android process-per-guest would require multiple APKs, services or
`android:isolatedProcess`. This is compatible with the architecture but
requires significant restructuring. It may be adopted later for high-security
workloads.

### Use SELinux policies per guest

Rejected. Custom SELinux policies require a rooted device or a custom ROM.
The app must work on stock Android without special firmware.

## Consequences

- The security model relies on Android's kernel-level isolation as the
  foundation.
- PRoot is not a security boundary; it is a filesystem translation layer.
- The broker architecture adds latency to hardware access.
- A threat model document will be maintained at `docs/security/THREAT_MODEL.md`.
- External security review is recommended before any financial or medical use
  case.

## Revisit triggers

- A PRoot escape vulnerability is published with a working exploit.
- Android introduces per-process or per-profile guest isolation APIs.
- The app targets use cases requiring formal security certification.
