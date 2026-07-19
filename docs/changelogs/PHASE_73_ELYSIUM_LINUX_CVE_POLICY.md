# Phase 73 (third half, I-73.3.5) — Elysium Linux CVE Policy

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 73 / Linux distro foundation — CVE policy
> **Predecessor:** I-73.3.4 (Update Strategy)
> **Vertical:** Elysium Linux (`com.elysium.vanguard.core.linux.*`)

---

## TL;DR

Elysium Linux has a **typed vulnerability policy**. The distro
has a documented commitment to security:

- **Severity levels** (CRITICAL / HIGH / MEDIUM / LOW / NONE)
  with CVSS score ranges.
- **Response SLA** (the maximum time from a CVE disclosure to
  a patched Elysium Linux release, per severity).
- **Disclosure timeline** (the time from a CVE patch to a
  public CVE record, per severity).
- **Affected package tracking** (the typed list of packages
  affected by a CVE; a CVE is associated with one or more
  packages + a fixed-in version).

The policy is the **distro's commitment to security**. A user
who installs Elysium Linux has a typed answer to "when will my
device be patched?" — the answer is the policy's
`severityResponseHours`.

This is the **fifth and final** sub-task of Phase 73 third
half. The third half is now **CLOSED**: the Elysium Linux
distro's foundation is fully specified (manifest + trust
chain + package manager + repository + runtime layers + rootfs
layout + ABI capability matrix + update strategy + CVE policy).

---

## What shipped

### `ElysiumCvePolicy`

The distro's typed vulnerability policy. The policy has:

```kotlin
data class ElysiumCvePolicy(
    val severityResponseHours: Map<ElysiumCveSeverity, Int>,
    val severityDisclosureDelayHours: Map<ElysiumCveSeverity, Int>,
) {
    fun responseSlaFor(severity: ElysiumCveSeverity): Int
    fun disclosureDelayFor(severity: ElysiumCveSeverity): Int
    fun meetsResponseSla(severity, disclosedAtMs, patchedAtMs): Boolean

    companion object {
        val DEFAULT: ElysiumCvePolicy
    }
}
```

### `ElysiumCveSeverity` (enum, 5 values)

| Severity | CVSS range | Display |
| --- | --- | --- |
| CRITICAL | 9.0-10.0 | "Critical" |
| HIGH | 7.0-8.99 | "High" |
| MEDIUM | 4.0-6.99 | "Medium" |
| LOW | 0.1-3.99 | "Low" |
| NONE | 0.0 | "None" |

The `fromCvss(score)` factory maps a CVSS v3.1 base score to a
severity. The function is **total**: every CVSS score in
[0.0, 10.0] maps to exactly one severity.

### `ElysiumCveId`

A typed CVE id. The pattern is the official CNA format:
`CVE-YYYY-NNNN+` (at least 4 digits in the serial).

```kotlin
data class ElysiumCveId(val value: String) {
    init { require(value.matches(Regex("^CVE-\\d{4}-\\d{4,}$"))) }
}
```

### `ElysiumCveStatus` (enum, 4 values)

| Status | Meaning |
| --- | --- |
| UNDISCLOSED | Patch in development; CVE not yet public |
| DISCLOSED_PATCHED | Patch released; CVE is public |
| DISCLOSED_UNPATCHED | CVE is public; no patch yet |
| WONT_FIX | Distro declined to patch (rare) |

### `ElysiumCveRecord`

The typed record of a CVE in the Elysium Linux distro.

```kotlin
data class ElysiumCveRecord(
    val cveId: ElysiumCveId,
    val severity: ElysiumCveSeverity,
    val cvssScore: Double,
    val description: String,
    val affectedPackages: List<ElysiumCveAffectedPackage>,
    val status: ElysiumCveStatus,
    val disclosedAtMs: Long?,
    val patchedAtMs: Long?,
)
```

The init block enforces the lifecycle invariants:
- `UNDISCLOSED` → `disclosedAtMs == null`.
- `DISCLOSED_PATCHED` → both timestamps are set.
- `WONT_FIX` → `patchedAtMs == null`.

### `ElysiumCveAffectedPackage`

The typed record of a single package affected by a CVE.

```kotlin
data class ElysiumCveAffectedPackage(
    val packageName: String,
    val fixedInVersion: ElysiumPackageVersion,
    val affectedVersions: VersionConstraint,
)
```

### Default policy

