package com.elysium.vanguard.core.runtime.distros.bundled

import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class DebugTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun debugExtract() {
        val assetFile = File("src/main/assets/distros/alpine-mini-aarch64.tar")
        val base = tmp.newFolder("base")
        val extractor = BundledRootfsExtractor(base)
        val distro = BundledDistroRegistry.ALPINE_MINI_AARCH64
        val source = FileBundledRootfsSource(assetFile)
        val rootfs = extractor.ensureExtracted(distro, source)
        println("ROOTFS=$rootfs")
        println("EXISTS=${rootfs.exists()}")
        rootfs.walkTopDown().forEach { f ->
            if (f.name in listOf("sh", "ash", "cat", "busybox", "bin")) {
                println("  $f exists=${f.exists()} isFile=${f.isFile} isDir=${f.isDirectory} isSymlink=${java.nio.file.Files.isSymbolicLink(f.toPath())}")
            }
        }
    }
}
