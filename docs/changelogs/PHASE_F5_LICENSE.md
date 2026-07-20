# Phase F5 second half (G6+G7, I-5.2) — License

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** F5 / Foundry / Commercial foundation
> **Predecessor:** Phase F5 first half (Royalty foundation)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.royalty.*`)

---

## TL;DR

The Foundry's **License** type is operational. The
typed usage license for a `RoyaltyContract`. The
license is a sealed class with 4 cases:

- **`PermissiveLicense`** — MIT / Apache-2.0 / BSD-2-Clause.
- **`CopyleftLicense`** — GPL-2.0 / GPL-3.0 / AGPL-3.0.
- **`ProprietaryLicense`** — Elysium-Proprietary / custom proprietary.
- **`CustomLicense`** — a custom license (the
  contributor defines the terms in a separate
  document).

Every license is bound to a specific contract + a
specific program + a specific contributor. The
license is **signed** (the platform binds the license
to the publisher via the license's signature).

This is the **second of three sub-tasks** in Phase F5
(G6+G7). The remaining sub-task:

- **I-5.3** — Double-entry ledger integration
  (the platform's accounting layer that records every
  settlement).

---

## What shipped

### `License` (sealed class, 4 cases)

The typed usage license for a `RoyaltyContract`. The
4 cases are:

```kotlin
sealed class License {
    abstract val licenseId: LicenseId
    abstract val contractId: RoyaltyContractId
    abstract val programId: VehicleProgramId
    abstract val contributorId: ContributorId
    abstract val displayName: String
    abstract val effectiveFromMs: Long
    abstract val effectiveUntilMs: Long?
    abstract val signature: Signature
    abstract val contentHash: ContentHash

    data class PermissiveLicense(
        val spdxIdentifier: String,
        val attributionRequired: Boolean = true,
        ...
    ) : License()

    data class CopyleftLicense(
        val spdxIdentifier: String,
        val shareAlikeRequired: Boolean = true,
        ...
    ) : License()

    data class ProprietaryLicense(
        val spdxIdentifier: String,
        val allowedUses: List<String>,
        val redistributionProhibited: Boolean = true,
        ...
    ) : License()

