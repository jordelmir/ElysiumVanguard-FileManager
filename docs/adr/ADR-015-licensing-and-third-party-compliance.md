# ADR-015: Licensing and third-party compliance

- Status: Draft
- Date: 2026-07-13
- Owners: Elysium Vanguard legal/compliance

## Context

The app bundles, distributes and interacts with software under various open
source licenses: GPL (PRoot, QEMU), LGPL (Wine, Box64), MIT (TigerVNC,
libvncserver), Apache 2.0 (Android components), BSD (various libraries).
Without a compliance framework, the app risks license violations by:

- Not reproducing required notices.
- Combining GPL-licensed code with incompatible licensing.
- Distributing binaries without source code availability.
- Modifying GPL/LGPL code without publishing the modifications.

## Decision

Establish a compliance framework with four components:

### Component 1 — Third-party component registry

A living document at `docs/legal/THIRD_PARTY_COMPONENTS.md` lists every
externally-sourced component:

| Component | License | Source | Modified | Bundled | Notice file |
|---|---|---|---|---|---|
| PRoot | GPL-2.0 | https://github.com/proot-me/proot | No | Yes | docs/legal/NOTICES/GPL-2.0.txt |
| Box64 | LGPL-2.1 | https://github.com/ptitSeb/box64 | No | Yes | docs/legal/NOTICES/LGPL-2.1.txt |
| Wine | LGPL-2.1 | https://gitlab.winehq.org/wine/wine | No | Yes | docs/legal/NOTICES/LGPL-2.1.txt |
| QEMU | GPL-2.0 | https://gitlab.com/qemu-project/qemu | No | Yes | docs/legal/NOTICES/GPL-2.0.txt |
| TigerVNC | GPL-2.0 | https://github.com/TigerVNC/tigervnc | No | In distro | docs/legal/NOTICES/GPL-2.0.txt |
| AndroidX | Apache 2.0 | Jetpack | No | Yes | Included in APK |
| Kotlin stdlib | Apache 2.0 | JetBrains | No | Yes | Included in APK |

### Component 2 — Notice bundling

All required license notices are:
- Included in the APK under `assets/licenses/`.
- Accessible from the app's Settings → Licenses screen.
- Included in the app's `About` dialog.

### Component 3 — Source code availability

For GPL/LGPL components:
- The `docs/legal/SOURCE_CODE_AVAILABILITY.md` document describes how to obtain
  the complete corresponding source code.
- Source code for modified components (if any) is published in a public
  repository.
- A written offer is included in the app's distribution metadata.

### Component 4 — Compatibility matrix

| Component | Imported by | Inherent obligation |
|---|---|---|
| PRoot binary (GPL-2.0) | PRootRuntimeBackend | Ours: notice + source availability. PRoot is a separate process, not a linked library, so the GPL does not require the app itself to be GPL. |
| Box64 binary (LGPL-2.1) | WinLayer | Ours: notice + source availability for Box64. LGPL allows dynamic linking from proprietary apps. |
| Wine libraries (LGPL-2.1) | WinLayer | Same as Box64. Wine runs in the guest, not the host process. |
| QEMU binary (GPL-2.0) | VmBackend | Same as PRoot. QEMU is a separate process. |
| Terminal parser (Apache 2.0) | :app | No source requirement. Notice included. |

### Compliance automation

- A Gradle task `checkLicenses` verifies that all dependencies in
  `debugRuntimeClasspath` have a declared license in the registry.
- CI runs `checkLicenses` on every PR.
- Missing or unknown licenses block the build.

### Third-party modification policy

- Modified components have their patches in a `patches/` directory at the
  repository root.
- Each patch file documents the original source URL, commit hash and the
  reason for modification.
- Patches are reviewed for license compatibility before merging.

## Invariants

1. Every third-party component has an entry in the registry.
2. Every GPL/LGPL component has a corresponding notice in the APK.
3. Modified components must have a patch file and source publication.
4. The license check Gradle task passes before any release.
5. No GPL-licensed code is linked into the app process (separate process only).

## Alternatives considered

### Use only permissively-licensed components

Rejected. GPL tools (PRoot, QEMU) are essential for the app's core
functionality. No permissively-licensed equivalent exists at the same quality
level.

### Ignore GPL compliance (common in Android ecosystem)

Rejected. Compliance is a legal requirement, not optional. The app must be a
good open source citizen to build trust with the community.

## Consequences

- Compliance requires ongoing maintenance as components are added or updated.
- The `checkLicenses` Gradle task may need updates as dependencies change.
- Legal review is recommended before any major dependency addition.
- The notice screen in Settings provides transparency to users.
- Source code availability documentation satisfies GPL obligations.

## Revisit triggers

- A component's license changes to a GPL version with different requirements.
- The app begins linking GPL code directly (not as a separate process).
- A component's modification requires publishing source code.
- Legal guidance on Android guest process GPL compliance changes.
