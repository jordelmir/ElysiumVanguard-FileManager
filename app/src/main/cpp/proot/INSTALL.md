# Cross-compiling libproot.so for Elysium Vanguard

**Phase 9.6.4** — `libproot.so` is the missing piece between the jailed shell
(Phase 9.6.3) and "real Debian". This document explains what the wire-up is
on our side and how to provide a `.so` that we can load.

## What we already have

- `NativeProotLauncher` (`core/runtime/distros/launcher/NativeProotLauncher.kt`)
  builds the proot command line (`proot -0 -r <rootfs> -b ... /bin/sh`).
- `ProotNativeLibrary` (`core/runtime/distros/launcher/ProotNativeLibrary.kt`)
  looks up the .so across bundled / user-installed / Termux locations.
- `FilesystemBridge` describes the bind mounts we want proot to apply
  (`-b <host>:<guest>`).
- `TerminalSession.forDistro(rootfs, pick)` will hand the proot command
  straight into `ProcessBuilder` once the launcher reports available.

We don't currently exec proot because there is no `libproot.so` on disk; we
don't currently JNI-load the binary. This document closes both gaps.

## What proot is

`proot` is a user-space `chroot`/`pivot_root` implementation that does not
require root. It is licensed GPLv2 and its source lives at
<https://github.com/termux/proot>. It exposes a single C entry point:

```c
int proot_main(int argc, char *argv[]);
```

…which means a JNI shim is small (one declaration plus the
`System.loadLibrary("proot")` call). We do NOT need to wrap the whole
binary — we use proot as the existing CLI tool, just invokable through
JNI when bundled.

## Build environment

On macOS or Linux:

```bash
# Install Android NDK (r26+ recommended)
brew install --cask android-ndk          # macOS
# or download from https://developer.android.com/ndk/downloads

# Install proot build deps (none at runtime; libtalloc is the only
# build-time dep). Termux's proot is statically linked against talloc,
# so no separate .so needed.
brew install autoconf automake libtool gettext pkg-config   # macOS
```

## Cross-compile proot for each ABI

We support four ABIs in priority order:

| ABI | Use case |
|---|---|
| `arm64-v8a` | 99% of modern Android phones (8.0+) |
| `armeabi-v7a` | Older phones / IoT / wide-compat (API 26+) |
| `x86_64` | Emulators, Chromebooks |
| `x86` | 32-bit emulators only |

For each ABI, do the following:

```bash
# 1. Clone proot
git clone https://github.com/termux/proot.git
cd proot
git checkout v5.3.0   # pin a known-good version

# 2. Bootstrap autotools if needed
./autogen.sh          # only needed once per source tree

# 3. Configure for the target ABI (using NDK's clang wrapper)
NDK=/path/to/ndk
SYSROOT=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot     # adjust to your host

# arm64-v8a
CC="clang --target=aarch64-linux-android26 --sysroot=$SYSROOT" \
CFLAGS="-O2 -fPIC" \
LDFLAGS="-static -static-libgcc" \
./configure --host=aarch64-linux-android \
            --prefix=$(pwd)/build/arm64-v8a \
            --disable-shared --enable-static
make -j8
make install DESTDIR=$(pwd)/build/arm64-v8a/install

# 4. Copy the binary + .so (if any) into a per-ABI staging dir
mkdir -p staging/arm64-v8a
cp build/arm64-v8a/install/libexec/proot staging/arm64-v8a/libproot.so
# Or if proot was built as a real .so:
# cp build/arm64-v8a/install/lib/libproot.so staging/arm64-v8a/libproot.so

# (Repeat for armeabi-v7a / x86_64 / x86 by changing the --target flag.)
```

> **Why `--enable-static`**: proot's runtime support libraries (libtalloc)
> are small enough to fold in. A single static `libproot.so` keeps the APK
> simpler than juggling deps.

## Drop the result into the APK

Once you have per-ABI `libproot.so`:

```
app/src/main/jniLibs/
├── arm64-v8a/libproot.so
├── armeabi-v7a/libproot.so
├── x86_64/libproot.so
└── x86/libproot.so
```

Then `./gradlew assembleDebug` will pick them up via Android Gradle
Plugin's `mergeNativeLibs` task. After install, they live at
`<dataDir>/lib/<abi>/libproot.so` and `ProotNativeLibrary` will pick
them up automatically on launch.

> **Note**: when bundling real native libs the JNI loader needs to know
> the symbol name. We use `proot_main` (the regular C entry point).
> JNI shim is in `NativeProotLauncherNativeBridge.kt` once a binary is
> present; otherwise the wire-up is purely the "build shell command"
> variant that runs `proot` as a regular CLI binary path (Termux
> install).

## Quick smoke test

After installing a debug APK with the bundled `.so`:

1. Install **Alpine** Linux (60 MB).
2. Open the runtime catalog → Alpine "Open" → terminal launches.
3. The title should now read **"Elysium Terminal · proot"** instead of
   "· jailed".
4. Inside the shell, `pwd` reports `/`, not `<rootfs-path>`.
5. `which apk` returns `/sbin/apk` (because `/sbin/apk` from inside the
   rootfs is now visible to the shell).
6. `apk add curl` downloads curl from inside Alpine's repos.

If that works, proot is fully wired.

## When the .so is NOT present

Until you (Jor) commit the `.so` files under `jniLibs/`, the launcher
keeps working as the **jailed shell** (Phase 9.6.3). No crash, no
regressions: just the same `ls /` over `filesDir/distros/<id>/rootfs/`
behavior we shipped in 9.6.3.

## What changes once we have proot

| Capability | Jailed shell (now) | With proot (9.6.4) |
|---|---|---|
| Read rootfs files (`cat etc/os-release`) | ✅ | ✅ |
| Run shell scripts (`./script.sh`) | ✅ | ✅ |
| Run ELF binaries (`/bin/ls`, `/bin/bash`) | ❌ | ✅ |
| `/sdcard` bind mount | ❌ | ✅ |
| `/elysium/vault` bind mount | ❌ | ✅ |
| `apt install python3` | ❌ | ✅ (with debian-mini) |
| `apk add python3` | ❌ | ✅ (with alpine) |
| `pacman -S python` | ❌ | ✅ (with arch) |
| Run GUI apps over VNC | ❌ | ✅ (Phase 9.6.5) |
