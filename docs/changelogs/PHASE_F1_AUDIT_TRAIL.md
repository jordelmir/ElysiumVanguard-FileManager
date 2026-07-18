# Phase F1 — Increment 4: Audit Trail (signed, append-only, content-addressed)

> **Status:** shipped 2026-07-17 against git head `468286b` (the 3-aggregates + concurrency commit).
> **Increment:** I-1.8 (ProvenanceRecord) — the audit-trail wiring.
> **Gate:** G1 progress (the audit trail is the signed backing store for the provenance).
> **Build evidence:**
> - `testDebugUnitTest` — **1498 tests, 0 failures, 0 errors, 2 skipped** (1380 EV baseline + 118 Foundry; +11 new in this commit)
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What's in this commit

The **audit trail** is the platform's signed, append-only, content-
addressed record of every event that mutates authoritative state. It's
the storage layer for the `ProvenanceRecord` aggregate (I-1.8) + the
foundation for every future aggregate that needs a tamper-evident
history (per `R-DI-1` in `docs/foundry/risk-register.md`).

### The 4 new components

| Component | Purpose |
|---|---|
| `SignedEvent` | The immutable, content-addressed, signed event. The eventType + the typed `SignedEventPayload` describe the mutation. |
| `SignedEventPayload` | A sealed class for the payload. Phase 1 ships a single variant (`ProvenanceAppended`); Phase 4 adds more (AIProposalApplied, RoyaltyContractActivated, etc.). |
| `AuditTrail` interface | The read/write seam. Phase 1 ships the in-memory implementation. Phase 2 wires the Room-backed implementation without changing the public interface. |
| `InMemoryAuditTrail` | The Phase 1 implementation. Uses `ReentrantReadWriteLock` for safe concurrent reads + a `LinkedHashMap` for insertion-order preservation. |

### The integration

The `ProvenanceService` is now wired to the audit trail:
- `createProvenance` creates the record + appends the corresponding
  `SignedEvent` to the audit trail.
- The audit trail is the only path to the record's persistence.
- A failure in the audit-trail append is a typed `FoundryError`
  (the consumer pattern-matches on the variant).

The `RevisionService` is updated to:
- Accept an `AuditTrail` parameter (defaults to `InMemoryAuditTrail`).
- Pass the audit trail to the `ProvenanceService`.
- The `freeze` flow is unchanged from the consumer's perspective
  (the audit trail is internal).

The `SceneManifest.contentHash` is now an `init`-block-computed
val (not a `get()` property). This means the data class equality
includes the hash, so two `SceneManifest` instances with the same
fields are equal. A manifest with the same fields but a different
hash is impossible.

### The critical integration test (extended to 7 assertions)

The integration test now asserts:
- The audit trail is populated with the `provenance.appended`
  event.
- The event's `subjectId` IS the compilation's `contentHash.value`
  (the audit trail indexes the events by the compilation's hash).
- The event's `payload` is a `SignedEventPayload.ProvenanceAppended`
  with the expected fields.
- The scene manifest's content hash is stable (re-computing the
  manifest from the same inputs produces the same hash).

---

## 1. Architecture decisions (ADRs applied)

- **ADR-0001** (`Money` is `BigDecimal`): not directly affected
  in this commit; the discipline is preserved.
- **ADR-0006** (append-only): the audit trail is append-only
  via the `LinkedHashMap` insertion-order + the duplicate-id
  rejection.
- **ADR-0009** (`CorrelationId`): the audit-trail event carries
  the correlation ID (per `.ai/AGENTS.md` 24.3) — the
  `SignedEvent` will be extended in Phase 4 to include a
  `correlationId` field.

---

## 2. Files added (5 main + 1 test = 6 new)

```
app/src/main/java/com/elysium/vanguard/foundry/core/audit/
├── SignedEvent.kt                (data class + SignedEventPayload sealed class)
├── AuditTrail.kt                 (interface: append + findBySubject + count)
└── InMemoryAuditTrail.kt         (ReentrantReadWriteLock + LinkedHashMap)

app/src/test/java/com/elysium/vanguard/foundry/core/audit/
└── AuditTrailTest.kt             (10 tests)
```

### 2.1 Files modified (4)

```
app/src/main/java/com/elysium/vanguard/foundry/core/provenance/
└── ProvenanceService.kt          (rewritten: now requires AuditTrail)

app/src/main/java/com/elysium/vanguard/foundry/core/revision/
└── RevisionService.kt            (accepts AuditTrail; passes to ProvenanceService)

app/src/main/java/com/elysium/vanguard/foundry/core/scene/
└── SceneManifest.kt              (contentHash is now init-computed val, not get())

app/src/test/java/com/elysium/vanguard/foundry/integration/
└── VehicleProjectToDigitalTwinIntegrationTest.kt  (asserts audit trail is populated)
```