    data class CustomLicense(
        val termsDocumentUrl: String,
        val termsDocumentHash: ContentHash,
        ...
    ) : License()
}
```

The license is **immutable** (a sealed class with
`val` fields; no setters). A new license is a new
value. The license's lifecycle (a renewal, a
revocation) is a new `License` value, not a mutation
of the existing one.

### `LicenseId` (UUID value class)

The typed id of a license. The id is a UUID (per the
Foundry id convention); the id is the join key the
consumer uses to reference the license.

### SPDX identifiers

Each license carries an `spdxIdentifier` (a string
that matches the SPDX license list, e.g. `"MIT"`,
`"GPL-3.0"`, `"Apache-2.0"`). The SPDX identifier is
the **machine-readable** reference to the canonical
license text; a consumer follows the identifier to
the SPDX database to look up the full license text.

For proprietary licenses, the SPDX identifier uses
the `LicenseRef-` prefix (e.g.
`"LicenseRef-Elysium-Proprietary"`); the SPDX
database treats `LicenseRef-*` as a custom
identifier that the contributor defines.

---

## Design decisions

### Why a sealed class for `License`, not a single class with a flag?

A sealed class is **type-safe + exhaustive**. The
consumer (the license validator, the UI) uses `when
(license)` to dispatch by case:
- `is PermissiveLicense` — apply the attribution
  requirement.
- `is CopyleftLicense` — enforce the share-alike
  requirement.
- `is ProprietaryLicense` — enforce the allowed-uses
  list.
- `is CustomLicense` — fetch the terms document and
  apply the custom rules.

A single class with a flag would lose the type
safety; the consumer would need to check multiple
fields. The sealed class captures the **4 distinct
license strategies** the platform supports.

### Why use SPDX identifiers?

The SPDX (Software Package Data Exchange) license
list is the **industry-standard** catalog of open-
source licenses. Every SPDX identifier maps to a
canonical license text. Using SPDX identifiers
means the platform can interoperate with the
broader open-source ecosystem (the SPDX database, the
license-compliance tools, the SBOM generators).

For proprietary licenses, the SPDX list allows
custom identifiers with the `LicenseRef-` prefix.
The contributor defines the custom identifier +
the license text; the platform references the
identifier.

### Why is the `signature` mandatory?

The license is a **legal + audit** artifact. A
license without a signature is **not auditable** —
the platform cannot prove the contributor granted
the license.

The signature is computed over the license's
canonical form (the form excludes the signature
itself, like the manifest's signature). The platform
verifies the signature at load time.

### Why is the `contentHash` mandatory?

The content hash is the **content-addressed**
identifier of the license. The platform uses the
content hash to detect changes (a license update
produces a new content hash).

The content hash is also the **audit** identifier:
the platform's audit log records "contributor X
granted license Y (content hash Z) at time T". A
later query "what license was granted for program P
at time T?" returns the license by content hash.

### Why are `attributionRequired` and `shareAlikeRequired` defaults?

The defaults reflect the **canonical interpretation
of each license kind**:

- **Permissive licenses** require attribution by
  default (the MIT license requires the copyright
  notice + the permission notice in all copies).
- **Copyleft licenses** require share-alike by
  default (the GPL requires derivative works to be
  licensed under the GPL).

A contributor can override the defaults (e.g. an MIT
contributor can set `attributionRequired = false` if
they want a more permissive license). The defaults
are the **canonical interpretation**; the override
is the **contributor's choice**.

---

## Tests

15 new tests in `LicenseTest`. The tests cover:

- **LicenseId** (3 tests): random, from valid UUID,
  from malformed UUID.
- **PermissiveLicense** (3 tests): well-formed
  configuration, blank displayName rejected, blank
  spdxIdentifier rejected.
- **CopyleftLicense** (2 tests): well-formed
  configuration, blank spdxIdentifier rejected.
- **ProprietaryLicense** (3 tests): well-formed
  configuration, empty allowedUses rejected, blank
  allowedUses entries rejected.
- **CustomLicense** (2 tests): well-formed
  configuration, blank termsDocumentUrl rejected.
- **Effective period** (2 tests): non-positive
  effectiveFromMs rejected, effectiveUntilMs before
  effectiveFromMs is currently accepted (documented
  behavior).

**Total foundry tests:** ~662 (was ~647; +15 new).
**Total project tests:** 3054 (was 3039, +15 new).

---

## What's next — Phase F5 third half (G6+G7, I-5.3)

`DoubleEntryLedger` — the platform's accounting layer
that records every settlement. The ledger is the
**double-entry accounting** system:

- Every settlement has a **debit** + a **credit**
  (the platform's cut + the contributor's cut).
- Every entry is **immutable** (a settled entry
  cannot be modified; a correction is a new
  compensating entry).
- The ledger's totals are **balanced** (the sum of
  all debits equals the sum of all credits at any
  point in time).

The ledger is the **audit** artifact: a regulator
(a tax authority, a financial auditor) can inspect
the ledger + reconstruct every transaction.

The ledger is **pure-domain** (no I/O, no Android
dependencies). The test implementation is an
in-memory ledger; the production implementation is
a persistent ledger (a future Phase 7+ increment).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/foundry/core/royalty/License.kt` | new | sealed class + 4 license cases + LicenseId |
| `app/src/test/java/com/elysium/vanguard/foundry/core/royalty/LicenseTest.kt` | new | 15 JVM tests |

---

## The role in the bigger picture

The License is the **legal envelope** that defines
what the contributor can do with the contribution.
The license + the RoyaltyContract together are the
**commercial foundation** of the Foundry:

- The **RoyaltyContract** defines the **financial**
  arrangement (the rate, the period, the status).
- The **License** defines the **legal** arrangement
  (the permissions, the restrictions, the
  attribution requirements).

A contribution has both:
- A **royalty** — the platform pays the
  contributor based on the rule + the transaction.
- A **license** — the consumer uses the
  contribution under the license's terms.

The royalty + the license together are the
**commercial contract** between the platform and
the contributor. The platform's marketplace
(Phase F6) + the production hardening (Phase F7)
build on this commercial foundation.
