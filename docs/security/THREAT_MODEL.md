# Elysium Vanguard threat model

**Status:** Phase 0 baseline
**Last reviewed:** 2026-07-12
**Scope:** Android app, local servers, rootfs supply chain, PRoot sessions,
terminal/native bridge, files, backups and future display/bridge work.

## Security position

Elysium runs untrusted files and potentially hostile Linux packages with the
same Android UID as the application. PRoot changes path/process behavior but is
not a VM, kernel sandbox or privilege boundary. A compromised distro process
must therefore be assumed capable of reading and modifying every file and
socket exposed to that UID unless the application creates a stronger boundary.

The current release is development-only. Distribution is blocked until all
Phase 0 critical gaps below are closed or explicitly accepted by the owner with
evidence.

## Assets

- user files, media, vault ciphertext and metadata;
- SFTP host keys, bearer tokens, passwords and future capability tokens;
- app-private databases, preferences, rootfs and session state;
- integrity of downloaded rootfs, native binaries and APK updates;
- terminal input/output, clipboard and future display/audio streams;
- availability, battery, storage, network and device responsiveness;
- truthful capability/status reporting.

## Trust boundaries

```text
Internet mirrors / custom URL
          │ untrusted bytes
          ▼
Downloader → verifier → bounded staging extractor → app-private rootfs
                                                    │ same Android UID
Android UI → typed policy → runtime backend → PTY/PRoot child processes
    │                          │
    │ explicit publish        ├─ optional file mounts
    ▼                          └─ future display/audio/clipboard brokers
LAN clients → HTTP/SFTP listeners

Android backup provider ← allow-listed app data
```

## Adversaries and abuse cases

1. A malicious archive attempts traversal, link escape, device-file creation,
   decompression bombs or disk exhaustion.
2. A compromised mirror, redirect or custom URL supplies modified executables.
3. A hostile distro package reads app-private state, abuses mounted storage,
   scans the network, forks indefinitely or survives UI stop.
4. A LAN peer guesses/replays credentials, exploits parsers or downloads files
   outside the published root.
5. Malicious terminal output abuses OSC, huge escape payloads, Unicode edge
   cases, clipboard writes or renderer allocation.
6. JNI input triggers memory corruption, integer overflow, handle reuse,
   use-after-close or a panic across the boundary.
7. Activity recreation, process death or racing stop/start leaves orphaned
   children, sockets, FDs or foreground notifications.
8. Backup or logs expose keys, paths, terminal contents or diagnostics.
9. A simulated desktop/capability misleads the user into trusting nonexistent
   isolation or functionality.

## Existing controls verified in Phase 0

- Rootfs catalog artifacts use pinned HTTPS URLs and SHA-256 values.
- Extraction occurs under staging with entry, path, per-entry and total-byte
  limits; traversal and escaping symbolic links are rejected.
- Catalog activation preserves the prior rootfs and rolls back on failure.
- Device nodes/FIFOs/sockets are not materialized from archives.
- No external-storage bind is added to a distro by default.
- Read-only mount requests fail closed because PRoot cannot enforce them as a
  security boundary.
- Android provider and services are not exported; only the launcher Activity is
  exported.
- HTTP and SFTP classes default to loopback. LAN binding requires the explicit
  sharing flow and credentials.
- Sensitive host-key, OCR and vault payload paths are excluded from cloud
  backup rules.
- Release logging is guarded in existing hardened paths; no analytics or ad SDK
  is declared.

## Required controls by boundary

### Rootfs and supply chain

- Require HTTPS for catalog and custom downloads unless a per-install warning
  and policy explicitly allows another scheme.
- Cap compressed download bytes, redirects, extracted bytes, file count and
  path length; check free space before and during installation.
- Verify hash before extraction and record URL, redirects, hash, timestamp and
  extractor version in a receipt.
- Add signed Elysium manifests and rollback protection before automatic
  updates.
- Treat rootfs package scripts and all installed binaries as untrusted.

### Runtime and native code

- Pass argv/env as arrays; never evaluate user data with `sh -c`.
- Use a PTY process group, bounded nonblocking I/O and an ordered idempotent
  stop with escalation and reap proof.
- Validate JNI handles, lengths, UTF-8/byte buffers and lifecycle; zero secrets
  when practical; prevent panic/exception escape.
- Bound process count, memory, disk, output rate and session count where Android
  APIs permit. Surface when a limit cannot be enforced.
- Keep filesystem, network, clipboard, display, audio and hardware bridges
  capability-scoped and disabled by default.

### Terminal and display

- Bound OSC/CSI strings, scrollback, hyperlinks, title and clipboard payloads.
- Clipboard reads/writes require visible policy; never execute terminal output.
- Fuzz parser and native boundaries and test arbitrary fragmentation.
- Remove the simulated VNC bitmap from capability claims until an embedded
  server and real client process pass acceptance.

### Local network services

- Loopback by default; an explicit user action may publish to LAN.
- Use random high-entropy credentials, constant-time comparison where
  applicable, connection/time/size limits and sanitized logs.
- Scope every route to a canonical root and reject traversal/symlink escape.
- Display listener address and a persistent stop action while published.
- Add TLS/host-key verification appropriate to each protocol and rotation or
  revocation controls.

### Storage, backup and privacy

- Request high-risk Android permissions only when a real feature needs them and
  explain degraded behavior.
- Never put credentials in QR URLs, logs, crash text or unencrypted prefs
  longer than the active sharing session requires.
- Exclude rootfs, runtime state, keys, vault data and terminal history from cloud
  backup unless a separate encrypted opt-in design is approved.
- Align `docs/PRIVACY_POLICY.md` with executable network and permission behavior
  before distribution.

## Open findings

| ID | Severity | Finding | Required resolution |
|---|---|---|---|
| TM-001 | Critical | Pipe terminal has no real PTY/process-group lifecycle. | Native PTY, race tests and physical orphan check. |
| TM-002 | Critical | PRoot children share the app UID and app-private access. | Explicit trust warning, minimal mounts, capability brokers; VM for strong isolation. |
| TM-003 | High | Custom rootfs trust policy is not yet signed and may accept unpinned content. | HTTPS policy, optional expected hash, signed manifests and receipts. |
| TM-004 | High | Simulated VNC bitmap can be mistaken for a desktop capability. | Mark unavailable/remove claim until real X11 acceptance. |
| TM-005 | High | Backup allow-list includes broad file/database domains. | Inventory actual sensitive paths and convert to narrow includes. |
| TM-006 | High | Privacy policy is stale relative to downloads and permissions. | Rewrite and verify against manifest/network scan before release. |
| TM-007 | Medium | LAN servers intentionally bind all interfaces after user start. | Visible publish policy, protocol hardening and device tests. |
| TM-008 | Medium | Static analysis has 64 warnings after errors reached zero. | Triage by security/runtime impact; no blanket baseline. |

## Security acceptance gate

- zero unresolved critical findings for the shipped capability;
- no exported component beyond documented entry points;
- no listener on `0.0.0.0` without explicit policy and visible state;
- rootfs malicious-archive suite and checksum failures pass;
- native sanitizers/fuzzers pass where supported;
- stop leaves zero child PIDs, sockets and owned FDs;
- logcat and backup artifacts contain no secrets;
- capability diagnostics distinguish PRoot, VM and unavailable features.

## Non-goals

- Claiming PRoot protects Android app-private data from a hostile distro.
- Running Windows images without a user-supplied licensed image.
- Circumventing Android SELinux, signature, executable-code or storage policy.
- Treating cosmetic UI states as security controls.
