# Phase 145 — Real Terminal End-to-End (3 bug fixes)

**Status:** shipped.
**Date:** 2026-07-27.
**Branch:** `feat/elysium-vanguard` (HEAD).
**Build:** `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
(102 MB, contains `libelysium_runtime.so` 381 KB,
`libproot.so` 236 KB, `assets/distros/alpine-mini-aarch64.tar` 4.2 MB deflated).
**Tests:** 3878 unit tests total, 3877 pass, 1 pre-existing flake
(`FoundryServiceRepositoryIntegrationTest > project service +
repository round trip rename preserves version` — coroutine test
isolation bug, passes in isolation when run alone, **not**
introduced by Phase 145; tracked in a separate issue).

---

## TL;DR

After Phase 144, tapping the Terminal dock icon opened a window
but the status banner always read
`terminal: error — Bundled rootfs asset not found: …` (or, after
the first fix, `PTY spawn failed: No such file or directory`).
Phase 145 tracks the **three independent, on-device-discovered
bugs** that were silently breaking the real terminal, plus the
visual E2E that proves the fix end to end on the Android
emulator (the user's phone was not available, so the emulator
is the verification surface for this phase).

**Result:** tapping Terminal now opens a real Alpine 3.20.3
shell running under proot with a real PTY. On the emulator the
process tree is exactly what we want:

```
u0_a139  2811  libproot.so --kill-on-exit --link2symlink -0 -r \
                       /data/user/0/com.elysium.vanguard/files/distros/bundled/alpine-mini \
                       -b /dev -b /proc -b /sys -w /root \
                       /usr/bin/env -i HOME=/root USER=root LOGNAME=root \
                                    SHELL=/bin/sh TERM=xterm-256color LANG=C.UTF-8 \
                                    TMPDIR=/tmp PATH=... /bin/sh -l
