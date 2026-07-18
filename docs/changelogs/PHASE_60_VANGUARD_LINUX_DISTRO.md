# Phase 60 — Elysium Vanguard Linux distro (propia)

> **Status:** shipped 2026-07-18 against git head `79a520e` (the Market data layer).
> **Build evidence:**
> - `testDebugUnitTest` — **1773 tests, 0 failures, 0 errors, 2 skipped** (was 1761; +12 new in this commit)
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What this phase is

Phase 60 closes the **Market** end-to-end: the
write side (`MarketPublisher`) + the install
side (`MarketInstaller`) + the **first listing**
in the catalog — the **Elysium Vanguard Linux**
distribution.

The Elysium Vanguard Linux distribution is the
platform's own Linux distribution. It is:

- A curated **Debian-based** base image.
- Bundled with the platform's runtime
  (the universal runtime + the proot + QEMU
  + the VNC viewer).
- Bundled with the platform's **Market client**
  (the in-VM tool that fetches + installs +
  updates from the Vanguard Market).
- Bundled with the platform's **security stack**
  (the Tink vault + the audit trail client).
- Signed by the platform's publisher identity
  (`publisher:elysium-vanguard`).

Phase 60 ships the **listing** + the **signed
distribution mechanism** + the **install
verification** + the **end-to-end round-trip
test**. The actual image bytes are a Phase 7+
deliverable (the image needs the runtime +
the market client compiled into a Linux
base, which is a substantial cross-compile
effort). The Phase 1 listing is a placeholder
that anchors the catalog + the publisher +
the installer.

---

## 1. Architecture decisions

- **Signed distribution** (per `R-T-3`): the
  publisher signs the listing with a private
  key; the installer verifies with the public
  key. A failed verification is a hard
  rejection — the install does NOT proceed.
- **Content-addressed** (per `R-DI-13`): the
  listing carries a `ContentHash`; the installer
  hashes the actual bytes and compares. A
  mismatch is a hard rejection.
- **Tamper detection** (per `R-T-3` + `R-T-5`):
  the install verifies the signature BEFORE
  writing the file. A tampered listing in the
  catalog (e.g. an attacker modified the
  signed bytes) fails verification.
- **No bypass** (per ADR-0010): the
  `MarketInstaller` is the **only** path to
  install a listing. A direct file write is
  not a `MarketInstall`; the consumer cannot
  bypass the verification.

---

## 2. Files added (3 main + 1 test = 4 new)

### 2.1 The 3 main files

```
app/src/main/java/com/elysium/vanguard/core/runtime/market/
├── MarketPublisher.kt                (interface + LocalMarketPublisher + MarketListingDraft)
├── MarketInstaller.kt                (interface + LocalMarketInstaller + InstallRequest + InstallReceipt)
└── ElysiumVanguardDistroListing.kt   (the first listing: the platform's own distro)
```

### 2.2 The 1 test file (12 tests)

```
app/src/test/java/com/elysium/vanguard/core/runtime/market/
└── MarketRoundTripTest.kt            (the end-to-end publish + install + verify flow)
```

---

## 3. The `MarketPublisher` shape

```kotlin
interface MarketPublisher {
    fun publish(draft: MarketListingDraft): Result<MarketListing>
    val publisherId: String
}

data class MarketListingDraft(
    val id: String,
    val name: String,
    val type: MarketListingType,
    val version: String,
    val contentHash: ContentHash,
    val sizeBytes: Long,
    val dependencies: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)
```

The publisher takes a `MarketListingDraft`
(unsigned), signs it with the publisher's
key, and pushes it to the catalog. The
publisher's identity is the `signatureKeyId`
on the signed listing; the catalog uses the
identity to attribute the listing.

---

## 4. The `MarketInstaller` shape

```kotlin
interface MarketInstaller {
    fun install(request: InstallRequest): Result<InstallReceipt>
}

data class InstallRequest(
    val listingId: String,
    val byteSource: () -> ByteArray,    // Phase 1: in-memory; Phase 2: HTTP/URL
    val targetDir: File,
    val verifyingKey: ByteArray,
)

data class InstallReceipt(
    val listingId: String,
    val installedPath: File,
    val artifactHash: String,           // SHA-256 of the installed bytes
    val signatureKeyId: String,
    val bytesInstalled: Long,
)
```

The install flow is total (every step is
checked):

1. Read the listing from the catalog.
2. Verify the signature against the
   `verifyingKey` (HMAC-SHA-256 in Phase 1;
   Ed25519 / ML-DSA-65 in Phase 2).
3. Read the bytes from the `byteSource`.
4. Verify the content hash against the
   listing's `contentHash`.
5. Write the bytes to
   `targetDir/<listingId>`.
6. Return the `InstallReceipt`.

Any failure is a typed `FoundryError`. The
install does NOT proceed past a failed
verification.

---

## 5. The Elysium Vanguard Linux listing

