# Elysium Vanguard — Threat Model

**Status:** Accepted (matches ADR-011)
**Date:** 2026-07-13
**Scope:** Elysium Vanguard Android app + Elysium Vanguard Linux distribution
+ WinLayer (Wine + Box64) + AVF / QEMU virtualisation + remote backends.
**Out of scope:** the underlying Android OS, the device firmware, the OEM
ROM, the network operator, and the upstream package mirrors (Debian,
crates.io, etc.).

## 1. Trust boundaries

```
┌──────────────────────────────────────────────────────────────────────┐
│  Android OS / OEM kernel / bootloader                                │
│  ▸ we trust it but do not depend on it being well-behaved           │
└──────────────────────────────────────────────────────────────────────┘
              ↑               ↑                ↑
              │ JNI           │ Hilt           │ Intent / Provider
┌──────────────────────────────────────────────────────────────────────┐
│  Elysium Vanguard process (this app)                                 │
│  ▸ app-private storage, app-private DB, AndroidKeyStore            │
│  ▸ we own the code; the attacker may read but not modify it         │
└──────────────────────────────────────────────────────────────────────┘
              │                │                  │
   bind-mount  │   unix socket  │   package stream │
              ↓                ↓                  ↓
┌──────────────────────────────────────────────────────────────────────┐
│  PRoot guest (Elysium Vanguard Linux)                                │
│  ▸ rootfs is treated as adversarial input                            │
│  ▸ processes run as the app uid; no real root                        │
│  ▸ cannot mount / umount, cannot open privileged ports directly     │
└──────────────────────────────────────────────────────────────────────┘
              │                │                  │
   wine bin   │   box64 dlopen  │   ovmf / qemu    │
              ↓                ↓                  ↓
┌──────────────────────────────────────────────────────────────────────┐
│  WinLayer / Windows VM (optional, user-supplied)                     │
│  ▸ Windows VM is opaque to the broker; it sees only the AVF/QEMU    │
│    backend and the virtualised hardware                              │
│  ▸ Wine + Box64 are a translation layer; the process tree inherits  │
│    the guest's capabilities and the host broker's policies         │
└──────────────────────────────────────────────────────────────────────┘
```

## 2. Adversary model

| Adversary             | Capability                                                          | Primary mitigation                                       |
|-----------------------|---------------------------------------------------------------------|----------------------------------------------------------|
| Network attacker      | observes all network traffic to/from the device                    | TLS-only outbound, no 0.0.0.0 binds without consent      |
| Compromised rootfs    | runs arbitrary code as the app uid inside the guest                 | signature verification, integrity checksums, journaling   |
| Compromised Windows VM| runs arbitrary code with VM-level isolation                          | user-supplied image only, terms of use, no auto-launch   |
| Compromised Wine app  | runs in the guest, can read guest FS and request ports              | per-app Wine prefix, capability tokens, audit log        |
| Malicious archive     | a `.tar.gz` / `.zip` / rootfs image with path traversal             | canonicalisation, file / byte limits, staging+rename     |
| USB peripheral        | HID injection, BadUSB, malicious storage                            | explicit user consent, capability token, audit           |
| Clipboard exfiltration| guest reads / writes the Android clipboard without consent          | ClipboardBroker policy, max-size, no auto-secret share   |
| Privilege escalation  | guest tries to write outside its bind-mounted view                   | bind mounts are read-only by default, FilesystemBridge   |
| Dependency confusion  | a build-time dependency is replaced with a malicious one             | SHA-256 pins in build, SBOM, lockfile                    |
| Replay / downgrade    | a signed v0.5 rootfs is replayed after a v1.0 is installed          | monotonic version in SignedManifest, server-side reject   |
| Native memory corruption| JNI boundary crossed with bad data                                | narrow JNI surface, opaque handles, fuzzing              |
| Symlink / hardlink    | archive contains a symlink pointing outside the root                | symlinks refused during extraction, staging + verify      |
| Fork bomb / DoS       | guest spawns infinite processes                                     | PRoot --kill-on-exit, ulimit at the launcher             |
| Disk exhaustion       | guest fills /sdcard / vault                                         | per-session quota, FilesystemBridge size checks          |
| Decompression bomb    | archive claims 4 GB but expands to 4 TB                             | per-byte cap, per-file cap, ratio check                  |
| Log injection         | a guest process writes ANSI escapes into a log line                 | log strings stripped of control chars before serialise    |
| WebView abuse         | a feature uses an embedded WebView with javascript                   | WebView is opt-in, javascript disabled by default        |
| Intent spoofing       | a third-party app sends an Intent that triggers an action           | exported=false on every component, signature check       |