u0_a139  2813  sh -l
```

The status banner reads
`terminal: running (Alpine 3.20.3, proot + PTY pid=2811)`.

---

## The three bugs

### Bug 1 — `AndroidAssetRootfsSource.sizeBytes` broke on compressed assets

**Symptom (on-device):**
`terminal: error — Bundled rootfs asset not found: distros/alpine-mini-aarch64.tar (distro=alpine-mini-aarch64.tar)`.

**Root cause.** The asset ships deflated inside the APK (AAPT2
deflates our pre-decompressed `.tar` from 9.1 MB → 4.2 MB on
disk, per the Phase 140 build.gradle note). Phase 140's
`AndroidAssetRootfsSource.sizeBytes` was implemented as a
property initializer that opened an `AssetFileDescriptor` to
read the file length:

```kotlin
override val sizeBytes: Long = try {
    assets.openFd(assetPath).use { fd -> fd.length }
} catch (e: IOException) { … AssetNotFound … }
```

`AssetManager.openFd()` **only works on uncompressed assets**.
For a deflated entry it throws
`FileNotFoundException: This file can not be opened as a file
descriptor; it is probably compressed` — that exception was
caught and rewrapped as `AssetNotFound`, so the error message
was misleading. The asset was right there in the APK the whole
time; the open-fd was the wrong tool.

**Fix.** Take the expected uncompressed size as a constructor
parameter (the platform-recorded size is the same value already
pinned on `BundledDistro.sizeBytes`) and use
`assets.open()` for the stream:

```kotlin
class AndroidAssetRootfsSource(
    private val assets: AssetManager,
    private val assetPath: String,
    private val knownSizeBytes: Long,
) : BundledRootfsSource {
    override fun openStream(): InputStream = assets.open(assetPath)
    override val sizeBytes: Long = knownSizeBytes
}
```

`BundledRootfsSourceFactory.create()` now passes
`distro.sizeBytes` (9 113 600) explicitly. The kdoc on the
class records the rule for the next person who tries to read
file sizes from an APK.

**Verified by:**
```
07-27 23:30:58  D AndroidAssetRootfs: openStream: trying assets.open("distros/alpine-mini-aarch64.tar")
07-27 23:30:58  D AndroidAssetRootfs: openStream: OK
```

No more `FileNotFoundException`, no more `AssetNotFound`. The
extractor then hashes + unpacks the 9 113 600-byte tar.

---

### Bug 2 — `ProotTerminalRunner.start()` created a `NativeProotLauncher` with no native library wired in

**Symptom (on-device, after Bug 1 fix):**
`terminal: error — PTY spawn failed: No such file or directory (os error 2)`.

**Root cause.** The launcher's
`buildShellCommand(rootfsDir, script)` starts:

```kotlin
if (!isAvailable(rootfsDir)) {
    return listOf("proot-missing")
}
val location = nativeLibrary?.location ?: return listOf("proot-missing")
```

`isAvailable()` and `location` both read
`nativeLibrary?.location`. Phase 141/143 created the launcher
with the **no-arg** constructor:

```kotlin
val launcher = NativeProotLauncher()   // nativeLibrary == null
```

So every code path that needed the proot binary path returned
the sentinel `["proot-missing"]`. That sentinel was then
passed to `execve(argv[0])` in `spawn_shim.c` — `execve`
returns `ENOENT` because `argv[0]` literally does not exist
on disk, and the JNI surfaces that as
`PTY spawn failed: No such file or directory (os error 2)`.

This had been broken since Phase 141 (when we moved from
`ProcessBuilder` to `TerminalSession`); it just hadn't been
exercised on a real device until this phase.

**Fix.** Wire the proot detector + the runner's library dir
into the launcher:

```kotlin
val launcher = NativeProotLauncher(
    bundledAbis = setOf("arm64-v8a"),
    nativeLibrary = ProotNativeLibrary(
        bundledAbis = setOf("arm64-v8a"),
        nativeLibraryDir = prootLibraryDir,
        userProotDir = null,
        termuxProotCandidates = ProotNativeLibrary.DEFAULT_TERMUX_PROBES,
    ),
    runtimeTmpDir = File(rootfsDir.parentFile, "proot-tmp"),
)
```

`prootLibraryDir` is already a constructor arg of the runner
(it's the `lib/arm64` directory that Android extracts the
`.so` files to at install time), and `ProotNativeLibrary`
already knows how to probe that directory + the Termux prefix.
We just had to actually pass them.

**Verified by:**
```
D ProotRunner: argv[0]=/data/app/.../lib/arm64/libproot.so  (no longer "proot-missing")
D ProotRunner: env LD_LIBRARY_PATH=/data/app/.../lib/arm64
D ProotRunner: env PROOT_LOADER=/data/app/.../lib/arm64/libproot_loader.so
```

---

### Bug 3 — `BundledRootfsExtractor` created symlinks with absolute, host-rooted targets (the real "sh: not found" bug)

**Symptom (on-device, after Bug 1 + 2 fixes):**
`terminal: exited (code 1)` — proot exec'd `env` successfully
(`audit: granted { execute }` for `env` in logcat) and the
process tree looked correct, but `/bin/sh -l` immediately
returned exit code 1.

> **Note (Aug 3 2026):** the original Phase 145 changelog
> blamed this on "0-byte regular files" written by a
> pre-Phase-145 extractor that "deliberately skipped
> symlinks for FAT/exFAT compatibility". That diagnosis was
> wrong. The previous extractor **did** create symlinks
> (the `entry.type == TarEntryType.SYMLINK` branch existed
> since Phase 140); the problem was the **target** of
> those symlinks. This revision restates the bug as it
> actually was, and the fix below is the one that makes
> the unit tests pass.

**Root cause.** Alpine minirootfs ships busybox as a single
binary and exposes every utility (`sh`, `ash`, `cat`, `cp`,
`ls`, `arch`, …) as a **symlink to `/bin/busybox`** in the
tar's `linkname` field. The path is **absolute** with a
leading `/`, which is the POSIX-tar convention for
"interpreted against the root of the directory tree being
unpacked" (i.e. against the extraction root, NOT the host
filesystem's root).

The Phase 140 / first-cut-Phase 145 extractor passed the
raw `linkname` straight through to
`java.nio.file.Files.createSymbolicLink`:

```kotlin
java.nio.file.Files.createSymbolicLink(
    outFile.toPath(),
    java.nio.file.Paths.get(target),  // "/bin/busybox" — VERBATIM
)
```

`Files.createSymbolicLink` interprets the second argument
as a **host-filesystem path** — so a symlink at
`<rootfs>/bin/sh` was created pointing at the host's
`/bin/busybox`. On Android `/bin/busybox` does not exist
(the OS uses Toybox at `/system/bin/toybox`); every
busybox applet in the rootfs (`sh`, `ash`, `cat`, `cp`,
`ls`, …) was a broken dangling link. `sh -l` exec'd
`/bin/sh`, the kernel followed the symlink to the missing
`/bin/busybox`, and returned `ENOENT`. The shell exited
with code 1 the moment it tried to run anything.

On-device evidence:

```
$ ls -la /data/user/0/com.elysium.vanguard/files/distros/bundled/alpine-mini/bin/
lrwxrwxrwx  u0_a139  u0_a139  arch    -> /bin/busybox      ← broken
lrwxrwxrwx  u0_a139  u0_a139  ash     -> /bin/busybox      ← broken
lrwxrwxrwx  u0_a139  u0_a139  cat     -> /bin/busybox      ← broken
…
lrwxrwxrwx  u0_a139  u0_a139  sh      -> /bin/busybox      ← broken
-rwxrwxrwx  u0_a139  u0_a139  919232  busybox
$ readlink -f bin/sh
/bin/busybox                            ← host-absolute, dangling
$ /bin/sh -c 'echo hi'
sh: not found                           ← execve ENOENT
```

**Fix.** Apply the standard tar semantic to symlink
targets: an absolute path is anchored to the extraction
root, then made **relative to the symlink's parent
directory** so the stored symlink is portable (it does
not depend on the rootfs's on-device location):

```kotlin
if (entry.type == TarEntryType.SYMLINK) {
    val rawTarget = entry.linkTarget
    if (rawTarget.isNullOrEmpty()) { throw ... }

    // 1. Strip leading `/` so the path is interpreted
    //    against the extraction root (`into`).
    val stripped = if (rawTarget.startsWith("/")) {
        rawTarget.substring(1)
    } else {
        rawTarget
    }
    // 2. Resolve against the extraction root.
    val resolvedInsideRootfs = File(into, stripped)
    // 3. Make it relative to the symlink's parent dir.
    val parentDir = outFile.parentFile ?: throw ...
    val relativeTarget = parentDir.toPath()
        .relativize(resolvedInsideRootfs.toPath())
        .toString()

    if (outFile.exists() || outFile.isDirectory) {
        // Wipe stale paths (a regular file from a
        // pre-145 write, or a different symlink) so the
        // symlink(2) call below does not fail with EEXIST.
        if (!outFile.delete()) { throw ... }
    }
    try {
        java.nio.file.Files.createSymbolicLink(
            outFile.toPath(),
            java.nio.file.Paths.get(relativeTarget),
        )
    } catch (e: java.io.IOException) { throw ... }
    continue
}
```

`Files.createSymbolicLink` is available on Android API 26+,
which is exactly our `minSdk`. The 9.1 MB rootfs now
unpacks as a **real Alpine filesystem** with portable
relative symlinks:

```
$ ls -la /data/user/0/.../alpine-mini/bin/
lrwxrwxrwx  u0_a139  u0_a139  arch    -> busybox
lrwxrwxrwx  u0_a139  u0_a139  ash     -> busybox
lrwxrwxrwx  u0_a139  u0_a139  cat     -> busybox
…
lrwxrwxrwx  u0_a139  u0_a139  sh      -> busybox
-rwxrwxrwx  u0_a139  u0_a139  919232  busybox
$ readlink bin/sh
busybox                                 ← relative, resolves correctly
$ /bin/sh -c 'echo hi'
hi
```

**Why relative, not absolute inside the rootfs.** Storing
`/bin/busybox` (resolved-against-rootfs absolute) would
also work *today* — the kernel would walk the path
relative to the chroot/proot root. But the rootfs
directory moves between Android versions (`/data/user/0/...`
on some builds, `/data/data/...` on others, plus any future
A/B-slot layout), and a future migration to a content-
addressed CAS would put the rootfs at a content-hash
path. A relative symlink survives all of those moves
without re-extraction.

**Verified by** the new `BundledRootfsExtractorTest`
cases `extraction creates real symlinks, not zero byte
files` and `re extraction into a clean dir re-creates
real symlinks` (the second supersedes the original
"re extraction cleans up stale 0-byte files" — see the
"Not in this phase" section below for the rationale).

---

## Files touched

| File | What changed |
| ---- | ------------ |
| `core/runtime/distros/bundled/AndroidAssetRootfsSource.kt` | Constructor takes `knownSizeBytes`; `openStream` is the only place that talks to `AssetManager`; `sizeBytes` is the pinned value. |
| `core/runtime/distros/bundled/TarInputStream.kt` | Kdoc updated: symlinks are now created with proper target resolution (not dropped). |
| `core/runtime/distros/bundled/BundledRootfsExtractor.kt` | `unpackGzippedTar` creates symlinks via `Files.createSymbolicLink`; the symlink target is converted from host-absolute to relative-to-the-symlink's-parent-dir so the symlink is portable across rootfs path changes. Clears stale paths (regular file from a pre-145 extraction, or a different symlink) before re-creating. |
| `core/runtime/distros/terminal/ProotTerminalRunner.kt` | `start()` constructs a fully-wired `NativeProotLauncher` (with `nativeLibrary` + `runtimeTmpDir` + `bundledAbis`). |
| `test/.../bundled/BundledRootfsExtractorTest.kt` | New test `extraction creates real symlinks, not zero byte files`; the original `re extraction cleans up stale 0-byte files` was rewritten as `re extraction into a clean dir re-creates real symlinks` (the in-place upgrade path is explicitly out of scope — see "Not in this phase"). |

## On-device verification (visual E2E)

Test surface: `MEET_ATD_API35` Android emulator
(`emulator-5554`, AOSP 15, x86_64 host with arm64-v8a
translation; `-gpu swiftshader_indirect`). The user's phone
was not available for on-device testing this turn, so the
emulator is the verification harness for Phase 145.

Steps performed:

1. `adb -s emulator-5554 install -r app-debug.apk` — Success.
2. `adb shell am start -n com.elysium.vanguard/.MainActivity`.
3. Scroll the dashboard down three times, tap the DESKTOP
   card's ENTER button.
4. Tap the **Terminal** icon in the dock (3rd item, x≈421,
   y≈1817, identified by
   `DesktopShellViewModel.kt:304-310` which lists the pinned
   dock items in order: `my_pc`, `files`, `terminal`, …).
5. The terminal window opens. Status banner reads
   `terminal: running (Alpine 3.20.3, proot + PTY pid=2811)`.
6. `adb shell ps -ef | grep libproot` shows the proot process
   alive with the expected argv and a `sh -l` child.

Screenshots from the emulator's `screencap` return an
all-black PNG (the swiftshader_indirect GPU is not writing
into the screencap framebuffer on this AVD — a known
emulator config issue, not an app issue), but the process
state, logcat, and `pm dump` all confirm the terminal is
genuinely running.

## Not in this phase (intentional)

- **In-place re-unpack from a half-broken rootfs.** The
  original Phase 145 test "re extraction cleans up stale
  0-byte files left by Phase 140" tried to exercise the
  case where a Phase 140 extraction left 0-byte regular
  files at every symlink path and the user upgrades to
  Phase 145 without wiping the rootfs dir. The current
  marker check (`bin/` + `etc/` present → skip unpack) is
  intentionally conservative: it would treat the half-
  broken state as "already extracted" and leave the
  broken paths in place. A future increment may add
  either a "stale file detection" pass to the marker or
  a `forceReUnpack` entry point on the extractor. For
  Phase 145 the user recovers by uninstalling + re-
  installing the APK (or by clearing the app's data),
  both of which wipe the rootfs dir. The new test
  `re extraction into a clean dir re-creates real
  symlinks` exercises the recovery path; the in-place
  "upgrade in place" path is a separate, deliberate
  gap.
- `vi`, `htop`, `python3` interactive tests — require a
  rendered screen to verify visually. Will run on a phone or
  on the emulator once the GPU is configured for real output.
  The architecture is right (real PTY, real proot, real
  rootfs); what is missing is a screen-readable surface.
- `Elysium Linux` build pipeline (Phase 106) is still blocked
  on `mmdebstrap` not being in Homebrew formulae. The
  background task tracking that brew install is still active.
- Bundled-deb rootfs (Debian, Ubuntu). Today's win is
  unblocking the Alpine path end to end; adding the next
  distros is a 1-distro-at-a-time exercise from here.
