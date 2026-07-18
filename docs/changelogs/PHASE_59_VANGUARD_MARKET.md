# Phase 59 — Vanguard Market (catalog + signed distribution)

> **Status:** shipped 2026-07-18 against git head `9d5087c` (the Foundry audit-trail commit).
> **Build evidence:**
> - `testDebugUnitTest` — **1761 tests, 0 failures, 0 errors, 2 skipped** (was 1498; +263 net — Foundry is included in this baseline; +32 new in this commit for the Market)
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What this phase is

The **Vanguard Market** is the platform's signed
distribution channel. It's the catalog side of the
runtime: a typed, signed, content-addressed index
of every item the platform can install (distros,
apps, Wine profiles, containers, toolchains, IDEs,
servers, project templates, game configs, plugins,
automations, AI agents).

Phase 59 ships the **data layer**: the typed
listing, the signing layer, the in-memory catalog,
the search types, the search. The **write side**
(publish + sign + push) is `MarketPublisher` in
Phase 60+; the **install side** (download + verify
+ extract) is `MarketInstaller` in Phase 60+.

The Market is the foundation that the next 6 phases
(60–65) build on:
- **Phase 60** uses the Market to publish the
  Elysium Vanguard Linux distro.
- **Phase 61** uses the Market to distribute the
  Universal Desktop Shell.
- **Phase 65** uses the Market to add multiple
  distros.
- **Phase 64** uses the Market to instrument-test
  the install flow on a real device.

---

## 1. Architecture decisions

