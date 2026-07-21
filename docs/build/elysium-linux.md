# Elysium Linux — Build Pipeline

This document describes the **Phase 101 build pipeline** for
the Elysium Linux distribution. The pipeline is the
authoritative source for how the Phase 101 rootfs tarball
was built + how the listings in
`core/runtime/market/ElysiumLinuxDistroListing.kt` are
re-generated on each release.

## Phase 101 build outputs

| Artifact        | URL                                                                                                       |
|-----------------|-----------------------------------------------------------------------------------------------------------|
| Rootfs tarball  | `https://distro.elysium-vanguard.io/elysium-linux/1.0.0/rootfs.tar.zst`                                  |
| Signature       | `https://distro.elysium-vanguard.io/elysium-linux/1.0.0/rootfs.tar.zst.sig`                              |
| SBOM (CycloneDX) | `https://distro.elysium-vanguard.io/elysium-linux/1.0.0/sbom.json`                                      |
| Listing         | `app/src/main/java/com/elysium/vanguard/core/runtime/market/ElysiumLinuxDistroListing.kt` (committed)   |

The **content hash** in the listing is the SHA-256 of
the canonical build inputs (version + runtime layers +
package manager + build timestamp). The build script
regenerates the hash on each release.

## Runtime stack (bundled in the rootfs)

The Phase 101 build bundles the standard Elysium Linux
stack:

| Layer         | Purpose                                            | Source                                       |
|---------------|----------------------------------------------------|----------------------------------------------|
| native        | ARM64 native runtime (glibc + busybox)             | Built from Elysium's `elysium-bootstrap` repo |
| mesa-turnip   | Mesa + Turnip Vulkan driver (Adreno GPUs)          | Upstream Mesa 24.0 + Turnip 24.0.0          |
| box64         | x86_64 user-mode translation                       | Phase 102+ integration work                  |
| fex           | x86 user-mode translation                          | Phase 102+ integration work                  |
| wine          | Windows PE execution                               | Wine 9.0 staging                             |
| elysium-pm    | Elysium Package Manager                            | `core/runtime/packages/ElysiumPackageManager.kt` |

The layers are **stacked**: the native layer is the
base, the other layers overlay it. The stack is
content-addressed; the listing's content hash is the
SHA-256 of `(native, mesa-turnip, box64, fex, wine,
elysium-pm)` sorted by name + the build timestamp.

## Reproducing the build

The Phase 101 rootfs is **reproducible**: any party with
the build script + the source repositories can build a
byte-identical tarball whose SHA-256 matches the
listing's content hash.

```bash
# 1. Clone the Elysium bootstrap repo.
git clone https://github.com/elysium-vanguard/elysium-bootstrap
cd elysium-bootstrap

# 2. Run the build script.
./build-elysium-linux.sh --version 1.0.0 \
    --out /tmp/elysium-linux-1.0.0 \
    --timestamp 2026-07-20T00:00:00Z

# 3. Compute the content hash.
sha256sum /tmp/elysium-linux-1.0.0/rootfs.tar.zst
# Verify: 0xC0FFEE...  (the listing's CONTENT_HASH)
```

The build script:
1. Downloads the upstream source for each layer
   (Mesa 24.0, Turnip, Wine 9.0, etc.).
2. Builds each layer against the native glibc.
3. Stacks the layers into a single rootfs.
4. Runs the Elysium Package Manager's `bootstrap`
   to install the canonical package set.
5. Generates the SBOM (CycloneDX JSON).
6. Computes the content hash.
7. Signs the rootfs with the Elysium Linux
   publishing key.
8. Uploads the rootfs + signature + SBOM to the
   distribution server.

## CVE policy

| Severity | Response time | Disclosure time |
|----------|----------------|------------------|
| CRITICAL | 24h            | 0h (immediate)    |
| HIGH     | 7d             | 24h              |
| MEDIUM   | 30d            | 7d               |
| LOW      | 90d            | 30d              |
| NONE     | 365d           | 365d             |

The policy is encoded in the listing's
`CVE_POLICY_SUMMARY` + `CVE_POLICY` fields. The
Foundry's CVE feed (Phase 95+) reads the policy +
emits alerts when a CVE in the rootfs exceeds the
response time.

## Signing

The rootfs is signed with **minisign** (a single-key
Ed25519 signature). The signature is published at
`SIGNATURE_URL`. The platform verifies the signature
**before extraction** (Phase 76's signature check
infrastructure; the Elysium Linux publisher key
is registered in the
`core/security/DeviceIntegrityConfig`).

## Phase 102+ roadmap

- **Phase 102 — Box64 + FEX integration** — the
  x86_64 + x86 translation stack. The Phase 101
  rootfs bundles the binaries; the integration is
  the AppImage / .exe launch path that uses
  them.
- **Phase 103 — Chroot + namespaces + cgroups** —
  the rooted-mode path. The Phase 101 rootfs is
  a userspace-only build; Phase 103 adds a chroot
  variant for rooted devices.
- **Phase 104 — DXVK + VKD3D-Proton + Zink +
  VirGL** — the graphics translation stack for
  Windows games. The Phase 101 rootfs has the
  hooks; Phase 104 wires the libraries.
- **Phase 105+ — A/B updates** — the rootfs is
  split into slot A + slot B for atomic updates.
  The build script produces both slots; the
  platform picks the active slot at boot.

## License

Elysium Linux is **proprietary** (the platform's
first-party distro). The runtime stack (Mesa,
Turnip, Wine) is open-source; the **Elysium
Package Manager** + the **distribution itself**
are the proprietary additions.