| Severity | Response SLA | Disclosure delay |
| --- | --- | --- |
| CRITICAL | 24h | 0h (no embargo) |
| HIGH | 7d (168h) | 24h |
| MEDIUM | 30d (720h) | 7d (168h) |
| LOW | 90d (2160h) | 30d (720h) |
| NONE | 365d (8760h) | 365d |

The defaults are the distro's standard commitment. A future
Phase 73 increment may add per-domain policies (e.g. a tighter
policy for security-critical packages like OpenSSL or sudo).

---

## Design decisions

### Why a typed severity enum, not a raw CVSS score?

A CVSS score is a **number**; a severity is a **typed
classification**. The two are related (a CVSS score maps to a
severity) but distinct. The enum captures the **canonical
classes** (CRITICAL / HIGH / MEDIUM / LOW / NONE) and the
`fromCvss` factory provides the mapping.

The enum is **exhaustive**: every CVSS score in [0.0, 10.0]
maps to exactly one severity. The init block of the policy
requires every severity to be in both maps; a missing severity
is a misconfiguration.

### Why a `NONE` severity?

A CVSS score of 0.0 is a real thing — it's a CVE that the NVD
has reviewed + concluded has no real impact (e.g. a
documentation error). The `NONE` severity captures this case.

The policy's `NONE` SLA is 365d (1 year); the disclosure delay
is 365d. This is a **defensive default** — a NONE-severity CVE
gets the longest response time + the longest disclosure delay,
because the distro doesn't act on it.

### Why a typed `ElysiumCveId`, not a String?

A `String` has no validation. A `String` can be `""`,
`"foo"`, `"CVE-2024-123"` (3-digit serial), `"cve-2024-1234"`
(lowercase). Every consumer of the id would re-validate, and
the validation rules would drift.

A `ElysiumCveId` is a **typed value** that:
- Rejects blank values.
- Rejects non-CVE format.
- Rejects 3-digit serials (the official CNA format is
  4+ digits).
- Rejects lowercase (the official format is uppercase).

The trade-off: every API that takes a CVE id takes an
`ElysiumCveId`, not a `String`. This is **intentional** — the
type system enforces the invariants.

### Why a `meetsResponseSla` method on the policy?

The method is the **typed answer** to "did the distro meet
its SLA for this CVE?". The method compares the disclosedAtMs
+ the patchedAtMs + the policy's `severityResponseHours[severity]`
+ returns a boolean.

