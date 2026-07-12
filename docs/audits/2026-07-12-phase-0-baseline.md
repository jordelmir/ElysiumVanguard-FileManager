# Phase 0 reproducible baseline audit

**Date:** 2026-07-12
**Branch:** `main` (owner-authorized)
**Baseline commit before this audit batch:**
`5bc7c1e26ca182af8e224fedd347c74f9a30e806`

## Repository and build topology

- One Gradle module: `:app`.
- Android application ID: `com.elysium.vanguard`.
- Minimum/target/compile SDK: 26 / 34 / 34.
- AGP 8.2.0; Kotlin Android plugin 1.9.20; Compose compiler 1.5.4.
- Gradle Wrapper 8.10.2; Java 17.0.18; macOS ARM64 host.
- Android NDK pinned and installed: r29 `29.0.14206865`.
- Rust pinned: 1.97.0 with Rustfmt, Clippy and
  `aarch64-linux-android` standard library.
- Physical-device target: ARM64 Honor Magic V2; no ADB device was attached at
  the end of this audit.

## Reproduction commands

```sh
./gradlew lintDebug testDebugUnitTest assembleDebug
rustc --version
cargo --version
cargo clippy --version
rustup target list --installed
adb devices -l
```

The combined Gradle command completed successfully in 39 seconds after the
Phase 0 permission and backup-rule changes.

## Automated result

| Gate | Result |
|---|---|
| JVM tests | 846 executed, 0 failures, 0 errors, 2 skipped |
| Android lint errors | 0 |
| Android lint warnings | 64 |
| Debug APK | built successfully |
| APK bytes | 112,930,943 |
| APK SHA-256 | `23b2bff6c58ac13868d58a2e50884eed1d1b0c69878c9213d2fe41c69e976ba0` |
| Physical install/launch | blocked: `adb devices -l` returned no device |

The APK hash is an audit snapshot and changes when subsequent native/runtime
work is packaged.

## Lint inventory

| ID | Count | Triage |
|---|---:|---|
| `GradleDependency` | 25 | dependency-upgrade program, not blind bulk update |
| `SdCardPath` | 7 | inspect real path assumptions; runtime/file-manager risk |
| `TrustAllX509TrustManager` | 7 | all reported in transitive BouncyCastle/MINA/JGit jars; verify reachability and upgrade path |
| `ObsoleteSdkInt` | 6 | cleanup |
| `UnnecessaryComposedModifier` | 6 | UI performance cleanup |
| `StaticFieldLeak` | 5 | inspect injected Context ownership in ViewModels |
| `ModifierParameter` | 2 | Compose API cleanup |
| `MonochromeLauncherIcon` | 2 | launcher asset |
| other single warnings | 4 | accessibility, inlined API, custom lint compatibility, target SDK |

No lint baseline or blanket suppression was added. The former blocking
`QUERY_ALL_PACKAGES` error was resolved by removing the unused permission.

## High-risk architecture findings

1. `TerminalSession` still uses `ProcessBuilder` pipes; `PtyFactory` returns
   `PipePty`, so no PTY capability is complete.
2. Terminal rows/columns are immutable, resize clears content and the parser is
   a partial character parser rather than an incremental byte parser.
3. The renderer repaints the whole centered grid.
4. `VncSession` renders a simulated desktop bitmap; it is not a Linux display
   server.
5. Local server classes previously defaulted to `0.0.0.0`; Phase 0 changed the
   defaults to loopback and made LAN exposure explicit in sharing flows.
6. PRoot runs under the Android app UID and must not be represented as strong
   isolation.
7. The codebase contains `runBlocking`, non-null assertions and broad/empty
   catches that require risk-driven removal.
8. Public distribution is blocked by unresolved project licensing,
   corresponding-source/relink evidence and missing transitive SBOM.

## Phase 0 controls completed

- removed unused package-query, overlay, usage-stats and special-use foreground
  permissions;
- changed HTTP/SFTP defaults to `127.0.0.1` with explicit LAN publication;
- converted Android backup from broad file-domain inclusion to a narrow
  database/preference allow-list;
- verified the rootfs transactional installer and bounded extractor suite;
- corrected talloc's library license to LGPL-3.0-or-later using upstream source
  headers and packaged the exact license text;
- accepted ADR-001 and ADR-002;
- added threat model, component inventory and compatibility gates;
- aligned the privacy policy with rootfs downloads and actual permissions;
- installed/pinned the Android NDK and Rust Android target.

## Next mandatory gate

Implement and test the first vertical slice in order:

```text
runtime domain → native PTY → PRoot Debian → byte VT parser
→ mutable screen buffer → damage-tracked renderer → physical ADB acceptance
```

X11/desktop work cannot claim readiness before that gate passes.
