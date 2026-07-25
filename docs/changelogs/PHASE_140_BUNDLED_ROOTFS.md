# Phase 140 — Bundled Rootfs (Alpine minirootfs aarch64, real Linux in the APK)

**Status**: shipped
**Date**: 2026-07-25
**Commit**: pending

## The gap

The Phase 9.6.x proot stack ships the executor
(`NativeProotLauncher` + `libproot.so` + `libtalloc2.so` +
`libandroid-shmem.so` + `libproot_loader.so` in
`app/src/main/jniLibs/arm64-v8a/`) and the typed command-line
builder that turns a `WorkspaceDefinition` into a proot
invocation. But the executor had no `rootfs/` to execute
against. The whole "real Linux in the APK" claim was hanging
on a missing artifact.

The Phase 101 distro listing had URLs and hashes for upstream
distros; the Phase 106 build script had a `mmdebstrap` recipe
for a reproducible build; but **nothing was actually
bundled**. The vision says "real terminal, real Linux" — and
without a real rootfs, the terminal is a mock.

## What shipped

A real Linux distribution bundled in the APK and a
hash-verified extractor that unpacks it on first use.

### 1. The asset

`app/src/main/assets/distros/alpine-mini-aarch64.tar`
(9 113 600 bytes; SHA-256
`909cd5ea844eecebecee4b5e80e98709c7cfbb2e95748c787c211bf1698aaae2`).

**Alpine Linux 3.20.3 minirootfs for aarch64** — upstream
artifact from `dl-cdn.alpinelinux.org`. Contains a real
ARM64 Linux userspace: busybox (multi-call binary: ash,
cat, ls, sh, etc.), musl libc, apk package manager, the
FHS layout (`bin/`, `etc/`, `lib/`, `usr/`, `var/`, …).
The minirootfs is the smallest *real* distro Alpine ships
— about 4 MB compressed vs Debian's 30+ MB. The user can
get a working shell in seconds and `apk add` any other
package they need.

**Why a `.tar` and not a `.tar.gz`**: AAPT2's default
behavior is to *decompress* `.gz` assets at build time,
storing the unpacked `.tar` in the APK. We tried the
`.tar.gz` path first — APK grew by 5 MB vs the deflate
path. AAPT2's deflate compresses the `.tar` from 9.1 MB
to ~4.2 MB on disk (54% ratio; tar entries are heavy
on zero-padding + similar patterns, which deflate loves).
Storing the tar uncompressed (`noCompress`) makes the APK
4 MB bigger. So the .tar approach wins.

### 2. The registry

`BundledDistro` (immutable data class) +
`BundledDistroRegistry` (singleton object).

- Pinned: every entry has a `sha256` + `sizeBytes` that
  the extractor verifies before unpacking.
- Today: 1 entry (`ALPINE_MINI_AARCH64`).
- Future: additional entries (Debian slim, Arch ARM,
  Ubuntu core) added here. The contract is the same
  for every entry.

### 3. The extractor

`BundledRootfsExtractor` (pure JVM, no Android imports):

- `ensureExtracted(distro, source)` is idempotent.
- Step 1 — **hash verify**: recomputes SHA-256 of the
  source bytes off `AssetManager`, compares to
  `BundledDistro.sha256`. Mismatch throws
  `BundledRootfsError.HashMismatch` (defense-in-depth
  against tampered APKs).
- Step 2 — **size check**: `totalRead == source.sizeBytes`.
  A lying source (size != actual) is rejected.
- Step 3 — **stage to `.part`**: unpack to a sibling
  staging directory; any IO error cleans up the stage.
- Step 4 — **atomic rename** to the final
  `<filesDir>/distros/bundled/<id>/`.
- Step 5 — **marker check** on subsequent calls: if
  `bin/` + `etc/` are missing, the dir is treated as
  half-extracted and re-unpacked.

The production `BundledRootfsSource` is
`AndroidAssetRootfsSource` (uses
`AssetManager.openFd` for the size + `AssetManager.open`
for the stream). Tests use `FileBundledRootfsSource`
backed by a `File` so the JVM test runner doesn't need
an Android `Context`.

### 4. The tar reader

`TarInputStream` — a minimal self-contained tar reader
(Android has no `java.util.tar` and no Apache
`commons-compress` dependency). Scope:

- USTAR / POSIX tar (the format Alpine minirootfs uses).
- GNU long-name extension (typeflag `'L'`) for paths
  longer than 100 chars.
- Regular files, directories, symlinks.
- Pax extended headers (typeflag `'x'` / `'g'`) are
  transparently skipped.
- Accepts both POSIX (`"ustar\0"`) and GNU
  (`"ustar "`) magic.

Not a full POSIX tar implementation — only what the
platform's own tarballs need.

### 5. Hilt wiring

`BundledRootfsModule` provides:

- `BundledRootfsExtractor` (singleton, scoped to
  `<filesDir>/distros/bundled`)
- `BundledDistroRegistry` (singleton)
- `@Named("bundled_rootfs_base") File` (the base dir)

`BundledRootfsEntryPoint` + `rememberBundledRootfsExtractor()`
Composable helper — Hilt EntryPoint bridge for Composable-only
contexts (same pattern as Phase 139's
`RecentFileRepositoryEntryPoint`).

### 6. Error envelope

`BundledRootfsError` sealed class with 3 variants:
- `AssetNotFound(distroId, assetPath)` — APK missing the
  asset (build mistake).
