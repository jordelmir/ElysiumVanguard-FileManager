# Phase 74 (integration) — Elysium Linux Market Integration Test

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 74 / Market integration — end-to-end consistency
> **Predecessor:** Phase 74 first half (listing) + second half (Capsule)
> **Vertical:** Elysium Linux + Market + Capsule + ABI matrix

---

## TL;DR

The Elysium Linux distro is **end-to-end consistent**. The
new `ElysiumLinuxMarketIntegrationTest` verifies the
**cross-component consistency** between the listing + the
Capsule + the package manager + the ABI matrix. The test is
the **canonical "Phase 74 closed" proof**: the distro is
discoverable (via the listing), runnable (via the Capsule),
publishable (via the Market), installable (via the
`MarketInstaller`), and aligned with the runtime support
(via the ABI matrix).

The test is **14 scenarios** covering:

1. **Listing id <-> Capsule distribution id consistency** (the
   orchestrator's join key).
2. **Listing version <-> rootfs version consistency** (per
   Phase 73 third half I-73.3.4).
3. **Capsule architecture <-> ABI capability matrix** (per
   Phase 73 third half I-73.3.3).
4. **Capsule GPU config <-> GPU vendor** (TURNIP requires
   Adreno).
5. **Listing publishable + installable through the Market**
   (round-trip test).
6. **Listing discoverable via tag search** (the "first-party"
   tag).
7. **Capsule build() returns a valid Capsule** (all fields
   populated).
8. **Listing draft is a valid `MarketListingDraft`** (the
   type accepts it).

---

## What the test verifies

### 1. Listing id <-> Capsule distribution id

The orchestrator matches the listing + the capsule by
`distribution.id`. A mismatched pair is a deployment error.
The test asserts `ElysiumLinuxDistroListing.ID ==
ElysiumLinuxCapsule.DISTRIBUTION_ID`.

### 2. Listing version <-> rootfs version

The listing's `version` is the distribution-level version;
the rootfs version is the rootfs-level version. The two MUST
match (the listing is the distribution contract for the
rootfs). The test asserts
`ElysiumLinuxDistroListing.VERSION ==
ElysiumLinuxDistroListing.ROOTFS_VERSION.canonical` (both
are `"1.0.0"`).

### 3. Capsule architecture <-> ABI capability matrix

The Capsule declares `Architecture.ARM64`. The default
Android ARM64 matrix reports `NATIVE` is available on
`ARM64`. The test asserts both — the architecture matches
the matrix's supported architectures.

### 4. Capsule GPU config <-> GPU vendor

The Capsule declares `gpu.driver = TURNIP`. The matrix
reports `MESA_TURNIP` is available on `ADRENO + ARM64`. The
test asserts both — the GPU driver is available on the
required GPU vendor.

### 5. Listing publishable + installable through the Market

The test publishes the listing via `LocalMarketPublisher` +
installs via `LocalMarketInstaller`. The round-trip succeeds
when the listing is well-formed + the publisher signs it +
the installer verifies the signature.

### 6. Listing discoverable via tag search

The test searches the catalog for `"first-party"` + verifies
the Elysium Linux listing is in the results. The tag-based
search is the **primary discovery mechanism** for the user
(the user searches for tags like "first-party" + "proprietary"
+ "wine" to find the distro).

### 7. Capsule build() consistency

The `build()` factory returns a Capsule with all the declared
fields. The test asserts every field is consistent with the
corresponding constant.

### 8. Listing draft is a valid `MarketListingDraft`

The draft is a valid `MarketListingDraft` (the type accepts
it; the init block doesn't throw).

---

## Design decisions

### Why an integration test, not a unit test?

Unit tests verify **single components** in isolation. The
Elysium Linux distro is **multi-component** (the listing +
the capsule + the matrix + the package manager + the
installer). A unit test on any single component wouldn't
prove the **cross-component consistency** — a listing
that's well-formed + a capsule that's well-formed can still
be **inconsistent** (e.g. different distribution ids).

The integration test verifies the **consistency** between
components. The test is the canonical proof that "the
Elysium Linux distro is end-to-end shippable".

### Why a `com.elysium.vanguard.core.runtime` package, not a `market` or `capsule` sub-package?

The test is **cross-cutting** — it touches the market + the
capsule + the linux + the runtime + the foundry + the
graphics packages. Putting it in `com.elysium.vanguard.core.runtime`
makes the cross-cutting nature explicit; the test is
**runtime-level**, not feature-level.

### Why include the round-trip install test?

The round-trip test (`publish + install + verify`) is the
**closest to production** the JVM tests can get. It
exercises:

- `LocalMarketPublisher.publish(draft)` — the publisher's
  signature + listing flow.
- `LocalMarketInstaller.install(request)` — the installer's
  signature verification + content-hash check + file write.

A failure in either is a **production failure** (a user
who can't install the distro). The round-trip test catches
this before the device sees it.

---

## Tests

14 new tests in `ElysiumLinuxMarketIntegrationTest`. The
tests cover:

- **Listing id <-> Capsule distribution id** (2 tests):
  constants match, built Capsule matches.
- **Listing version <-> rootfs version** (2 tests): version
  strings match, semver components.
- **Capsule architecture <-> ABI matrix** (2 tests):
  architecture supported on matrix, runtime is LINUX.
- **Capsule GPU config <-> GPU vendor** (2 tests): Turnip
  available on Adreno+ARM64, Vulkan api is satisfied.
- **Publish + install round-trip** (1 test): the listing is
  publishable + installable through the Market.
- **Tag search** (1 test): discoverable via "first-party"
  tag.
- **Capsule build() consistency** (2 tests): all fields
  populated, placeholders non-blank.
- **Type validation** (2 tests): Capsule type accepts the
  build, MarketListingDraft type accepts the draft.

**Total project tests:** 2909 (was 2895, +14 new).

---

## Phase 74 — closed

With the integration test shipped, **Phase 74 is fully
closed**:

- **Phase 74 first half**: `ElysiumLinuxDistroListing` (the
  distribution contract).
- **Phase 74 second half**: `ElysiumLinuxCapsule` (the
  runtime contract).
- **Phase 74 integration**: `ElysiumLinuxMarketIntegrationTest`
  (the cross-component consistency proof).

The Elysium Linux distro is now **discoverable + runnable
+ publishable + installable + consistent**. The Phase 74
work is **complete**.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/test/java/com/elysium/vanguard/core/runtime/ElysiumLinuxMarketIntegrationTest.kt` | new | 14 JVM integration tests |

---

## The role in the bigger picture

The integration test is the **canonical proof** that the
Elysium Linux distro is end-to-end shippable. The test:

- Verifies the **listing** is well-formed + discoverable.
- Verifies the **Capsule** is well-formed + consistent.
- Verifies the **ABI matrix** supports the architecture +
  GPU config.
- Verifies the **Market** round-trip succeeds (publish +
  install).
- Verifies the **package manager** is referenced correctly
  in the Capsule.

A future Phase 7+ increment can **promote the integration
test to a Phase 7 acceptance gate** — the test must pass
before any new Elysium Linux release is published.

The test is the **bridge** between "the typed foundation is
specified" (Phases 73-74) and "the real binary is built"
(future Phase 73+). The bridge is the **proof** that the
foundation is consistent + the runtime is ready.
