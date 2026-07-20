# Phase F6 second half (G8, I-6.2) — Supplier Network

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** F6 / Foundry / Supplier Network
> **Predecessor:** Phase F6 first half (Marketplace, RFQ + Offer + Order)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.marketplace.*`)

---

## TL;DR

The Foundry's **Supplier Network** is
operational. The B2B supplier directory the
marketplace uses to **discover qualified
suppliers** before an RFQ is published.

The supplier network is the **typed
discovery layer** for the marketplace (Phase
F6 first half). The marketplace flow becomes:

1. The buyer creates an **RFQ** (the request).
2. The marketplace **queries the supplier
   network** for suppliers that match the
   RFQ's `componentSpec` + `region` →
   matches by `capabilityName` +
   `countryCode` / `Region`.
3. The matching suppliers submit
   **Offers**.
4. The buyer accepts an Offer → creates an
   **Order**.

The supplier network is the **step 2
primitive**: the typed registry of
suppliers, capabilities, regions, and
certifications. The supplier network is
the **5 primitives**:

1. **`Supplier`** — the typed identity of a
   supplier (a human or an organization).
2. **`SupplierQualification`** — the typed
   record of a supplier's capabilities (what
   they can supply, where they serve, the
   certifications they hold).
3. **`SupplierCapability`** — a single
   capability the supplier offers (e.g.
   "engine-blocks for passenger cars,
   100-5000 units/year").
4. **`Certification`** — a typed
   certification the supplier holds (e.g.
   "ISO 9001 issued by TÜV SÜD until
   2027-12-31").
5. **`Region`** — a typed region the supplier
   serves (a country, a continent, or
   worldwide).

The 5 primitives form the **canonical
supplier network**:

1. A supplier registers (the `Supplier` is
   added to the registry).
2. The supplier submits a
   `SupplierQualification` (the capabilities
   + regions + certifications are added).
3. The marketplace queries the registry for
   suppliers that match a capability (e.g.
   "engine-blocks") AND a region (e.g.
   Country "US").
4. The matching suppliers are the candidates
   for the RFQ.

**This is the second of two sub-tasks** in
Phase F6 (G8). **Phase F6 (G8) is CLOSED.**

---

## What shipped

### `Supplier` (data class)

The typed identity of a supplier. The
supplier has:

- **`supplierId`** — UUID; the join key the
  marketplace uses to find the supplier.
- **`name`** — the supplier's display name
  (e.g. "Acme Powertrain Co.").
- **`legalEntity`** — the legal name of the
  supplier (e.g. "Acme Powertrain
  Corporation, Inc.").
- **`countryCode`** — the 2-letter ISO
  3166-1 alpha-2 country code where the
  supplier is headquartered.
- **`yearEstablished`** — the year the
  supplier was established (> 1800, <=
  2100).
- **`contactEmail`** — the supplier's
  contact email (RFC 5322 simplified;
  must contain `@` and `.`).
- **`signature`** — the supplier's
  signature; binds the supplier to the
  registry.

The supplier is **immutable** (a data class;
no setters). A new supplier is a new value.
The supplier's qualifications (the
capabilities + regions) are added separately
via `SupplierRegistry.addQualification`.

### `SupplierQualification` (data class)

The typed record of a supplier's
capabilities. The qualification has:

- **`qualificationId`** — UUID.
- **`supplierId`** — the supplier the
  qualification is for.
- **`capabilities`** — the capabilities
  the supplier offers (e.g.
  "engine-blocks", "transmissions").
- **`regions`** — the regions the supplier
  serves (e.g. `Country("US")`,
  `Continental("EU")`, `Worldwide`).
- **`lastReviewedMs`** — the timestamp the
  qualification was last reviewed.
- **`reviewerId`** — the `UserId` that
  reviewed the qualification (a **human**
  reviewer; AI agents cannot approve
  supplier qualifications per the AI
  authority boundary in `.ai/AGENTS.md`).
- **`signature`** — the qualification's
  signature.

The qualification has two helper methods:

- **`offersCapability(capabilityName)`** —
  returns `true` if the qualification
  includes a capability with the given name.
- **`servesCountry(countryCode)`** —
  returns `true` if any of the qualification's
  regions includes the given country code.

### `SupplierCapability` (data class)

A single capability the supplier offers. The
capability has:

- **`capabilityName`** — the capability
  name (e.g. "engine-blocks",
  "transmissions", "wiring-harnesses").
- **`vehicleDomain`** — the vehicle domain
  (e.g. "passenger-cars",
  "commercial-trucks", "motorcycles").
- **`minVolumePerYear`** — the supplier's
  minimum annual production capacity (> 0).
- **`maxVolumePerYear`** — the supplier's
  maximum annual production capacity
  (>= min).
- **`certifications`** — the certifications
  the supplier holds for this capability
  (can be empty).

### `Certification` (data class)

A typed certification the supplier holds.
The certification has:

- **`name`** — the certification name
  (e.g. "ISO 9001", "IATF 16949",
  "AS9100").
- **`issuer`** — the certification issuer
  (e.g. "TÜV SÜD", "BSI", "DNV").
- **`validUntilMs`** — the certification's
  expiration timestamp.

### `Region` (sealed class, 3 cases)

The typed region the supplier serves. The
sealed class has 3 cases:

- **`Country(countryCode)`** — a single
  2-letter ISO 3166-1 alpha-2 country code
  (e.g. "US", "DE", "JP").
- **`Continental(continentCode)`** — a
  continent code; the 7 valid codes are
  `AF`, `AS`, `EU`, `NA`, `OC`, `SA`, `AN`.
- **`Worldwide`** — every country (data
  object).

The `Region` has a helper method:

- **`includes(countryCode)`** — returns
  `true` if the region includes the given
  country. The check is:
  - `Country`: exact match on the 2-letter
    code.
  - `Continental`: the country belongs to
    the continent (per
    `Region.COUNTRY_TO_CONTINENT`).
  - `Worldwide`: always `true`.

### `COUNTRY_TO_CONTINENT` (Map)

A small ISO 3166-1 alpha-2 → continent code
map (the 36 countries the platform
supports). The map is **not exhaustive**
(it covers the countries the platform
supports; a future Phase 7+ increment may
add more countries). The map is used by
`Region.Continental.includes(countryCode)`
to determine whether a country belongs to a
continent.

### `SupplierRegistry` (sealed class, 1 in-memory impl)

The typed directory. The interface has:

- **`register(supplier)`** — register a new
  supplier. Returns
  `Result.failure(SupplierRegistryError.SupplierAlreadyRegistered)`
  if the supplier id is already used.
- **`addQualification(qualification)`** —
  add a qualification to a registered
  supplier. Returns
  `Result.failure(SupplierRegistryError.SupplierNotFound)`
  if the supplier is not registered, OR
  `Result.failure(SupplierRegistryError.DuplicateQualificationId)`
  if the qualification id is already used.
- **`getSupplier(supplierId)`** — get a
  supplier by id; returns `null` if not
  registered.
- **`getQualifications(supplierId)`** — get
  a supplier's qualifications; returns
  empty if the supplier is not registered OR
  has no qualifications.
- **`findByCapability(capabilityName)`** —
  find suppliers that offer a specific
  capability. Returns empty if no supplier
  offers the capability.
- **`findByCountry(countryCode)`** — find
  suppliers that serve a specific country.
  A supplier that serves `Worldwide` OR
  `Continental` is included when the country
  is part of that region.
- **`findByRegion(region)`** — find
  suppliers that serve a specific region
  (exact match on the region).
- **`findByCapabilityAndCountry(capabilityName, countryCode)`** —
  find suppliers that offer a specific
  capability AND serve a specific country.
  This is the **primary marketplace
  discovery primitive**.

### `InMemorySupplierRegistry` (impl)

The in-memory implementation. The registry
is **thread-safe** (the underlying
collections are `CopyOnWriteArrayList` for
safe iteration during query + a
`ConcurrentHashMap` for the supplier id →
supplier lookup).

### `SupplierRegistryError` (sealed class, 3 cases)

The typed error envelope for the supplier
network. The error extends `RuntimeException`
(mirrors the `FoundryError` contract with
`code` + `retryClassification` + `message`,
but lives in the `marketplace` package
because Kotlin sealed classes only permit
subclassing in the same package where the
base class is declared). The 3 variants:

- **`SupplierAlreadyRegistered(supplierId)`**
  — the supplier id is already registered.
- **`SupplierNotFound(supplierId)`** — the
  supplier id is not registered.
- **`DuplicateQualificationId(qualificationId)`**
  — the qualification id is already used.

### `SupplierRegistryRetryClassification` (enum)

The retry classification for
`SupplierRegistryError`. The enum mirrors
`FoundryError.RetryClassification`
(`RETRYABLE_IMMEDIATE`,
`RETRYABLE_BACKOFF`,
`RETRYABLE_IDEMPOTENT_ONLY`,
`NON_RETRYABLE`).

### `SupplierId` + `QualificationId` (UUID value classes)

The typed ids. Both follow the Foundry id
convention (UUID-based, with `random()` +
`from()` factories that return
`Result<...>` on parse failure).

---

## Design decisions

### Why a sealed class for `Region`, not a single class with a flag?

A sealed class is **type-safe +
exhaustive**. The consumer (the marketplace
orchestrator) uses `when (region)` to
dispatch by case:

- `is Region.Country` — check the country
  code.
- `is Region.Continental` — check the
  continent code.
- `Region.Worldwide` — always matches.

A single class with a flag would lose the
type safety; the consumer would need to
check the flag. The sealed class captures
the **3 distinct region kinds** the
platform supports.

### Why is the `Supplier` a data class, not a sealed class?

The `Supplier` is a **single class** (not
a sealed class with multiple cases) because
the supplier's lifecycle is encoded by
**registration + qualifications** (the
supplier is either registered OR not; the
qualifications are added separately). The
supplier's immutability is preserved by the
data class.

A sealed class would be overkill for a
single registration axis.

### Why is `SupplierRegistry` a sealed class, not an interface?

The `SupplierRegistry` is a **sealed class**
with a single in-memory impl. The sealed
class captures the **abstract behavior**
(the platform's typed registry contract);
the in-memory impl is the test + production
default. A future Phase 7+ increment may
add a `DistributedSupplierRegistry` (a
production impl backed by a database); the
sealed class allows the consumer to pattern-
match on the impl.

### Why is the supplier id distinct from `UserId`?

A `UserId` is a **platform user** (a human
or an AI agent). A `SupplierId` is a
**supplier entity** (a human OR an
organization). An organization is a
supplier but is not a single platform user;
the supplier id captures the
**legal-entity** identity of the supplier
(separate from the human user who manages
the supplier account).

### Why is `Region.Country.countryCode` 2-letter uppercase letters only?

The 2-letter ISO 3166-1 alpha-2 code is
the **canonical country code** in the
industry. The constraint (uppercase letters
only) is enforced at the type level so the
registry cannot store a malformed country
code. The lowercase check rejects typos
(e.g. "us" instead of "US").

### Why is `Region.Continental` restricted to 7 codes?

The 7 continent codes (AF, AS, EU, NA, OC,
SA, AN) are the **ISO 3166-1 continent
codes**. The platform does not support
custom continent codes (a custom code
would require a custom `COUNTRY_TO_CONTINENT`
map; the platform keeps the map small for
predictability).

### Why does `Region.Continental.includes` use a country → continent map?

The map is the **canonical reference** for
"which countries are in which continent".
A `Continental("EU").includes("DE")`
operation is the natural way to query a
supplier that serves Europe; the map is the
mechanism that makes the query
**declarative** (no imperative iteration
over a list of countries).

### Why is `SupplierQualification.reviewerId` a `UserId` (not an `AIAuthorId`)?

Per the AI authority boundary in
`.ai/AGENTS.md`, AI agents **cannot approve
supplier qualifications**. A supplier
qualification is a **legal commitment** (the
supplier certifies the capability + the
region + the certification); the reviewer
must be a **human** (a platform user). The
reviewer is a `UserId`; the AI council
(Phase F4) may *propose* a qualification
(an `AIProposal` with `kind =
AIProposalKind.SUPPLIER_QUALIFICATION`),
but a human user must *approve* the
qualification before it's added to the
registry.

### Why is `SupplierRegistryError` a separate sealed class, not extending `FoundryError`?

Kotlin sealed classes **only permit
subclassing in the same package where the
base class is declared**. `FoundryError`
lives in `ontology.primitives`; the
supplier registry lives in `marketplace`.
The cross-package extension is not
allowed. The cleanest fix is a separate
sealed class in the `marketplace` package
that **mirrors** the `FoundryError`
contract (same `code` + `retryClassification`
+ `message` shape + extends
`RuntimeException`).

This is a **known Kotlin language
limitation**; the future "Kotlin 2.0 sealed
interface" feature may allow cross-package
subclassing. For now, the mirror class is
the platform's typed-error contract
preserved.

---

## Tests

60 new tests in `SupplierTest`. The tests
cover:

- **Supplier invariants** (11 tests):
  well-formed configuration, blank name,
  blank legalEntity, non-2-letter
  countryCode, lowercase countryCode,
  non-letter countryCode, year before
  MIN_YEAR, year after MAX_YEAR, blank
  contactEmail, email without `@`, email
  without `.`.
- **Certification invariants** (4 tests):
  well-formed configuration, blank name,
  blank issuer, zero validUntilMs.
- **SupplierCapability invariants** (5
  tests): well-formed configuration, blank
  capabilityName, blank vehicleDomain, zero
  minVolumePerYear, max < min.
- **Region invariants** (10 tests): Country
  well-formed, Country non-2-letter,
  Country lowercase, Continental
  well-formed, Continental invalid
  continent, Worldwide is a single
  instance, Country.includes exact match +
  mismatch, Continental.includes for
  countries in / not in the continent,
  Worldwide.includes always true.
- **SupplierQualification invariants** (9
  tests): well-formed configuration, empty
  capabilities, empty regions, zero
  lastReviewedMs, offersCapability match +
  mismatch, servesCountry for direct
  Country + Continental + Worldwide.
- **InMemorySupplierRegistry — register
  + getSupplier** (4 tests): register a
  well-formed supplier, register rejects a
  duplicate supplier id, getSupplier
  returns the supplier by id, getSupplier
  returns null for an unknown id.
- **InMemorySupplierRegistry —
  addQualification** (5 tests):
  addQualification accepts a well-formed
  qualification, addQualification rejects
  an unknown supplier, addQualification
  rejects a duplicate qualification id,
  getQualifications returns the
  qualifications for a supplier,
  getQualifications returns empty for an
  unknown supplier.
- **InMemorySupplierRegistry — discovery**
  (8 tests): findByCapability returns
  suppliers that offer the capability,
  findByCapability returns empty for an
  unknown capability, findByCountry returns
  suppliers that serve the country,
  findByCountry returns only the supplier
  in the country, findByCountry returns
  empty for an unsupported country,
  findByRegion returns suppliers that serve
  the region, findByRegion returns
  suppliers that serve Worldwide,
  findByCapabilityAndCountry returns
  suppliers that match both,
  findByCapabilityAndCountry returns empty
  when no match.
- **Realistic scenario** (1 test): a
  supplier registers, gets qualified for
  engine-blocks in NA + EU; a buyer creates
  an RFQ for engine-blocks in the US; the
  registry finds the supplier; the same RFQ
  for Japan finds no suppliers.

**Total foundry tests:** ~760 (was ~700;
+60 new).
**Total project tests:** 3163 (was 3103;
+60 new).

**2 test-discovered bugs fixed** during
this phase:

1. **Kotlin sealed class cross-package
   inheritance**: `SupplierRegistryError`
   cannot extend `FoundryError` from a
   different package. Fix: `SupplierRegistryError`
   is a separate sealed class that mirrors
   the `FoundryError` contract (`code` +
   `retryClassification` + `message` +
   `RuntimeException`).
2. **Test helper missing `capabilityName`
   parameter**: the `buildQualification`
   fixture didn't accept a
   `capabilityName` parameter; tests
   couldn't override the default capability
   name. Fix: add a `capabilityName: String?`
   parameter that, when present, replaces
   the default capability.

---

## Phase F6 (G8) closure

**Phase F6 is CLOSED.** The Foundry's
marketplace is **complete**:

- **I-6.1** — `RFQ` + `Offer` + `Order` (the
  3 transaction primitives).
- **I-6.2** — `Supplier` +
  `SupplierQualification` (the discovery
  layer that feeds the marketplace).

The marketplace is the **commercial layer**
that ties together the Foundry's three
commercial primitives:

- **The royalty foundation** (Phase F5
  first half) — the financial arrangement.
- **The license** (Phase F5 second half) —
  the legal envelope.
- **The double-entry ledger** (Phase F5
  third half) — the accounting record.
- **The marketplace** (Phase F6) — the
  transaction layer that uses the 3
  primitives to power B2B transactions.

The marketplace + the supplier network
together form the **complete B2B
marketplace**. The supplier network is the
**discovery**; the marketplace is the
**transaction**.

---

## What's next

The next concrete deliverable is up to
the user. The remaining work in the
Foundry program:

- **Phase F7 (G9+G10) — Production
  hardening**: threat model + SLOs + on-call
  + runbooks + red team + CVE SLA +
  observability + multi-module split
  (per ADR-0023).
- **Foundry Phase F8 (G11) — International
  expansion** (i18n + multi-currency +
  multi-jurisdiction compliance).
- **Foundry Phase F9 (G12) — The Foundry
  public API** (the B2B API surface for
  third-party integrations).

The next concrete deliverable in the EV
runtime:

- **EV Phase 72 — Capsule installer UI**
  (Compose) for the new Elysium Linux
  distro.
- **EV Phase 74 — FileObserver for
  real-device audit step 9** (the existing
  Phase 71 E2E test needs a real file
  observer; the audit step currently uses
  an empty writes list).
- **ProcessLauncher interface +
  AndroidProcessLauncher implementation**
  (a future Phase 7+ increment that
  consumes `LaunchPlan` from
  `RuntimeDispatcher`).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/foundry/core/marketplace/Supplier.kt` | new | Supplier + SupplierQualification + Region + Certification + SupplierCapability + SupplierRegistry + SupplierRegistryError + ids |
| `app/src/test/java/com/elysium/vanguard/foundry/core/marketplace/SupplierTest.kt` | new | 60 JVM tests |

---

## The role in the bigger picture

The supplier network is the **discovery
layer** that the marketplace (Phase F6
first half) uses to find qualified
suppliers. The full B2B marketplace flow
becomes:

1. **Discovery** (Phase F6 second half) —
   the marketplace queries the supplier
   network for candidates that match an
   RFQ's `componentSpec` + `region`.
2. **Transaction** (Phase F6 first half) —
   the matched suppliers submit Offers;
   the buyer accepts an Offer → creates an
   Order.
3. **Settlement** (Phase F5 first half) —
   the Order triggers a Settlement (per
   the RoyaltyContract); the platform
   records the revenue + the contributor's
   cut.
4. **License** (Phase F5 second half) — the
   Order binds to a License (the legal
   envelope for the use of the contributor's
   data).
5. **Accounting** (Phase F5 third half) —
   the Order is recorded in the
   double-entry ledger (the audit).

The supplier network is the **first step**
in the B2B marketplace flow. Without the
supplier network, the marketplace would
have no way to find qualified suppliers;
the supplier network is the **directory
that powers the marketplace**.
