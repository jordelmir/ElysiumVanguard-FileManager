# ADR-008: Virtualization backend (AVF and QEMU)

- Status: Draft
- Date: 2026-07-13
- Owners: Elysium Vanguard runtime
- Depends on: ADR-001

## Context

Certain workloads require a full virtual machine rather than process-level
translation: custom kernels, non-Linux operating systems, workloads requiring
kernel module loading or workloads that must be isolated at the hardware level.
Android 13+ provides the Android Virtualization Framework (AVF) and QEMU can be
bundled for broader compatibility.

## Decision

Implement two virtualization backends gated by device capability:

### AVF backend (API 33+, protected VM)

AVF provides a protected VM (pVM) using crosvm on supported devices. The
backend:

- Uses `VirtualMachineManager` to create a `VirtualMachine` with configured
  CPU/memory.
- Passes a boot image (custom kernel + initramfs) built from the app's assets.
- Routes guest console output to the terminal subsystem.
- Manages VM lifecycle through the standard `RuntimeSession` contract.
- Reports `CapabilityFlag.AVF_VM` if `VirtualMachineManager` probe succeeds.

### QEMU backend (API 26+, full system emulation)

QEMU is bundled as a native binary for ARM64 hosts. The backend:

- Extracts QEMU from the app's native libraries on first use.
- Launches QEMU as a child process with the configured VM spec.
- Configures TCG thread count, memory ballooning and device model.
- Supports x86-64, ARM64, and RISC-V guests.
- Exposes a VNC console for graphical guests and a serial console for TUI
  guests.
- Reports `CapabilityFlag.QEMU_VM` if the QEMU binary executes successfully.

### Shared VM specification

Both backends share a common `VmSpec`:

```kotlin
data class VmSpec(
    val cpus: Int,
    val memoryMb: Int,
    val diskImages: List<DiskImage>,
    val consoleType: ConsoleType,       // SERIAL | VNC | BOTH
    val networkMode: VmNetworkMode,     // USER | TAP | BRIDGE
    val bootDevice: BootDevice,         // KERNEL | DISK | PXE
    val kernelPath: String?,            // for KERNEL boot
    val kernelCmdline: String?
)
```

### Capability probing

The backend probes at startup:
1. Check for AVF availability (`VirtualMachineManager#isSupported`).
2. Verify QEMU binary integrity (SHA-256 from asset manifest).
3. Run QEMU `--version` to confirm execution.
4. Report available backends as capability flags.

## Invariants

1. AVF is preferred when available; QEMU is the fallback.
2. VM specs are validated before launch (memory bounds, disk existence).
3. The VM process is owned by the session and killed on `stop()`.
4. QEMU binary verification must pass before every launch.
5. Guest networking uses QEMU user-mode (SLIRP) by default; TAP requires
   additional capabilities.

## Alternatives considered

### AVF only

Rejected. AVF requires API 33+ and specific hardware support (pKVM, protected
VM). QEMU provides a universal fallback for older devices and custom use cases.

### Embedded QEMU as a system library

Rejected. Bundling QEMU as a native binary allows version pinning and avoids
system library compatibility issues.

## Consequences

- APK size increases by ~15 MB for the QEMU binary.
- VM performance on QEMU TCG is significantly slower than native execution.
- AVF performance depends on the device's pKVM implementation.
- Two backends must be tested and maintained.
- Future microVM backends (Firecracker, Cloud Hypervisor) can reuse VmSpec.

## Revisit triggers

- AVF becomes available on all target devices (API 33+).
- A microVM backend demonstrates better performance or lower overhead.
- QEMU binary size becomes prohibitive for the APK.
