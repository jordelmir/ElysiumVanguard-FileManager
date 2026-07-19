# Phase 74 — Elysium Linux Distro Listing

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 74 / Market integration
> **Predecessor:** Phase 73 (Linux foundation, all 3 halves)
> **Vertical:** Elysium Linux + Market (`com.elysium.vanguard.core.runtime.market`)

---

## TL;DR

The Elysium Linux distro is now **discoverable through the
Market**. The new `ElysiumLinuxDistroListing` is the typed
reference to the first-party proprietary distro — the listing
is the **distribution contract** (what's in the catalog), and
the Capsule (Phase 68) is the **runtime contract** (how the
orchestrator runs it).

The new listing is a **sibling** of the legacy
`ElysiumVanguardDistroListing` (Phase 60, the Debian-based
distro). The two are distinct:

| Listing | Publisher | Type | Era |
| --- | --- | --- | --- |
| `ElysiumVanguardDistroListing` | `publisher:elysium-vanguard` | Debian-based | Phase 1-9 |
| `ElysiumLinuxDistroListing` (this) | `publisher:elysium-linux` | First-party proprietary | Phase 73+ |

A user picks **one** at install time. The Market catalog
exposes both.

---

## What shipped

### `ElysiumLinuxDistroListing` (object)

The typed reference to the Elysium Linux distro in the Market
catalog. The object has:

- **`PUBLISHER_ID`** — `"publisher:elysium-linux"` (the
  publisher identity; matches the listing's signature key).
- **`VERSION`** — `"1.0.0"` (the current version; bumped on
  new image publication).
- **`ID`** — `"com.elysium.linux:distro:1.0.0"` (the catalog
  id; follows the `<group>:<name>:<version>` format).
- **`NAME`** — `"Elysium Linux"` (the display name).
- **`ROOTFS_VERSION`** — `ElysiumRootfsVersion(1, 0, 0)` (the
  rootfs version; image filename is
  `rootfs-v1.0.0.tar.zst`).
- **`CONTENT_HASH`** — placeholder for the real image
  (a non-blank value; the real hash is set when the rootfs
  is built).
- **`SIZE_BYTES`** — 800 MB (smaller than the legacy
  Debian-based distro because the minimal rootfs + runtime
  layer tarballs are smaller than a full Debian base).
- **`TAGS`** — the search filter tags (e.g.
  `elysium-linux`, `first-party`, `proprietary`,
  `mesa-turnip`, `box64`, `fex`, `wine`).
- **`DEPENDENCIES`** — empty (the distro is self-contained;
  the runtime layers + the package manager are bundled in
  the image).
- **`INCLUDED_RUNTIME_LAYERS`** — the 5 default layers from
  Phase 73 third half I-73.3.1: `native`, `mesa-turnip`,
  `box64`, `fex`, `wine`.
- **`PACKAGE_MANAGER`** — `"elysium-pm"` (the canonical
  package manager for Elysium Linux, per Phase 73 second
  half).
- **`CVE_POLICY_SUMMARY`** — the distro's standard
  commitment (per Phase 73 third half I-73.3.5): CRITICAL
  24h/0h, HIGH 7d/24h, MEDIUM 30d/7d, LOW 90d/30d.
- **`draft()`** — factory method that returns a
  `MarketListingDraft` for the listing.

### The draft factory

```kotlin
fun draft(): MarketListingDraft = MarketListingDraft(
    id = ID,
    name = NAME,
    type = MarketListingType.DISTRO,
    version = VERSION,
    contentHash = CONTENT_HASH,
    sizeBytes = SIZE_BYTES,
    dependencies = DEPENDENCIES,
    tags = TAGS,
)
```

The `MarketListingDraft` is the **unsigned** version of the
listing; the publisher signs the draft at publish time
(Phase 60's `MarketPublisher`).

### Tags as search filters

The tags are how the Market search filters listings. The
Elysium Linux tags include:

- `linux` — every Linux distro.
- `elysium-linux` — the canonical namespace tag.
- `first-party` — distinguishes from community distros.
- `proprietary` — distinguishes from Debian / Ubuntu
  derivatives.
- `arm64` — the target ABI.
- `runtime-layers` — the distro's distinctive feature
  (Mesa/Turnip/Box64/FEX/Wine).
- `mesa-turnip`, `box64`, `fex`, `wine` — the specific
  runtime layers.

A user can search for "first-party" to find Elysium Linux +
other first-party distros; or search for "wine" to find
Elysium Linux + any other distro with Wine.

---

## Design decisions

### Why a sibling listing, not a replacement of the legacy one?

The legacy `ElysiumVanguardDistroListing` (Phase 60) is the
**Debian-based** distro — the Phase 1-9 placeholder. The new
`ElysiumLinuxDistroListing` is the **first-party
proprietary** distro — the Phase 73+ real distro.

The two serve **different purposes**:

- The legacy listing is for users who want a familiar
  Debian-based environment (apt/dpkg, standard Linux
  tooling).
- The new listing is for users who want the Elysium Linux
  vision — the proprietary distro with the runtime layer
  system, the signed package manager, the FHS-compliant
  rootfs layout.

Replacing the legacy listing would break existing users
who installed the Debian-based distro. Sibling listings let
both exist; the user picks at install time.

### Why include the runtime layers as tags?

The runtime layers are the **distinctive feature** of
Elysium Linux. A user who wants Mesa/Turnip specifically
should be able to search for it; a tag like `mesa-turnip`
makes the listing findable.

The tags are also the **machine-readable** description of
the listing's capabilities. A future Phase 7+ increment can
build a recommendation engine that matches user
preferences (e.g. "I want a distro with Wine") to listings
with matching tags.

### Why is the package manager name a constant, not a
`MarketListingDraft` field?

The `MarketListingDraft` type is the **typed contract**
between the publisher + the catalog. The draft doesn't have
a `packageManager` field — the draft's fields are
distribution-level (id, name, type, version, contentHash,
sizeBytes, dependencies, tags).

The `PACKAGE_MANAGER` constant is the **Elysium Linux
specific metadata** that the listing exposes for UI
display + for the runtime to read. The constant is on
`ElysiumLinuxDistroListing` (the listing object) not on
the draft type (the catalog contract).

A future Phase 7+ increment can promote the package
manager to a `MarketListingDraft` field if multiple
distros need to declare different package managers.

### Why the `rootfs-v1.0.0.tar.zst` image format?

The image format is **zstd-compressed tarball** (`.tar.zst`).
Zstd is faster to decompress than gzip (typical 3-5x speedup
on Android ARM64) and produces smaller images (~10% smaller
than gzip at the same compression level).

The image is **content-addressed** by SHA-256 of the
decompressed tarball (the `ElysiumPackageManifest.contentHash`).
A new image is a new content hash; a downgrade is a hard
rejection (the `ElysiumPackageVerificationError` envelope).

### Why is the listing's `contentHash` a placeholder?

The real Elysium Linux rootfs is built in a future Phase 73
increment (the **real binaries** — minimal rootfs + Mesa/Turnip
+ Box64/FEX + Wine). Until then, the `contentHash` is a
non-blank placeholder that the Market catalog can store
without rejecting the listing as malformed.

The placeholder pattern matches the legacy
`ElysiumVanguardDistroListing.CONTENT_HASH` —
`ContentHash.of("elysium-linux-distro-placeholder")` — a
typed non-blank value that the real image will replace.

---

## Tests

21 new tests in `ElysiumLinuxDistroListingTest`. The tests
cover:

- **Listing identity** (4 tests): publisher id,
  distribution id (catalog format), distribution name,
  distribution version.
- **Rootfs version** (3 tests): semver components, canonical
  form, image file name.
- **Content + size** (2 tests): non-blank content hash,
  800 MB size.
- **Tags** (5 tests): include `elysium-linux`,
  `first-party`, `proprietary`, the 4 runtime layer markers;
  do NOT include `debian-based` (Elysium Linux is not
  Debian-based).
- **Runtime layers + package manager** (3 tests): included
  layers match Phase 73 defaults, package manager is
  `elysium-pm`, CVE policy summary mentions every severity.
- **Dependencies** (1 test): empty list.
- **draft() factory** (1 test): returns a valid draft with
  the listing's fields.
- **Distinct from legacy listing** (1 test): different
  publisher + id + name.
- **Data class equality** (1 test): rootfs version is equal
  to an equivalent instance.

**Total market tests:** 78 (11 signing + 13 community +
12 round-trip + 21 catalog + 21 Elysium Linux).
**Total project tests:** 2878 (was 2857, +21 new).

---

## What's next — Phase 74 second half

`ElysiumLinuxCapsule` — the Capsule (the runtime contract)
for the Elysium Linux distro. The Capsule is the typed
manifest that the orchestrator reads to launch the distro:

- `runtime: Runtime.LINUX`
- `architecture: Architecture.ARM64`
- `distribution: Distribution("com.elysium.linux:distro:1.0.0")`
  (the same id as the listing)
- `entrypoint.executable: "/usr/bin/elysium-pm"`
- `entrypoint.args: ["init"]` (the package manager init
  command)
- `gpu: GpuConfig(api = GpuApi.VULKAN, driver = GpuDriver.TURNIP)`
  (the distro's default GPU config)
- `permissions: Permissions(network = true, storage = [])`
  (the distro needs network for the package manager; the
  storage is per-workspace, declared at workspace
  creation time)

The Capsule is what the orchestrator consumes; the listing
is what the catalog exposes. The two are linked by
`distribution.id`.

A future Phase 74 third half is the **Capsule installer UI**
(Compose) — the user-facing screen where the user picks
Elysium Linux from the catalog + sees the listing details
+ clicks "Install".

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/runtime/market/ElysiumLinuxDistroListing.kt` | new | Elysium Linux listing (typed reference + draft factory) |
| `app/src/test/java/com/elysium/vanguard/core/runtime/market/ElysiumLinuxDistroListingTest.kt` | new | 21 JVM tests |

---

## The role in the bigger picture

The Elysium Linux listing is the **bridge** between the
distro's foundation (Phase 73) and the existing market
infrastructure (Phases 59-65). The bridge makes the new
distro:

- **Discoverable** — searchable through the Market's tag
  system.
- **Installable** — the `MarketInstaller` (Phase 60) reads
  the listing + downloads the image + verifies the hash.
- **Updatable** — a new version of the listing (e.g.
  `1.0.0` → `1.1.0`) is a new `MarketListing` + the
  catalog replaces the old listing.
- **Comparable** — the user can compare Elysium Linux to
  the legacy Debian-based distro side by side.

The bridge is **the missing piece** between "the distro is
specified" and "the distro is shipped". The Phase 73
foundation is the specification; the Phase 74 listing is
the first step toward shipping.
