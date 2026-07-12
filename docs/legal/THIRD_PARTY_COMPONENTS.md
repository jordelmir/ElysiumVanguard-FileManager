# Third-party component inventory

**Status:** Phase 0 direct-dependency baseline
**Last verified:** 2026-07-12
**Release status:** blocked pending the actions in the compatibility matrix.

This is an engineering inventory, not legal advice. It covers bundled native
artifacts and direct Gradle declarations. A generated transitive SBOM, notice
bundle and legal review are required before public distribution.

## Bundled native runtime artifacts

All four files are packaged only for `arm64-v8a` under
`app/src/main/jniLibs/arm64-v8a/`.

| APK artifact | Role | Version/source | File SHA-256 | License |
|---|---|---|---|---|
| `libproot.so` | PRoot PIE executable, invoked as a child | Termux `proot` 5.1.107.84; upstream tag `v5.1.107.84` | `c060d7d6f51595b21e7ee55ba7a93b0d9d0f907327c96a52acfcf2d4ba105a62` | GPL-2.0-only per Termux recipe |
| `libproot_loader.so` | PRoot loader executable | same source/package | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` | GPL-2.0-only |
| `libtalloc2.so` | dynamically required by PRoot; SONAME renamed from `libtalloc.so.2` | talloc 2.4.3 | `f4127f767725313190e947591f4b98406ddd23993a76ee912b91d620061dd66e` | LGPL-3.0-or-later in upstream library headers |
| `libandroid-shmem.so` | Android shared-memory compatibility library | Termux/libandroid-shmem 0.7 | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` | BSD-3-Clause |

Package/source receipts and source archive hashes are recorded in
`app/src/main/assets/third_party/PROOT_RUNTIME_NOTICE.md`. License texts are
packaged alongside that notice.

`objdump -p` confirms that the packaged PRoot executable needs
`libtalloc2.so`, `libandroid-shmem.so` and `libc.so`; it also retains a Termux
RUNPATH. The runtime supplies the packaged dependencies through Android's
native library directory.

### Native provenance actions still required

- preserve the exact Termux package archives or a reproducible rebuild recipe;
- version the SONAME/dependency rewrite procedure and all applied patches;
- publish or accompany complete corresponding source in a GPL-compliant form;
- provide the LGPL relink/replace mechanism and unmodified library form as
  required by the selected compliance path;
- generate a CycloneDX or SPDX SBOM containing hashes and relationships;
- verify copyrights and notices against the exact source snapshots.

## Downloaded rootfs artifacts

Rootfs archives are not inside the APK. The app downloads them after an
explicit install action and verifies the catalog SHA-256 before extraction.

| Catalog ID | Artifact | SHA-256 |
|---|---|---|
| `debian-stable` | Termux proot-distro Debian Trixie ARM64 v4.29.0 | `3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918` |
| `ubuntu-noble` | Ubuntu Base 24.04.4 ARM64 | `04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2` |
| `alpine-latest` | Alpine minirootfs 3.24.0 ARM64 | `4b8cd66a6688b2a87276c39843ed89c3a06d9534fc6a5823c586aff2696c1f2a` |
| `arch-arm` | Termux proot-distro Arch ARM64 v4.34.2 | `dabc2382ddcb725969cf7b9e2f3b102ec862ea6e0294198a30c71e9a4b837f81` |

Each rootfs contains many independently licensed packages. Before Elysium
hosts, mirrors or preinstalls one, generate its package-level SBOM and preserve
all required source/notice offers. User-initiated downloads from upstream do
not authorize Elysium to redistribute the archive.

## Direct Android/JVM dependencies

The following declarations come from `app/build.gradle.kts`. “Expected” is a
triage label and must be replaced by evidence from the resolved artifact before
release.

| Component family | Pinned version | Expected license/status |
|---|---:|---|
| AndroidX Core, Lifecycle, Activity, Navigation, Compose, Hilt integration, Room, DocumentFile, WorkManager | explicit versions plus Compose BOM `2024.02.02` | Apache-2.0 expected; transitive inventory pending |
| Dagger Hilt | `2.48` | Apache-2.0 expected |
| MediaPipe Tasks GenAI | `0.10.32` | Apache-2.0 expected; model/content terms must be inventoried separately |
| Coil Compose/Video | `2.5.0` | Apache-2.0 expected |
| AndroidX Media3 | `1.2.0` | Apache-2.0 expected |
| Gson | `2.10.1` | Apache-2.0 expected |
| Tink Android | `1.13.0` | Apache-2.0 expected |
| ZXing Core | `3.5.3` | Apache-2.0 expected |
| Apache MINA SSHD/SFTP | `2.10.0` | Apache-2.0; NOTICE preservation required |
| Google ML Kit text recognition | `16.0.1` | proprietary Google artifact terms; manual review required |
| Google ML Kit image labeling | `17.0.8` | proprietary Google artifact terms; manual review required |
| Apache Commons Compress | `1.26.0` | Apache-2.0; NOTICE preservation required |
| XZ for Java | `1.10` | public-domain dedication expected; verify artifact |
| zstd-jni | `1.5.6-1` | BSD-style expected; native source and notices pending |
| JUnit / AndroidX Test / Espresso | pinned test versions | test-only; Apache/EPL licenses to inventory in build SBOM |

## Distribution sources

- PRoot source: https://github.com/termux/proot/tree/v5.1.107.84
- Termux package definitions: https://github.com/termux/termux-packages
- talloc source archive: https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
- libandroid-shmem source: https://github.com/termux/libandroid-shmem/tree/v0.7
- GNU GPLv2 terms: https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
- GNU LGPLv3 terms: https://www.gnu.org/licenses/lgpl-3.0.html
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

## Update procedure

For every dependency or rootfs change:

1. pin version/commit and verify the artifact hash;
2. record origin, build recipe, patches and relationship (exec, dynamic link,
   JNI, Java link, downloaded content);
3. add exact license and NOTICE evidence;
4. run compatibility review before merging;
5. regenerate SBOM and in-app notices;
6. verify release APK contents rather than trusting declarations alone.
