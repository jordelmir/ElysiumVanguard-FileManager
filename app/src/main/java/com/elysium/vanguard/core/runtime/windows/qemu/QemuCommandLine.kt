package com.elysium.vanguard.core.runtime.windows.qemu

import com.elysium.vanguard.core.runtime.windows.WindowsVmSpec

/**
 * Phase 23 — the QEMU command line builder.
 *
 * A pure function from [WindowsVmSpec] + [QemuOptions] to
 * the argument vector that spawns a QEMU process. The
 * builder is JVM-testable end-to-end: no `Process` is
 * spawned in the test path; the test asserts on the
 * argument list directly.
 *
 * Why a pure function (and not a method on the backend):
 *
 *   - The argument list is the *contract* between the
 *     runtime and QEMU. Pinning it in a testable builder
 *     means a regression in the command line is caught
 *     by the JVM test suite, not by an on-device smoke
 *     test.
 *   - The same builder powers the production backend
 *     (QemuWindowsVmBackend) and any future dry-run
 *     / preview UI. The UI can render the exact
 *     command line the runtime would spawn.
 *
 * The arguments follow the QEMU 8.x / 9.x convention.
 * A future QEMU major version may change the flag set;
 * the builder is the single point of update.
 */
object QemuCommandLine {

    /**
     * Build the QEMU command line for [spec] with [options].
     * The returned list is the argv vector: `argv[0]` is
     * the qemu binary, `argv[1..]` are the flags.
     */
    fun build(
        spec: WindowsVmSpec,
        options: QemuOptions,
        qemuBinary: String = "qemu-system-x86_64"
    ): List<String> {
        val args = mutableListOf(qemuBinary)
        args += "-name"
        args += spec.id
        args += "-machine"
        args += if (spec.requiresKvm) "type=q35,accel=kvm" else "type=q35"
        args += "-cpu"
        args += "host"
        args += "-smp"
        args += "${spec.recommendedCpuCores}"
        args += "-m"
        args += "${spec.recommendedRamMb}"
        // The boot ISO is the install media. We attach
        // it read-only.
        args += "-drive"
        args += "file=${spec.bootIsoUrl},media=cdrom,readonly=on,if=ide,index=0"
        // The virtio driver ISO carries the paravirt
        // drivers; Windows cannot boot without them on
        // a virtio-only disk.
        args += "-drive"
        args += "file=${spec.virtioIsoUrl},media=cdrom,readonly=on,if=ide,index=1"
        // The disk image. The runtime pre-creates a
        // qcow2 file at the path it provides; the VM
        // installs Windows into it on first boot.
        args += "-drive"
        args += "file=${options.diskImagePath},format=qcow2,if=virtio"
        // Networking: a `user` netdev (slirp) bridges the
        // guest to the host's network stack. The runtime
        // can layer its own `NetworkBroker` on top of the
        // host's interface. (Production deployments may
        // prefer a tap device for performance; the
        // options class lets the caller override.)
        args += "-netdev"
        args += "${options.netdev},id=net0"
        args += "-device"
        args += "virtio-net,netdev=net0"
        // QMP socket. The runtime's backend opens this
        // and issues commands (query-status, stop, cont,
        // device_add, device_del).
        args += "-qmp"
        args += "tcp:127.0.0.1:${options.qmpPort},server,nowait"
        // A second TCP port for the human-readable
        // monitor (Ctrl-A x to quit, etc.). The runtime
        // never opens this in production; tests may
        // need it for debugging.
        args += "-monitor"
        args += "tcp:127.0.0.1:${options.monitorPort},server,nowait"
        // Display: a VNC server bound to 127.0.0.1
        // on `5900 + options.vncDisplay`. The runtime's
        // RfbSession connects to this port and streams
        // the guest framebuffer. Phase 47 — the legacy
        // `-display none` is replaced by a real VNC
        // display so the Phase 9.6.5 viewer can render.
        args += "-vnc"
        args += "127.0.0.1:${options.vncDisplay}"
        // TPM (for Windows 11 and Server 2022+).
        if (spec.requiresSwtpm) {
            args += "-chardev"
            args += "socket,id=chrtpm,path=${options.swtpmSocketPath}"
            args += "-tpmdev"
            args += "emulator,chardev=chrtpm"
            args += "-tpm"
            args += "tpm-tis"
        }
        return args
    }
}

/**
 * Per-call options for the QEMU command line. The
 * caller (production backend or test) supplies the
 * network backend, the disk image path, the QMP /
 * monitor port, and (for Windows 11) the SWTPM socket
 * path. The values are not validated by the builder;
 * the production backend validates them at process
 * spawn time.
 */
data class QemuOptions(
    /** Path to a pre-created qcow2 disk image. */
    val diskImagePath: String,
    /** QEMU QMP TCP port. */
    val qmpPort: Int,
    /** QEMU human-readable monitor port. */
    val monitorPort: Int,
    /**
     * Phase 47 — QEMU VNC display number. The actual
     * VNC port is `5900 + vncDisplay` (QEMU's
     * convention). The runtime connects to
     * `127.0.0.1:${5900 + vncDisplay}` from the
     * [com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSession].
     * Set to 0 to disable the VNC display (the
     * legacy headless `none` mode).
     */
    val vncDisplay: Int = 0,
    /** QEMU `-netdev` backend (default: `user`). */
    val netdev: String = "user",
    /** Path to the SWTPM socket (only when the spec
     *  requires TPM). */
    val swtpmSocketPath: String? = null
) {
    init {
        require(diskImagePath.isNotBlank()) { "diskImagePath must not be blank" }
        require(qmpPort in 1..65535) { "qmpPort out of range: $qmpPort" }
        require(monitorPort in 1..65535) { "monitorPort out of range: $monitorPort" }
        require(qmpPort != monitorPort) { "qmpPort and monitorPort must differ" }
        require(monitorPort != vncPort()) {
            "monitorPort and VNC port must differ"
        }
        require(vncDisplay in 0..99) { "vncDisplay out of range: $vncDisplay" }
    }

    /** The VNC port (`5900 + vncDisplay`). */
    fun vncPort(): Int = 5900 + vncDisplay
}
