# ADR-007: WinLayer — Wine + Box64 architecture

- Status: Draft
- Date: 2026-07-13
- Owners: Elysium Vanguard runtime
- Depends on: ADR-001

## Context

The Universal Computing Fabric must run Windows x86-64 applications on ARM64
Android. This requires two translation layers working in concert:
- Wine (Windows API translation to POSIX)
- Box64 (x86-64 CPU instruction translation to ARM64)

Without a structured WinLayer, Wine would be installed ad-hoc inside a distro
container without version management, dependency reconciliation or performance
tuning specific to Android/ARM64.

## Decision

Implement WinLayer as a dedicated RuntimeBackend plugin that manages the
Wine+Box64 lifecycle:

### Architecture stack

```
Application (Win32 PE)
  ↓
Wine (wine64 $WINEPREFIX/drive_c/app.exe)
  ↓
Box64 (x86-64 → ARM64 JIT)
  ↓
PRoot/Native process (ELF ARM64)
  ↓
Linux kernel / Android
```

### Wine prefix management

Each Windows application gets an isolated Wine prefix at a well-known path
under the application data directory:

```
{appData}/winlayer/prefixes/{appId}/
  drive_c/
    Program Files/
    Users/
    windows/
  user.reg
  system.reg
  userdef.reg
```

Prefixes are created on demand from a clean template. Shared components
(Wine Mono, Gecko) are stored in a shared read-only directory and symlinked
into each prefix.

### Application packaging

Windows applications are packaged as ApplicationCapsules with:
- `type: WINDOWS_WINE`
- A `winpe_hash` field identifying the PE executable
- `wine_version` pin (system or per-app)
- `box64_flags` (dynamic FPU, memory limits, JIT tuning)
- `dxvk_enabled` toggle

### Performance tuning

- Box64 runs with `-1` (dynamic FPU) for x87-heavy applications.
- Wine uses `-thread-count` matching the device CPU cores.
- DXVK (DirectX → Vulkan) is detected and enabled per-app when the device
  supports Vulkan.
- 3D acceleration is disabled by default and must be explicitly opted into
  per-application.

### Dependency isolation

- Box64 itself is stored as a native binary in the app's native library
  directory.
- Wine is bundled per Wine version in the app's data directory.
- Shared libraries (libfreetype, libX11, libGL) are provided by the PRoot
  distro, not bundled.

## Invariants

1. Every Windows application runs in its own prefix.
2. Box64 runs at `-9` (maximum JIT) by default for applications, `-5` for
   installers.
3. WinLayer does not claim DirectX, Vulkan or OpenGL capability unless the
   device probe confirms it.
4. A crashed Wine process does not affect other running prefixes.
5. Wine and Box64 binaries are verified against known hashes before execution.

## Alternatives considered

### Bundling a complete Wine + Box64 in a single distro

Rejected. Mixing Windows app state with the distro filesystem creates
migration, backup and isolation problems.

### Using an x86-64 compatibility layer (FEX, QEMU user-mode)

Rejected for now. Box64 has the best Wine compatibility on ARM64. FEX may be
evaluated later if Box64 cannot handle specific workloads.

## Consequences

- WinLayer requires significant disk space per prefix (~200 MB baseline).
- Performance depends on Box64 JIT quality and the specific Wine version.
- DirectX games are unlikely to run well without Vulkan translation.
- WinLayer adds a new backend type to the runtime domain model.
- The architecture allows adding FEX or other emulators as alternative
  CPU backends without changing the Wine management layer.

## Revisit triggers

- Box64 does not support a critical x86-64 instruction used by target
  applications.
- Wine 10+ or a Wine fork significantly changes the prefix layout.
- FEX demonstrates substantially better x86-64 → ARM64 translation quality.
