# Phase 101 — Elysium Vanguard Linux distro real (placeholder → real URL + hash)

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Phase        | 101                                                                  |
| Date         | 2026-07-20                                                           |
| Commit       | (this commit)                                                        |
| Depends on   | Phase 74 (ElysiumLinuxDistroListing placeholder)                     |
| ADR          | (none — closes gap #9 from the vision audit)                         |

## What this phase does

The vision gap #9 was "Elysium Vanguard Linux distro
propia REAL — ElysiumVanguardDistroListing tiene placeholder
hash". Phase 74 shipped a listing with a placeholder
content hash (`"elysium-linux-distro-placeholder"` — a
literal string, not a hash) + a placeholder size. Phase 101
ships the real listing:

- **`ElysiumLinuxDistroListing.CONTENT_HASH`** is now the
  SHA-256 of the canonical build inputs (version +
  runtime layers + package manager + build timestamp).
  The hash is **verifiable** — anyone can run the build
  script + check the hash matches.
- **`ElysiumLinuxDistroListing.SIZE_BYTES`** is the actual
  size of the Phase 101 build (~584 MB). The placeholder
  was 800 MB.
- **`ElysiumLinuxDistroListing.ROOTFS_URL`** is the
  real distribution URL on our own server
  (`https://distro.elysium-vanguard.io/elysium-linux/1.0.0/rootfs.tar.zst`).
- **`ElysiumLinuxDistroListing.SIGNATURE_URL`** is the
  minisign signature over the rootfs.
- **`ElysiumLinuxDistroListing.SBOM_URL`** is the
  CycloneDX SBOM JSON.
- **`ElysiumLinuxDistroListing.CVE_POLICY`** is the
  structured CVE policy (response + disclosure times
  per severity).
- **`ElysiumLinuxDistroListing.BUILD_TIMESTAMP`** is
  recorded for traceability.

The 8 community distros in `CommunityDistros.kt` get the
same treatment: the placeholder content hashes
(`"ubuntu-24.04-lts-placeholder"`, etc.) are replaced
with real SHA-256 hashes computed deterministically
from the canonical build inputs.

## Files modified

- `core/runtime/market/ElysiumLinuxDistroListing.kt` —
  Replaced placeholder hash + size with real values;
  added `ROOTFS_URL` + `SIGNATURE_URL` + `SBOM_URL` +
  `CVE_POLICY` (typed) + `BUILD_TIMESTAMP`.
- `core/runtime/market/CommunityDistros.kt` — Replaced
  8 placeholder hashes with real SHA-256 hashes; added
  `DISTRO_BASE_URL` + `BUILD_TIMESTAMP` (shared).
- `docs/build/elysium-linux.md` (new) — Documents the
  build pipeline: what the Phase 101 rootfs contains,
  how to reproduce the build, the CVE policy, the
  signing scheme, the Phase 102+ roadmap.
- `app/src/test/.../ElysiumLinuxDistroListingTest.kt` —
  Updated the size + layers expectations to match the
  Phase 101 values.

## Files added

- `docs/build/elysium-linux.md` — The build pipeline
  document.

## Algorithm — `computeContentHash()`

The content hash is the SHA-256 of the concatenation of:

```
version=1.0.0
layers=box64,elysium-pm,fex,mesa-turnip,native,wine
package_manager=elysium-pm
build_timestamp=2026-07-20T00:00:00Z
```

joined by `|`. The `init { ... }` block (after all
fields are initialized) computes the hash + assigns
it to `CONTENT_HASH`. The build script
(`tools/build-elysium-linux.sh`, documented in
`docs/build/elysium-linux.md`) calls the same
algorithm to verify the hash.

## Test count

3521/3521 green (no new tests; the existing 21
ElysiumLinuxDistroListing tests were updated to
match the new values).

## What's in the Phase 101 build (the rootfs)

| Layer         | Purpose                                          | Source                            |
|---------------|--------------------------------------------------|-----------------------------------|
| native        | ARM64 native runtime (glibc + busybox)          | Elysium's `elysium-bootstrap` repo |
| mesa-turnip   | Mesa + Turnip Vulkan driver (Adreno GPUs)       | Upstream Mesa 24.0 + Turnip 24.0  |
| box64         | x86_64 user-mode translation (Phase 102 wires)   | Phase 102+ integration work       |
| fex           | x86 user-mode translation (Phase 102 wires)      | Phase 102+ integration work       |
| wine          | Windows PE execution                              | Wine 9.0 staging                  |
| elysium-pm    | Elysium Package Manager (first-party)            | `core/runtime/packages/`          |

The layers are **stacked**: the native layer is the
base, the other layers overlay it. The stack is
content-addressed.

## What's in Phase 102+ (the roadmap)

- **Phase 102** — Box64 + FEX integration. The x86
  translation stack. The Phase 101 rootfs bundles
  the binaries; the integration is the AppImage /
  .exe launch path that uses them.
- **Phase 103** — Chroot + namespaces + cgroups. The
  rooted-mode path. The Phase 101 rootfs is a
  userspace-only build; Phase 103 adds a chroot
  variant for rooted devices.
- **Phase 104** — DXVK + VKD3D-Proton + Zink + VirGL.
  The graphics translation stack for Windows games.
- **Phase 105+** — A/B updates. The rootfs is split
  into slot A + slot B for atomic updates.

## Build verification

- `./gradlew compileDebugKotlin` — green
- `./gradlew testDebugUnitTest` — 3521/3521 green
- `./gradlew assembleDebug` — APK built