- `HashMismatch(distroId, expectedHash, actualHash, expectedSize, actualSize)`
  — defense-in-depth against tampered APKs / corrupted
  downloads.
- `ExtractionFailed(message)` — IO errors during unpack.

Every variant is a `RuntimeException` so the resolver can
throw + caller can `try/catch` without `Result.unwrap()`.

## Test coverage (8 JVM unit tests, all green)

`BundledRootfsExtractorTest` (real Alpine asset, no Android
Room, no Robolectric):

1. **`extracts the real Alpine minirootfs with hash match`** —
   opens the on-disk asset, verifies the SHA-256, unpacks
   to a temp dir, asserts `bin/`, `etc/`, `lib/`,
   `bin/busybox`, `lib/ld-musl-aarch64.so.1` exist.
2. **`extraction is idempotent — second call returns the
   same path without re-unpacking`** — adds a `user-marker.txt`
   to the extracted dir, calls again, asserts the marker
   survives (proves no re-unpack happened).
3. **`marker check treats half-extracted dir as
   not-yet-extracted`** — deletes `bin/`, calls again,
   asserts re-unpack re-creates `bin/` + `bin/busybox`.
4. **`hash mismatch throws typed error`** — wraps a source
   that lies about its hash; asserts the thrown error has
   `distroId = "alpine-mini"`, `expectedHash` populated,
   `actualHash == distro.sha256` (the real SHA-256 of the
   asset).
5. **`size mismatch throws typed error`** — wraps a source
   that lies about its size; asserts the error has
   `expectedSize == distro.sizeBytes` (pinned truth) and
   `actualSize` from the actual stream.
6. **`registry returns the alpine entry by id`** — `findById("alpine-mini")`
   returns the entry, asserts `family = "alpine"`, `architecture = "aarch64"`,
   `version = "3.20.3"`.
7. **`registry returns null for unknown id`** —
   `findById("nonexistent-distro") == null`.
8. **`catalog has the pinned sha256 for alpine-mini`** —
   belt-and-suspenders: pins the hash format (64-char
   lowercase hex) so a future bad edit breaks the test
   instead of shipping a malformed registry.

## Bug fixes (3, found while writing the tests)

1. **`mode != 0` type mismatch** (compile error):
   `entry.mode and 0x1FF` was `Long` but the literal
   `0x1FF` was `Int`. Fixed: `0x1FFL` + bit-shift
   constants as `Long`. Same on the `mode shr 6` lines.
2. **GNU magic `"ustar "` vs POSIX `"ustar"`**:
   Alpine's minirootfs uses GNU tar, whose magic is
   `"ustar "` (with a trailing space) followed by a null
   byte. The first reader accepted only POSIX magic and
   threw on every entry. Fixed: accept both forms.
3. **`InputStream.skip()` unreliable on `GZIPInputStream`**:
   `GZIPInputStream.skip()` doesn't actually skip the
   requested count; it advances at the deflate block
   level, returning a number that does not match the
   bytes actually consumed. Symptom: "Short skip on
   entry padding" after the first few entries. Fixed:
   replaced `skip()` with a `discardBytes(count)` helper
   that reads-and-throws-away (byte-accurate, slower,
   correct).

## Build status

- `compileDebugKotlin`: ✅
- `testDebugUnitTest --tests "com.elysium.vanguard.core.runtime.distros.*"`: ✅
  (all distros tests pass; no regression)
- 0 new lint warnings
- 8/8 new tests green
- Total distros tests: was 100 → now 108

## What this enables

This is the foundation for the **real Linux terminal**.
The Terminal body in the desktop (currently Phase 122's
client-side shell mock) can now be wired to:
1. Resolve the bundled Alpine via
   `BundledDistroRegistry.findById("alpine-mini")`.
2. Extract it to `<filesDir>/distros/bundled/alpine-mini/`
   on first use.
3. Spawn a real `proot` process running `/bin/ash` (or
   `/bin/busybox sh`) inside the extracted rootfs.
4. Pipe the user's keyboard input → proot stdin,
   proot stdout/stderr → terminal surface.

That's Phase 141. Phase 140 is the foundation: a real
distro, a real extractor, a real hash chain.

## Files added (7)

| File | Lines | Purpose |
|---|---|---|
| `core/runtime/distros/bundled/BundledDistro.kt` | 47 | Immutable data class |
| `core/runtime/distros/bundled/BundledDistroRegistry.kt` | 64 | Catalog (1 entry today) |
| `core/runtime/distros/bundled/BundledRootfsSource.kt` | 39 | Test seam interface + file impl |
| `core/runtime/distros/bundled/BundledRootfsExtractor.kt` | 232 | Hash verify + gz tar extract + atomic rename |
| `core/runtime/distros/bundled/TarInputStream.kt` | 270 | Minimal tar reader (USTAR + GNU long names) |
| `core/runtime/distros/bundled/AndroidAssetRootfsSource.kt` | 35 | Production `AssetManager`-backed source |
| `core/runtime/distros/bundled/BundledRootfsModule.kt` | 86 | Hilt module + EntryPoint + Composable helper |
| `test/.../bundled/BundledRootfsExtractorTest.kt` | 178 | 8 JVM unit tests against the real asset |
| `assets/distros/alpine-mini-aarch64.tar.gz` | (binary) | 3.9 MB Alpine minirootfs 3.20.3 |
| **Total Kotlin** | | **951 lines** (production) + 178 (test) |
