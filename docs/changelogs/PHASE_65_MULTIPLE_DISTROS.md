# Phase 65 — Multiple distros (the first batch of community listings)

> **Status:** shipped 2026-07-18 against git head (the instrumented-tests commit).
> **Build evidence:**
> - `testDebugUnitTest` — **1855 tests, 0 failures, 0 errors, 2 skipped** (was 1842; +13 new in this commit)
> - `assembleAndroidTest` — green
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What this phase is

Phase 65 expands the Vanguard Market
catalog with **8 community distros**.
The platform's own distro
(Elysium Vanguard Linux) was the
first listing (Phase 60). Phase 65
adds the first batch of community
distros that users can install.

The 8 distros cover the most common
Linux families:
- **Debian-based**: Ubuntu 24.04 LTS,
  Debian 12 (Bookworm)
- **RPM-based**: Fedora 41, openSUSE
  Tumbleweed
- **Source-based**: Arch Linux (Rolling),
  Void Linux (Rolling), Alpine Linux
  3.20
- **Declarative**: NixOS 24.05

Each distro has its own runtime
profile (proot-friendly, bleeding-edge,
rolling, minimal, etc.) + the right
tags for the catalog search.

---

## 1. Architecture decisions

- **`CommunityDistros` as a single
  object** (per the existing
  `ElysiumVanguardDistroListing` pattern):
  all 8 distros are in one file. The
  pattern is consistent + the
  declarations are easy to find +
  modify.
- **Phase 1 listings are placeholders**:
  the `contentHash` and `sizeBytes`
  are stand-ins for the actual image
  bytes. The Phase 7+ build pipeline
  produces the real image + updates
  the constants + re-publishes the
  listing.
- **One signal per distro**: the
  `id` encodes `<group>:<name>:<version>`
  (the same format the platform uses
  for content addressing). The
  `tags` encode the Linux family
  + the rolling/release + the use case.
- **`ALL` is a `val` initialized at
  the END of the object** (per
  the bug caught during this commit):
  Kotlin initializes top-level/object-
  level `val` properties in declaration
  order; referencing a later `val` from
  an earlier one fails. The fix is to
  declare the individual `val`s first
  + the aggregated `ALL` last.

---

## 2. Files added (1 main + 1 test = 2 new)

### 2.1 The 1 main file

```
app/src/main/java/com/elysium/vanguard/core/runtime/market/
└── CommunityDistros.kt   (8 distros + ALL)
```

### 2.2 The 1 test file (13 tests)

```
app/src/test/java/com/elysium/vanguard/core/runtime/market/
└── CommunityDistrosTest.kt
```

---

## 3. The 8 community distros

| # | Name | Family | Size | Tags |
|---|---|---|---|---|
| 1 | Ubuntu 24.04 LTS | Debian | 1.8 GB | linux, debian-based, lts, ubuntu, proot-friendly |
| 2 | Fedora 41 | RPM | 1.6 GB | linux, rpm, fedora, bleeding-edge |
| 3 | Arch Linux (Rolling) | Source | 900 MB | linux, arch, rolling-release, minimal |
| 4 | openSUSE Tumbleweed (Rolling) | RPM | 1.4 GB | linux, rpm, opensuse, rolling-release |
| 5 | Debian 12 (Bookworm) | Debian | 1.5 GB | linux, debian, stable, server |
| 6 | Alpine Linux 3.20 | Source (musl) | 250 MB | linux, musl, alpine, container, minimal |
| 7 | Void Linux (Rolling) | Source (musl) | 700 MB | linux, musl, void, runit, independent |
| 8 | NixOS 24.05 | Declarative | 1.7 GB | linux, nix, nixos, declarative, reproducible |

The combined catalog (1 platform + 8
community) is **9 listings** with
diverse families + use cases.

---

## 4. The 13 tests cover

- Community distros has 8 entries.
- All community distros are of type
  `DISTRO`.
- All community distros have non-blank
  name + id.
- All community distros have unique ids.
- Ubuntu 24.04 has the expected fields
  (id + name + tags).
- Arch Linux has the expected fields
  (rolling-release tag).
- Fedora 41 has the expected fields
  (rpm + fedora tags).
- Alpine 3.20 is the smallest distro
  (< 500 MB).
- All community distros can be
  published to an in-memory catalog.