A consumer (the security dashboard, the user's notification)
asks `policy.meetsResponseSla(...)` instead of computing the
SLA inline. The policy is the **single source of truth** for
SLA computation.

### Why a status enum with 4 cases, not 2 (DISCLOSED + UNDISCLOSED)?

A 2-case enum would conflate "no patch yet" with "declined to
patch". A 4-case enum captures the **full lifecycle**:

- `UNDISCLOSED` — coordinated disclosure in progress.
- `DISCLOSED_PATCHED` — normal end state.
- `DISCLOSED_UNPATCHED` — the CVE is public + no patch
  exists; the user is exposed.
- `WONT_FIX` — the distro declined to patch (rare; usually
  because the package is end-of-life).

The 4 cases let the security dashboard show the user
**which CVEs are currently exposing the user** (the
`DISCLOSED_UNPATCHED` case) — this is the most important
piece of information for a security-conscious user.

---

## Bug-fixes (test-discovered, fixed in this phase)

### 1. `ElysiumCvePolicy.DEFAULT` failed at object init

**Symptom:** `ExceptionInInitializerError` at the
`ElysiumCvePolicy.DEFAULT` object init. The cause was
`IllegalArgumentException: ...missing severity: NONE`.

**Root cause:** The policy's init block requires every severity
in `ElysiumCveSeverity.values()` to be in both
`severityResponseHours` + `severityDisclosureDelayHours`. The
first cut of `DEFAULT` only had 4 severities
(CRITICAL/HIGH/MEDIUM/LOW), missing `NONE`.

**Fix:** Added `NONE` to the DEFAULT policy with a 365d SLA
(informational; the distro doesn't act on NONE-severity
CVEs).

This is a **test-discovered** bug — the test that referenced
`ElysiumCvePolicy.DEFAULT` failed at object init time,
surfacing the missing-severity issue.

### 2. `cveId rejects a 3-digit serial` test checked wrong substring

**Symptom:** The test asserted `e.message!!.contains("PATTERN")`
but the actual error message contains the **regex** (`^CVE-\\d{4}-\\d{4,}$`),
not the word "PATTERN".

**Root cause:** The test used the constant name as a substring
match, but the require block substitutes the constant's value
(the regex) into the message.

**Fix:** Changed the substring match to `e.message!!.contains("CVE-")`
which is a stable substring of the error message.

### 3. `policy rejects non-positive response hours` test was missing the NONE severity

**Symptom:** Test failed because the policy's init block
rejected the map for missing `NONE`, not for the zero hours.

**Root cause:** The test only included 4 severities; the init
block requires 5.

**Fix:** Added `NONE` to both maps in the test (with a valid
SLA), so the test now exercises the "non-positive hours"
rejection path.

---

## Tests

32 new tests in `ElysiumCvePolicyTest`. The tests cover:

- **ElysiumCveSeverity** (8 tests): fromCvss mapping (5
  ranges), reject negative scores, reject scores > 10,
  every severity has a non-blank displayLabel.
- **ElysiumCveId** (5 tests): canonical format accepted,
  5-digit serial accepted, 3-digit serial rejected,
  lowercase rejected, blank rejected.
- **ElysiumCvePolicy** (10 tests): reject empty
  responseHours, reject empty disclosureHours, require every
  severity in both maps, reject non-positive response hours,
  reject negative response hours, allow zero disclosure delay,
  responseSlaFor, disclosureDelayFor, meetsResponseSla
  (within SLA), meetsResponseSla (outside SLA).
- **ElysiumCveRecord** (6 tests): reject cvssScore out of
  range, reject blank description, reject empty affected
  packages, UNDISCLOSED rejects disclosedAtMs set,
  DISCLOSED_PATCHED requires both timestamps, WONT_FIX rejects
  patchedAtMs set, DISCLOSED_PATCHED with both timestamps
  accepted.
- **ElysiumCveAffectedPackage** (2 tests): reject blank
  packageName, accept well-formed configuration.

**Total linux tests:** 222 (32 manifest + 30 package manager
+ 41 runtime layers + 35 rootfs layout + 23 capability
matrix + 29 update strategy + 32 CVE policy).
**Total project tests:** 2857 (was 2825, +32 new).

---

## Phase 73 third half — CLOSED

With I-73.3.5 (CVE policy) shipped, **Phase 73 third half is
closed**. The Elysium Linux distro's foundation is now fully
specified:

| Sub-task | Status | What |
| --- | --- | --- |
| I-73.1 (first half) | ✅ | Manifest + trust chain |
| I-73.2 (second half) | ✅ | Package manager + repository |
| I-73.3.1 | ✅ | Runtime layers (Native / MesaTurnip / Box64 / Fex / Wine) |
| I-73.3.2 | ✅ | Rootfs layout (FHS + Elysium descendants) |
| I-73.3.3 | ✅ | ABI capability matrix |
| I-73.3.4 | ✅ | Update strategy (A/B + versioned images) |
| I-73.3.5 | ✅ | CVE policy (severities + SLA + disclosure) |

The next Phase 73 increment is the **real binary** — the
minimal rootfs tarball, the Mesa/Turnip/Box64/FEX/Wine
binaries, the first-party repository. The foundation is ready;
the binaries are the next step.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumCvePolicy.kt` | new | policy + severity + id + status + record + affected package |
| `app/src/test/java/com/elysium/vanguard/core/linux/ElysiumCvePolicyTest.kt` | new | 32 JVM tests |

---

## The role in the bigger picture

The CVE policy is the **distro's security contract**. Every
piece of the Elysium Linux ecosystem reads the policy:

- The **security dashboard** shows the user the list of
  `DISCLOSED_UNPATCHED` CVEs (the CVEs that are currently
  exposing the user).
- The **update manager** reads the policy's `severityResponseHours`
  to schedule the next update (a CRITICAL CVE triggers an
  immediate update; a LOW CVE is batched into the monthly
  release).
- The **package manager** reads the policy's
  `severityDisclosureDelayHours` to schedule the CVE
  disclosure (a CRITICAL CVE is disclosed immediately; a LOW
  CVE is held for 30 days).
- The **incident response playbook** (Phase 7) reads the
  policy to know the SLA + the on-call escalation.

The policy is the **single source of truth** for "how does
Elysium Linux handle CVEs?".