- **Signed distribution** (per `R-T-3` in
  `docs/foundry/risk-register.md`): every listing
  carries an asymmetric signature over its
  canonical form. The signature is bound to a
  `signatureKeyId` (the public key's identifier).
  A failed verification is a hard rejection.
- **Content-addressed** (per `R-DI-13` in
  `docs/foundry/risk-register.md`): every listing
  carries a `ContentHash` of the artifact's bytes.
  A mismatch is a hard rejection.
- **Typed, not free-form** (per `.ai/AGENTS.md`
  24.1): the `MarketSearchQuery` is a struct (not
  a free-form string). The `MarketSearchResult`
  is a struct (not a `Map<String, Any>`). The
  `MarketListingType` is a 12-value enum (not a
  string).
- **Bounded result sets** (per `.ai/AGENTS.md`
  24): the `MarketSearchQuery.limit` is capped
  at 1,000. The default is 100.
- **No floating-point money** (per ADR-0001): the
  Market itself is not a money system. Phase 2
  adds `Money`-backed pricing; the pricing uses
  `BigDecimal`.

---

## 2. Files added (5 main + 2 test = 7 new)

### 2.1 The 5 main files

```
app/src/main/java/com/elysium/vanguard/core/runtime/market/
├── MarketListingType.kt         (12-value enum)
├── MarketListing.kt             (data class: typed + content-addressed + signed)
├── MarketSigning.kt             (sign + verify over canonical form)
├── MarketSearch.kt              (MarketSearchQuery + MarketSearchResult)
├── MarketCatalog.kt             (interface: getById + search + listAll + count)
└── InMemoryMarketCatalog.kt     (Phase 1 impl: LinkedHashMap + ReentrantReadWriteLock)
```

### 2.2 The 2 test files (32 tests)

```
app/src/test/java/com/elysium/vanguard/core/runtime/market/
├── MarketCatalogTest.kt         (20 tests)
└── MarketSigningTest.kt         (12 tests)
```

---

## 3. The 12 `MarketListingType` values

```kotlin
enum class MarketListingType {
    DISTRO,         // A complete Linux distribution image
    APP,            // A Linux application package
    WINE_PROFILE,   // A preconfigured Wine profile
    CONTAINER,      // A container image
    TOOLCHAIN,      // A development toolchain
    IDE,            // An integrated development environment
    SERVER,         // A server (nginx, postgres, redis)
    PROJECT_TEMPLATE, // A project scaffolding
    GAME_CONFIG,    // A configuration for a specific game
    PLUGIN,         // A host-app plugin
    AUTOMATION,     // A workflow / script
    AI_AGENT,       // An AI agent configuration
}
```

The 12 values cover the platform's distribution
surface. New values are ADRs.

---

## 4. The `MarketListing` shape

```kotlin
data class MarketListing(
    val id: String,                        // "com.elysium.vanguard:ubuntu-24.04:1.0.0"
    val name: String,                      // "Ubuntu 24.04"
    val type: MarketListingType,
    val version: String,                   // "1.0.0"
    val contentHash: ContentHash,          // SHA-256 of the artifact's bytes
    val signatureKeyId: String,            // "publisher:canonical"
    val signature: Signature,              // HMAC-SHA-256 over canonical form
    val sizeBytes: Long,
    val dependencies: List<String>,        // other listing ids
    val tags: List<String>,
    val createdAt: Timestamp,
)
```

The canonical form (the input to the signature)
EXCLUDES the `signature` field. The format is:

```
market-listing:v1|id=X|name=Y|type=DISTRO|version=1.0.0|
contentHash=...|signatureKeyId=...|sizeBytes=...|
dependencies=sorted,joined|tags=sorted,joined|createdAt=...
```

Same inputs → same canonical form → same
signature (deterministic, per `R-DI-13`).

---

## 5. The signing contract

```kotlin
object MarketSigning {
    fun sign(listing: MarketListing, key: ByteArray): MarketListing
    fun verify(listing: MarketListing, key: ByteArray): Boolean
}
```

The Phase 1 implementation uses **HMAC-SHA-256**
(matching the Foundry `Signature` primitive). The
Phase 2 hardening replaces HMAC with **Ed25519**
(then ML-DSA-65) for asymmetric verification —
the public key is published alongside the listing;
the private key stays with the publisher.

A failed verification is a hard rejection. The
catalog MUST NOT install an unverified listing.

---

## 6. The search types

```kotlin
data class MarketSearchQuery(
    val query: String = "",                // case-insensitive substring
    val type: MarketListingType? = null,   // type filter
    val limit: Int = 100,                  // result cap (max 1,000)
)

data class MarketSearchResult(
    val listings: List<MarketListing>,
    val totalCount: Int,                   // BEFORE the limit
)
```

The `totalCount` is the count BEFORE the limit is
applied. The `listings` is the limit-capped list.
The consumer can show "showing 100 of 247" if
the cap is hit.

---

## 7. The catalog interface

```kotlin
interface MarketCatalog {
    fun getById(id: String): MarketListing?
    fun search(query: MarketSearchQuery): Result<MarketSearchResult>
    fun listAll(limit: Int = 100): Result<MarketSearchResult>
    fun count(): Int
}
```

The `InMemoryMarketCatalog` is the Phase 1 impl.
The HTTP-backed catalog (fetched from the Vanguard
Cloud per `ADR-028-vanguard-cloud.md`) is the
Phase 2 impl. The public interface is stable; the
implementation can be swapped without changing
the consumer.

The `InMemoryMarketCatalog.put(listing)` is
used by the test suite + by the Phase 2
`MarketPublisher`. The read-side interface is
intentionally read-only.

---

## 8. The 32 tests cover

- 20 catalog tests: put + getById + search (by
  name + by tag + by type + case-insensitive) +
  listAll (sorted) + count + limit + invalid
  arguments + duplicate-id rejection.
- 12 signing tests: sign + verify under correct
  key + verify failure under wrong key + verify
  failure on tampered name / content hash /
  version / dependencies + determinism (same
  inputs → same signature) + canonical form
  excludes the signature field.

---

## 9. What's NOT in Phase 59 (deferred to Phase 60+)

- **`MarketPublisher`**: the write side. Takes a
  `MarketListing` + signs it + pushes it to the
  Vanguard Cloud. Phase 60.
- **`MarketInstaller`**: the install side. Takes a
  `MarketListing` + downloads the artifact +
  verifies the signature + verifies the content
  hash + extracts to the right location. Phase 60.
- **HTTP-backed catalog**: replaces the
  `InMemoryMarketCatalog` with a fetch from the
  Vanguard Cloud. Phase 60.
- **Asymmetric signing (Ed25519 / ML-DSA-65)**:
  replaces the HMAC signing with an asymmetric
  primitive. Phase 2 hardening.
- **Pricing** (the marketplace's `Money`-backed
  price field): Phase 5 (per the Foundry
  roadmap).

---

## 10. Build evidence

```
./gradlew testDebugUnitTest
  -> 1761 tests, 0 failures, 0 errors, 2 skipped
  -> Market tests: 32 (new in this commit)
  -> EV + Foundry baseline: 1729

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101,458,649 bytes (101 MB)

Lint:
  -> 0 errors, 0 warnings
```

---

## 11. Next steps (continuing the pending list)

- **Phase 60** — Elysium Vanguard Linux distro
  (propia): the `MarketPublisher` + the `MarketInstaller`
  + the first published listing (the EV distro
  itself).
- **Phase 61** — Universal Desktop Shell: the
  Compose-based window manager + the dock.
- **Phase 62** — Mesa Turnip Vulkan ICD: the
  graphics driver integration.
- **Phase 63** — Security Zero Trust completion:
  the remaining hardening items.
- **Phase 64** — Instrumented test on real device:
  expand the `androidTest/` coverage to the
  Market install flow + the runtime flows.
- **Phase 65** — Multiple distros: expand the
  catalog with the first batch of community
  distros.

---

> "The Market is the platform's signed distribution
> channel. Every item is content-addressed, every
> listing is signed, every install is verified.
> The catalog is the read side; the publisher +
> the installer are the write side. The data
> layer is the foundation; the runtime comes next."
