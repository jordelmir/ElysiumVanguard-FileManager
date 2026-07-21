package com.elysium.vanguard.core.fileactions

import com.elysium.vanguard.core.fileactions.FileActionContext.LinuxDistroTarget
import com.elysium.vanguard.core.fileactions.FileActionContext.WindowsVmTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 93 — the truth table for the
 * [FileActionResolver]. The resolver is a
 * pure function (file + context → actions);
 * the test suite drives every (extension,
 * context) combination.
 */
class FileActionResolverTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    // --- A typical context ---

    private val aptDistro = LinuxDistroTarget(
        id = "debian-12",
        name = "Debian 12",
        packageManager = LinuxPackageManager.APT,
    )
    private val dnfDistro = LinuxDistroTarget(
        id = "fedora-41",
        name = "Fedora 41",
        packageManager = LinuxPackageManager.DNF,
    )
    private val pacmanDistro = LinuxDistroTarget(
        id = "arch-arm",
        name = "Arch Linux ARM",
        packageManager = LinuxPackageManager.PACMAN,
    )
    private val apkDistro = LinuxDistroTarget(
        id = "alpine-3.20",
        name = "Alpine 3.20",
        packageManager = LinuxPackageManager.APK,
    )
    private val typicalContext = FileActionContext(
        linuxDistros = listOf(aptDistro, dnfDistro, pacmanDistro, apkDistro),
        windowsVms = listOf(
            WindowsVmTarget(
                id = "win11-vm",
                name = "Windows 11 VM",
                isRunning = false,
            )
        ),
        preferredLinuxDistroId = "debian-12",
        preferredWindowsVmId = "win11-vm",
    )

    // --- .deb ---

    @Test
    fun `deb file with apt distro offers InstallDebPackage`() {
        val deb = tmp.newFile("test.deb")
        val actions = FileActionResolver.resolve(deb, typicalContext)
        assertEquals(1, actions.size)
        val action = actions.first()
        assertTrue(action is FileAction.InstallDebPackage)
        action as FileAction.InstallDebPackage
        assertEquals("debian-12", action.targetDistroId)
        assertEquals("Debian 12", action.targetDistroName)
        assertEquals(deb.absolutePath, action.packagePath)
    }

    @Test
    fun `deb file with no apt distro offers nothing`() {
        val noAptContext = typicalContext.copy(
            linuxDistros = listOf(dnfDistro, pacmanDistro)
        )
        val deb = tmp.newFile("test.deb")
        val actions = FileActionResolver.resolve(deb, noAptContext)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `deb file offers one action per apt distro`() {
        val twoApt = typicalContext.copy(
            linuxDistros = listOf(aptDistro, dnfDistro, pacmanDistro).let {
                listOf(aptDistro, aptDistro.copy(id = "ubuntu-24", name = "Ubuntu 24"))
            }
        )
        val deb = tmp.newFile("test.deb")
        val actions = FileActionResolver.resolve(deb, twoApt)
        assertEquals(2, actions.size)
    }

    // --- .rpm ---

    @Test
    fun `rpm file with dnf distro offers InstallRpmPackage`() {
        val rpm = tmp.newFile("test.rpm")
        val actions = FileActionResolver.resolve(rpm, typicalContext)
        assertEquals(1, actions.size)
        val action = actions.first()
        assertTrue(action is FileAction.InstallRpmPackage)
        action as FileAction.InstallRpmPackage
        assertEquals("fedora-41", action.targetDistroId)
    }

    // --- .pkg.tar.zst ---

    @Test
    fun `pkg tar zst file with pacman distro offers InstallPacmanPackage`() {
        val pkg = tmp.newFile("test.pkg.tar.zst")
        val actions = FileActionResolver.resolve(pkg, typicalContext)
        assertEquals(1, actions.size)
        val action = actions.first()
        assertTrue(action is FileAction.InstallPacmanPackage)
        action as FileAction.InstallPacmanPackage
        assertEquals("arch-arm", action.targetDistroId)
    }

    // --- .AppImage ---

    @Test
    fun `AppImage file offers RunAppImage in the preferred distro`() {
        val app = tmp.newFile("Blender.AppImage")
        val actions = FileActionResolver.resolve(app, typicalContext)
        assertEquals(1, actions.size)
        val action = actions.first()
        assertTrue(action is FileAction.RunAppImage)
        action as FileAction.RunAppImage
        assertEquals("debian-12", action.targetDistroId)
    }

    @Test
    fun `AppImage file offers nothing when no preferred distro is set`() {
        val noPreferred = typicalContext.copy(preferredLinuxDistroId = null)
        val app = tmp.newFile("Blender.AppImage")
        val actions = FileActionResolver.resolve(app, noPreferred)
        assertTrue(actions.isEmpty())
    }

    // --- .exe / .msi ---

    @Test
    fun `exe file offers RunWindowsBinary in the preferred VM`() {
        val exe = tmp.newFile("setup.exe")
        val actions = FileActionResolver.resolve(exe, typicalContext)
        assertEquals(1, actions.size)
        val action = actions.first()
        assertTrue(action is FileAction.RunWindowsBinary)
        action as FileAction.RunWindowsBinary
        assertEquals("win11-vm", action.targetVmId)
    }

    @Test
    fun `msi file offers InstallWindowsMsi (NOT RunWindowsBinary)`() {
        // Phase 103 — `.msi` is a Windows Installer
        // database; the right tool is `msiexec /i`,
        // not `wine`. The resolver must return
        // InstallWindowsMsi so the FileActionSheet
        // labels the action accurately.
        val msi = tmp.newFile("office.msi")
        val actions = FileActionResolver.resolve(msi, typicalContext)
        assertEquals(1, actions.size)
        val action = actions.first()
        assertTrue(
            "expected InstallWindowsMsi, got ${action::class.simpleName}",
            action is FileAction.InstallWindowsMsi
        )
        // It must NOT be a RunWindowsBinary — the
        // command path is different.
        assertFalse(action is FileAction.RunWindowsBinary)
    }

    @Test
    fun `msi action carries the file path and target VM id`() {
        val msi = tmp.newFile("office.msi")
        val actions = FileActionResolver.resolve(msi, typicalContext)
        val action = actions.first() as FileAction.InstallWindowsMsi
        assertEquals(msi.absolutePath, action.msiPath)
        assertEquals(typicalContext.preferredWindowsVmId, action.targetVmId)
    }

    @Test
    fun `exe file offers nothing when no preferred VM is set`() {
        val noVm = typicalContext.copy(preferredWindowsVmId = null)
        val exe = tmp.newFile("setup.exe")
        val actions = FileActionResolver.resolve(exe, noVm)
        assertTrue(actions.isEmpty())
    }

    // --- Disk images ---

    @Test
    fun `iso file offers both mount and boot actions`() {
        val iso = tmp.newFile("windows10.iso")
        val actions = FileActionResolver.resolve(iso, typicalContext)
        assertEquals(2, actions.size)
        assertTrue(actions.any { it is FileAction.MountDiskImage })
        assertTrue(actions.any { it is FileAction.BootVmFromImage })
    }

    @Test
    fun `qcow2 file is recognized as QCOW2 format`() {
        val qcow = tmp.newFile("win11.qcow2")
        val actions = FileActionResolver.resolve(qcow, typicalContext)
        assertEquals(2, actions.size)
        val mount = actions.first { it is FileAction.MountDiskImage }
            as FileAction.MountDiskImage
        assertEquals(DiskImageFormat.QCOW2, mount.imageFormat)
    }

    @Test
    fun `img file is recognized as IMG format`() {
        val img = tmp.newFile("raspbian.img")
        val actions = FileActionResolver.resolve(img, typicalContext)
        assertEquals(2, actions.size)
        val mount = actions.first { it is FileAction.MountDiskImage }
            as FileAction.MountDiskImage
        assertEquals(DiskImageFormat.IMG, mount.imageFormat)
    }

    // --- Git clone ---

    @Test
    fun `git file offers GitClone action`() {
        val git = tmp.newFile("repo.git")
        val actions = FileActionResolver.resolve(git, typicalContext)
        assertEquals(1, actions.size)
        assertTrue(actions.first() is FileAction.GitClone)
    }

    @Test
    fun `git file with URL on first line is cloned to parent directory`() {
        val git = tmp.newFile("repo.git")
        git.writeText("https://github.com/elysium-vanguard/repo.git")
        val actions = FileActionResolver.resolve(git, typicalContext)
        // The handler reads the URL, not the resolver.
        // The resolver returns the file path as a
        // placeholder; the handler validates.
        val clone = actions.first() as FileAction.GitClone
        assertEquals(git.absolutePath, clone.repoUrl)
        assertEquals(git.parentFile?.absolutePath, clone.destinationDir)
    }

    // --- Network share descriptors ---

    @Test
    fun `smb file offers MountNetworkShare with SMB protocol`() {
        val smb = tmp.newFile("server.smb")
        smb.writeText("smb://server/share")
        val actions = FileActionResolver.resolve(smb, typicalContext)
        assertEquals(1, actions.size)
        val mount = actions.first() as FileAction.MountNetworkShare
        assertEquals(NetworkProtocol.SMB, mount.protocol)
    }

    @Test
    fun `webdav file offers MountNetworkShare with WEBDAV protocol`() {
        val webdav = tmp.newFile("nextcloud.webdav")
        val actions = FileActionResolver.resolve(webdav, typicalContext)
        assertEquals(1, actions.size)
        val mount = actions.first() as FileAction.MountNetworkShare
        assertEquals(NetworkProtocol.WEBDAV, mount.protocol)
    }

    // --- USB OTG ---

    @Test
    fun `usbotg file offers InspectUsbOtgDevice action`() {
        val usb = tmp.newFile("device.usbotg")
        usb.writeText("/dev/block/sda1")
        val actions = FileActionResolver.resolve(usb, typicalContext)
        assertEquals(1, actions.size)
        assertTrue(actions.first() is FileAction.InspectUsbOtgDevice)
    }

    // --- Unknown extensions ---

    @Test
    fun `unknown file extension returns no actions`() {
        val txt = tmp.newFile("readme.txt")
        val actions = FileActionResolver.resolve(txt, typicalContext)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `extension matching is case insensitive`() {
        val deb = tmp.newFile("test.DEB")
        val actions = FileActionResolver.resolve(deb, typicalContext)
        assertEquals(1, actions.size)
    }

    // --- Image format helpers ---

    @Test
    fun `DiskImageFormat fromExtension returns the right format`() {
        assertEquals(DiskImageFormat.ISO, DiskImageFormat.fromExtension("iso"))
        assertEquals(DiskImageFormat.ISO, DiskImageFormat.fromExtension("ISO"))
        assertEquals(DiskImageFormat.IMG, DiskImageFormat.fromExtension("img"))
        assertEquals(DiskImageFormat.QCOW2, DiskImageFormat.fromExtension("qcow2"))
        assertEquals(DiskImageFormat.QCOW2, DiskImageFormat.fromExtension("qcow2c"))
        assertNull(DiskImageFormat.fromExtension("bin"))
    }

    @Test
    fun `NetworkProtocol fromUrl returns the right protocol`() {
        assertEquals(NetworkProtocol.SMB, NetworkProtocol.fromUrl("smb://server/share"))
        assertEquals(NetworkProtocol.SMB, NetworkProtocol.fromUrl("cifs://server/share"))
        assertEquals(NetworkProtocol.WEBDAV, NetworkProtocol.fromUrl("webdav://server/share"))
        assertEquals(NetworkProtocol.SFTP, NetworkProtocol.fromUrl("sftp://server/share"))
        assertNull(NetworkProtocol.fromUrl("https://server/share"))
    }
}