## 3. Hardening controls that ship

### 3.1 Process and runtime

- `RuntimeDatabase.fallbackToDestructiveMigration()` is **not** used.
  Schema migrations are explicit.
- `GlobalScope` does not appear in production code.
- `runBlocking` does not appear in the production request path.
- `Runtime.exec(...)` with a concatenated string does not appear in
  production code. All commands are structured argv.
- `chmod 777` does not appear in production code.
- No secrets are written to logs, JSON, or SharedPreferences.

### 3.2 PRoot and rootfs

- The rootfs is verified by SHA-256 before extraction.
- The rootfs manifest is signed by an RSA key in the Android Keystore.
  The HMAC fallback is dev-only and is reported as such in the
  SignedManifest.
- Bind mounts default to read-only. The FilesystemBridge refuses to
  emit a `-b` flag for a path that violates the read-only policy.
- `--kill-on-exit` is always passed to proot.
- `--link2symlink` is always passed.
- `PROOT_NO_SECCOMP=1` is always set, to avoid Android-kernel seccomp
  differences.

### 3.3 Session lifecycle

- `ProotRuntimeBackend.stop()` sends SIGTERM to the entire process
  group, escalates to SIGKILL after a 1.5 s grace, closes the master
  FD, and reaps the child. It never relies on `close()` alone.
- `clipboard.Image` writes go to app-private storage under
  `filesDir/clipboard/<sessionId>/`, not to a world-readable location.
- The ClipboardBroker `clear()` wipes the per-session directory on
  session teardown.
- The HardwareBroker `close()` cancels the scope and clears the
  token indexes.

### 3.4 Network

- `PortBinding` defaults to `127.0.0.1`. A non-loopback bind is
  rejected under BLOCKED / LOOPBACK policy.
- The `NetworkBrokerImpl` reports which enforcement layer is active
  (IPTABLES / ENV_ONLY / DISABLED) in `applicationFor(...)` so the
  diagnostic surface is honest about whether the policy is actually
  enforced.
- `iptables` is not invoked from the JVM test path; production wires
  the iptables applier.

### 3.5 Cryptography

- Rootfs manifest: RSA-2048 / SHA-256 in production. HMAC-SHA-256 in
  dev / tests.
- Vault encryption: Tink AES-256-GCM, Android Keystore-backed key.
- TLS for the local HTTP server: not yet wired; tracked under
  'local server TLS' in the security backlog.

## 4. Known limitations and accepted risks

| Risk                                                                  | Why accepted                                                                       | Owner                  |
|-----------------------------------------------------------------------|------------------------------------------------------------------------------------|------------------------|
| PRoot is not a security boundary. A malicious guest can call any syscall the host allows. | PRoot does not advertise isolation. The audit (section 5.3) is explicit. The fix is a real VM (AVF) for untrusted workloads. | Virtualization slice   |
| The local HTTP server is plain HTTP.                                  | Currently only bound to 127.0.0.1; access requires a bearer token.                 | Server TLS slice       |
| The Android Keystore private key is not hardware-backed on every device. | Some Android devices use TEE; others fall back to software. The signer is honest about which. | Hardware attestation slice |
| The WinLayer is not sandboxed at the Wine level.                      | A compromised Windows app can read / write the prefix. The per-app prefix is the current mitigation. | WinLayer isolation slice |
| The X11 server (Xvnc) accepts VNC password authentication.           | The password is supplied by the user, kept in app-private storage, never logged.   | Display slice          |
| The remote backend (ADR-013) sends the session's network traffic over TLS to a user-chosen server. | The user opts in. The session's NetworkPolicy still applies at the broker. | Remote slice           |

## 5. Open work

The order's section 26 is the source of truth for the long list. Items
not yet implemented in code but tracked:

- Threat model in this file (done 2026-07-13).
- Capability detection for hardware-backed Keystore.
- FileProvider config for clipboard image access (currently uses
  `Uri.fromFile` to app-private storage; this works but a real
  FileProvider would be safer).
- TLS for the local HTTP server.
- Static analysis with detekt + ktlint + Android lint in CI.
- Secret scan (gitleaks / trufflehog) in CI.
- License scan in CI.

## 6. Update cadence

This document is updated whenever a new risk is identified, a new
component lands, or a known limitation is closed. The companion
ADR-011 ('Security model') tracks the high-level decisions; this
file tracks the concrete controls.