- Community distros are searchable by
  tag (3 distros match "rolling" — Arch,
  openSUSE, Void; 2 distros match "musl" —
  Alpine, Void; 2 distros match "debian" —
  Ubuntu, Debian).
- Community distros are searchable by
  name (Ubuntu, NixOS).
- Community distros are all signed and
  verifiable.
- Combined catalog has the platform
  distro + 8 community distros (9
  total).

---

## 5. Bugs found during testing (test-discovered)

Two bugs were found + fixed during
this commit:

### Bug 1: Object initialization order

The first attempt declared `val ALL`
BEFORE the individual `val`s. Kotlin
initializes top-level/object-level
properties in declaration order, so
`ALL = listOf(ubuntu_24_04, ...)` failed
with "Variable 'ubuntu_24_04' must be
initialized".

The fix: declare the individual `val`s
first + the aggregated `ALL` last.

### Bug 2: Mis-counted "rolling" search

The first test expected 2 distros to
match "rolling" (Arch + openSUSE).
The actual count was 3 (Arch +
openSUSE + Void — Void's name is
"Void Linux (Rolling)" so "rolling"
matches the name too).

The fix: the test now expects 3 (the
correct count given the actual data)
+ the comment explains why.

These are test-discovered regressions
— exactly the kind of evidence the
test suite is supposed to surface.

---

## 6. The catalog search stats (with all 9 distros)

```
search "rolling"   -> 3 (Arch, openSUSE, Void)
search "musl"      -> 2 (Alpine, Void)
search "debian"    -> 2 (Ubuntu, Debian)
search "minimal"   -> 2 (Arch, Alpine)
search "container"-> 1 (Alpine)
search "ubuntu"    -> 1 (Ubuntu)
search "nixos"     -> 1 (NixOS)
search "fedora"    -> 1 (Fedora)
```

The catalog is search-friendly; the
search is case-insensitive + matches
name + tags.

---

## 7. What's NOT in Phase 65 (deferred to later phases)

- **More distros**: the next batch
  (openSUSE Leap, Slackware, Gentoo,
  Solus, FreeBSD, etc.) is a Phase 2+
  follow-up.
- **Non-distro listings**: Wine
  profiles, IDEs, toolchains, AI agents,
  etc. (per the 12 `MarketListingType`
  values) — these are separate phases.
- **Real image bytes**: the
  `contentHash` + `sizeBytes` are
  placeholders. The Phase 7+ build
  pipeline produces the real images.
- **Search by version range**: the
  current search is a substring match;
  a semver range match is a Phase 2+
  follow-up.
- **Search by GPU requirement**: a
  listing may require Vulkan 1.3; a
  search for "vulkan" should return
  matching listings. Phase 2+.

---

## 8. Build evidence

```
./gradlew testDebugUnitTest
  -> 1855 tests, 0 failures, 0 errors, 2 skipped
  -> Community distros tests: 13 (new in this commit)
  -> EV + Foundry + Market + Desktop + Graphics + Security: 1842

./gradlew assembleAndroidTest
  -> green (instrumented tests compile)

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101 MB

Lint:
  -> 0 errors, 0 warnings
```

---

## 9. Pending list — DONE

```
Phase 59 — Vanguard Market (catálogo + signed distribution)   ✅ DONE
Phase 60 — Elysium Vanguard Linux distro (propia)              ✅ DONE
Phase 61 — Universal Desktop Shell (ventanas Compose, dock)    ✅ DONE
Phase 62 — Mesa Turnip Vulkan ICD                              ✅ DONE
Phase 63 — Security Zero Trust completion                      ✅ DONE
Phase 64 — Instrumented test en device real                    ✅ DONE
Phase 65 — Múltiples distros                                   ✅ DONE
```

7 of 7 phases shipped. **1855 tests,
0 failures, 0 errors, 0 lint warnings,
APK 101 MB.**

---

> "The Market is the platform's signed
> distribution channel. The first listing
> was the platform's own Linux distribution.
> The next eight are the community's
> most-loved distros: Ubuntu for stability,
> Arch for the minimalists, Alpine for
> containers, NixOS for the declaratives.
> Every listing is content-addressed,
> every install is verified, every distro
> is searchable. The catalog is the
> foundation. The pending list is done.
> What comes next is the next roadmap."