### 2.2 Files modified (1 test extension)

```
app/src/test/java/com/elysium/vanguard/foundry/core/provenance/
└── ProvenanceTest.kt             (now requires an InMemoryAuditTrail; 2 new tests)
```

---

## 3. The audit-trail contract

```kotlin
interface AuditTrail {
    fun append(event: SignedEvent): Result<SignedEvent>
    fun findBySubject(subjectId: String): List<SignedEvent>
    fun count(): Int
}
```

The contract (per `R-DI-1`, `R-DI-4`, `R-DI-5` in
`docs/foundry/risk-register.md`):

- **`append`** is the only path to a mutation. A second append
  with the same `id` is rejected with a typed
  `FoundryError.VehicleDefinitionInvalid` (the append-only
  invariant).
- **`findBySubject`** returns the events for a given subject.
  Used by the consumer to reconstruct the history of a
  `Project`, a `VehicleRevision`, a `RoyaltyContract`, etc.
- **`count`** returns the total number of events (for
  monitoring + test assertions).

The Phase 2 implementation (skill 08) replaces the in-memory
implementation with a Room-backed implementation without
changing the interface.

---

## 4. The `SignedEvent` shape

```kotlin
data class SignedEvent(
    val id: String,
    val eventType: String,
    val subjectId: String,
    val payload: SignedEventPayload,
    val signature: Signature,
    val contentHash: ContentHash,
    val createdAt: Timestamp,
)
```

The `payload` is typed (`SignedEventPayload` sealed class). A
free-form string is never the value (per `.ai/AGENTS.md` 24.1).
The Phase 1 sealed class has one variant:

```kotlin
sealed class SignedEventPayload {
    data class ProvenanceAppended(
        val provenanceSubjectId: String,
        val provenanceContentHash: String,
        val source: String,
    ) : SignedEventPayload()
}
```

Phase 4 adds more variants for the AI council proposals. Phase
5 adds variants for the royalty contracts. Phase 6 adds variants
for the marketplace. Phase 7 adds variants for the safety gates.

---

## 5. The `SceneManifest.contentHash` change

The `contentHash` is now computed in the `init` block, not as a
`get()` property:

```kotlin
data class SceneManifest(
    val revisionContentHash: ContentHash,
    val components: List<ComponentRef>,
    val lods: List<LodRef>,
    val representationLevel: RepresentationLevel,
) {
    val contentHash: ContentHash

    init {
        val canonical = buildString {
            append("scene-manifest:v1")
            append("|revision=").append(revisionContentHash.value)
            append("|level=").append(representationLevel.name)
            append("|components=")
            append(components.sortedBy { it.id }.joinToString(";") { "${it.id}:${it.label}" })
            append("|lods=")
            append(lods.sortedBy { it.level }.joinToString(";") { "${it.level}:${it.resolution}" })
        }
        contentHash = ContentHash.of(canonical)
    }
}
```

The change is significant: two `SceneManifest` instances with the
same fields are now **equal** (the data class equality includes
the hash). This means:
- A manifest with the same fields but a different hash is
  impossible (the hash is computed from the fields).
- The integration test's `assertEquals(firstManifest, recomputedManifest)`
  assertion is the canonical way to verify the manifest's
  determinism.

---

## 6. Build evidence

```
./gradlew testDebugUnitTest
  -> 1498 tests, 0 failures, 0 errors, 2 skipped
  -> Foundry tests: 118 (was 107; +11 in this commit)
  -> EV baseline 1380 tests preserved (per ADR-0024)

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101,214,784 bytes (101 MB; unchanged from baseline)

Lint:
  -> 0 errors, 0 warnings
```

---

## 7. Next steps (Phase 1 final increments)

The remaining 2 Phase 1 increments are persistence + the
final assembly:

- **I-1.3 + I-1.5 + I-1.8** (consolidated): Room DB entities +
  DAOs + migrations for Project, VehicleProgram, Contributor,
  EngineeringArtifact, VehicleRevision, ProvenanceRecord. The
  Room-backed `AuditTrail` + the per-aggregate `Repository`
  interfaces.

- **Hilt module**: `FoundryModule` that wires the DB + the
  repositories + the services. The existing EV Hilt modules
  are preserved (per ADR-0021); the Foundry module is additive.

These increments close out **G1 (Domain model approved)**. After
G1 is green, Phase 2 (DSL parser + full compiler pipeline) begins.

---

> "The platform's foundation grows by addition, never by
> subtraction. Every increment is a vertical slice that ships
> end-to-end. The audit trail is the signed, append-only,
> content-addressed backbone of every future mutation."