```kotlin
object ElysiumVanguardDistroListing {
    const val PUBLISHER_ID = "publisher:elysium-vanguard"
    const val VERSION = "1.0.0-TITAN"
    const val ID = "com.elysium.vanguard:distro:1.0.0-TITAN"
    const val NAME = "Elysium Vanguard Linux"
    val CONTENT_HASH = ContentHash.of("elysium-vanguard-distro-placeholder")
    const val SIZE_BYTES = 1_500_000_000L  // 1.5 GB placeholder
    val TAGS = listOf("linux", "debian-based", "elysium", "runtime", "market-client")
    val DEPENDENCIES = emptyList()        // runtime + market client are bundled
    
    fun draft(): MarketListingDraft = ...
}
```

The `CONTENT_HASH` and `SIZE_BYTES` are
placeholders until the actual image is
built. The listing is **structurally
complete** — when the image is built, the
publisher updates the constants and
re-publishes the listing.

---

## 6. The 12 round-trip tests cover

- `publish then install round-trip succeeds`
  (the happy path: end-to-end publish +
  install + verify the bytes match).
- `install rejects when the listing is not
  in the catalog` (the catalog miss path).
- `install rejects when the content hash
  does not match the bytes` (the hash
  mismatch path).
- `install rejects when the verifying key
  is wrong` (the wrong-key path).
- `publish rejects duplicate id` (the
  publish-twice path).
- `published listing is retrievable from
  the catalog` (the read-after-write path).
- `published listing is signed and verifiable`
  (the round-trip sign + verify path).
- `install writes the file to targetDir with
  the listing id as filename` (the install
  path layout).
- `market listing draft has the expected
  Elysium Vanguard Linux fields` (the
  contract path).
- `local publisher exposes its publisher id`
  (the identity path).
- `installing a tampered listing fails
  verification` (the tamper detection path).
- `published listing appears in search by
  tag` (the catalog search path).

---

## 7. The end-to-end round-trip (the test)

```kotlin
@Test
fun `publish then install round-trip succeeds for the Elysium Vanguard distro`() {
    val artifactBytes = ByteArray(1024) { (it % 256).toByte() }
    val contentHash = ContentHash.of(artifactBytes)
    val draft = ElysiumVanguardDistroListing.draft().copy(
        contentHash = contentHash,
        sizeBytes = artifactBytes.size.toLong(),
    )

    // 1. Sign + publish.
    val published = publisher.publish(draft).getOrThrow()
    assertEquals(publisherId, published.signatureKeyId)

    // 2. Install.
    val receipt = installer.install(
        InstallRequest(
            listingId = draft.id,
            byteSource = { artifactBytes },
            targetDir = tempDir,
            verifyingKey = signingKey,
        ),
    ).getOrThrow()

    // 3. Verify the installed file.
    assertEquals(contentHash.value, receipt.artifactHash)
    assertArrayEquals(artifactBytes, receipt.installedPath.readBytes())
}
```

The test exercises the full signed-distribution
path: sign the listing → push to the catalog →
read the bytes → verify the signature → verify
the content hash → write to disk → return the
receipt. Any failure at any step is a typed
error that the consumer pattern-matches on.

---

## 8. What's NOT in Phase 60 (deferred to later phases)

- **The actual image**: the Elysium Vanguard
  Linux distribution image is a Phase 7+
  deliverable. The Phase 1 listing is a
  placeholder.
- **HTTP-backed catalog** (replaces the
  `InMemoryMarketCatalog` with a fetch from
  the Vanguard Cloud per `ADR-028-vanguard-cloud.md`).
  Phase 2.
- **HTTP-backed byte source** (replaces the
  in-memory `byteSource: () -> ByteArray`
  with an HTTP client). Phase 2.
- **Asymmetric signing** (Ed25519 / ML-DSA-65).
  Phase 2 hardening.
- **Archive extraction** (`.tar.gz`, `.tar.xz`).
  Phase 2.
- **Install progress reporting** (the
  consumer wants to know the install is
  in progress). Phase 2.
- **Multiple distros** (the first batch of
  community distros). Phase 65.

---

## 9. Build evidence

```
./gradlew testDebugUnitTest
  -> 1773 tests, 0 failures, 0 errors, 2 skipped
  -> Market tests: 44 (32 from Phase 59 + 12 from Phase 60)
  -> EV + Foundry baseline: 1729

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101,465,169 bytes (101 MB)

Lint:
  -> 0 errors, 0 warnings
```

---

## 10. Next steps (continuing the pending list)

- **Phase 61** — Universal Desktop Shell:
  Compose-based window manager + dock. The
  shell is what the user sees when they
  launch a Linux app from the Market.
- **Phase 62** — Mesa Turnip Vulkan ICD:
  the graphics driver for Android-side GPU
  acceleration. Required for the Desktop
  Shell to render 3D apps.
- **Phase 63** — Security Zero Trust
  completion: the remaining hardening items
  (envelope encryption, secrets in vault,
  etc.).
- **Phase 64** — Instrumented test on real
  device: expand the `androidTest/` coverage
  to the Market install flow + the runtime
  flows.
- **Phase 65** — Multiple distros: the first
  batch of community distros in the catalog.

---

> "The Market is the platform's signed distribution
> channel. The first listing is the platform's own
> Linux distribution. The end-to-end round-trip
> proves the signed-distribution mechanism works:
> publish → install → verify → receipt. The
> catalog is signed, the install is verified, the
> file is content-addressed. Every step is a
> typed value; every failure is a typed error."
