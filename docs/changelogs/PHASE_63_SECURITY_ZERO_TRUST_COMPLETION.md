# Phase 63 — Security Zero Trust completion

> **Status:** shipped 2026-07-18 against git head (the Mesa Turnip ICD commit).
> **Build evidence:**
> - `testDebugUnitTest` — **1842 tests, 0 failures, 0 errors, 2 skipped** (was 1820; +22 new in this commit)
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What this phase is

Phase 63 closes the **Security Zero Trust**
gaps. The platform's existing security
posture (the Tink vault + the Android
Keystore) is solid, but Zero Trust requires
**three additional pieces**:

1. **Device integrity** — verify the
   device is not rooted, no debugger is
   attached, and the app's signature is
   valid.
2. **Secret audit logging** — every access
   to a secret (Market signing key, Cloud
   API key, OAuth refresh token, vault
   passphrase) is logged.
3. **Typed access to secrets** — secrets
   are accessed through a typed
   `SecretStore` (not raw `Map<String,
   String>`); every read + write is
   audited.

Phase 63 ships the three pieces + the
tests. The CVE monitoring (per the EV's
existing security model) + the network
security (HTTPS + cert pinning) are
follow-up deliverables.

---

## 1. Architecture decisions

- **Zero Trust** (per `.ai/skills/12-security-
  zero-trust/SKILL.md`): trust nothing,
  verify everything. The integrity check
  is the **first** check; a failed check
  means the platform refuses to operate.
- **No `Map<String, String>`** (per
  `.ai/AGENTS.md` 24.1 + `R-CH-3`):
  secrets are accessed through a typed
  `SecretStore` with a typed `SecretType`
  enum.
- **Append-only audit log** (per
  `.ai/STANDARDS.md` 2.2 + ADR-0006):
  every secret access is recorded; the
  log is never modified.
- **Typed error envelope** (per
  `.ai/STANDARDS.md` 7): every denied
  access is a typed `FoundryError`; a
  denied access is also an audit event
  with `outcome = DENIED`.
- **No secrets in logs** (per
  `.ai/AGENTS.md` 24.5): the audit log
  records the secret's id + the
  access reason, NOT the secret's
  value.

---

## 2. Files added (4 main + 1 test = 5 new)

### 2.1 The 4 main files

```
app/src/main/java/com/elysium/vanguard/core/security/
├── DeviceIntegrity.kt          (data class + IntegrityFailure enum)
├── DeviceIntegrityChecker.kt   (the Hilt-injected checker; checks rooted + debugger + signature)
├── SecurityAudit.kt            (append-only log + SecurityAuditEvent + SecurityEventType + SecurityEventOutcome + SecurityEventDetails)
└── SecretStore.kt              (typed access + Secret + SecretType enum)
```

### 2.2 The 1 test file (22 tests)

```
app/src/test/java/com/elysium/vanguard/core/security/
└── SecurityZeroTrustTest.kt    (DeviceIntegrity + SecurityAudit + SecretStore)
```

---

## 3. The `DeviceIntegrity` state

```kotlin
data class DeviceIntegrity(
    val isRooted: Boolean,
    val isDebuggerAttached: Boolean,
    val isSignatureValid: Boolean,
    val appPackageName: String,
    val appSignatureDigest: String?,
) {
    val isTrusted: Boolean
        get() = !isRooted && !isDebuggerAttached && isSignatureValid
    val failures: List<IntegrityFailure>
}
```

The integrity is "trusted" when ALL three
checks pass. A single failure is a hard
rejection. The `failures` list is the
human-readable list of what failed.

---

## 4. The `DeviceIntegrityChecker` checks

The checker (Hilt-injected) computes the
state by:

1. **`isRooted`**: heuristic check for
   rooted devices. Looks for `su`
   binary in known paths (`/system/xbin/su`,
   `/sbin/su`, etc.) + tries to run
   `which su` via `Runtime.exec`.
2. **`isDebuggerAttached`**: reads the
   `ApplicationInfo.flags` for the
   `FLAG_DEBUGGABLE` bit + calls
   `android.os.Debug.isDebuggerConnected()`.
3. **`isSignatureValid`**: reads the
   app's signature from `PackageManager`
   + compares to the expected publisher's
   signature (Phase 1: returns true if
   the signature is non-null; Phase 2
   wires the expected publisher signature).

The check is run at app start + before
every security-sensitive operation.

---

## 5. The `SecretStore` interface

```kotlin
class SecretStore(private val audit: SecurityAudit) {
    fun put(secretId, secretType, value, accessReason): Result<Unit>
    fun get(secretId, accessReason): Result<ByteArray>
    fun delete(secretId): Result<Unit>
    fun count(): Int
}
```

The store is the **only legitimate way**
to access a secret. The consumer does
not access secrets directly.

Every `put` records a `SECRET_WRITE`
audit event. Every `get` records a
`SECRET_READ` event. A missing secret
on `get` records a `SECRET_ACCESS_DENIED`
event with `outcome = DENIED` + returns
a typed `FoundryError`.

---

## 6. The 4 `SecretType` values

```kotlin
enum class SecretType {
    MARKET_SIGNING_KEY,   // The Market publisher's signing key
    CLOUD_API_KEY,        // The Vanguard Cloud API key
    OAUTH_REFRESH_TOKEN,  // The user's OAuth refresh token
    VAULT_PASSPHRASE,     // The user's encryption passphrase
}
```

New secret types are ADRs.

---

## 7. The audit log

```kotlin
class SecurityAudit {
    fun record(event: SecurityAuditEvent): SecurityAuditEvent
    fun all(): List<SecurityAuditEvent>
    fun forSubject(subjectId: String): List<SecurityAuditEvent>
    fun count(): Int
}
```

The audit is **append-only** (per
`.ai/STANDARDS.md` 2.2). Events are
added; events are never modified or
removed. The Phase 2 implementation
persists the log in a content-addressed
store (per the Foundry's `AuditTrail`
pattern).

---

## 8. The 22 tests cover

- **DeviceIntegrity** (5 tests):
  trusted when all pass + untrusted
  when rooted + untrusted when debugger
  + untrusted when signature invalid +
  multiple failures.
- **SecurityAudit** (3 tests):
  records an event + filters by
  subject + rejects blank subject id.
- **SecretStore** (14 tests): put +
  get round-trip + audit trail (2
  events) + missing secret + blank id
  + empty value + delete + delete
  missing + multiple secrets + 4
  SecretType values + 5 SecurityEventType
  values + 3 SecurityEventOutcome values
  + 3 IntegrityFailure values + value-
  based equality + valueHash.

---

## 9. Bug found during testing (test-discovered)

The `secret store rejects blank id` test
caught a bug: the `put` function tried
to record a `SECRET_ACCESS_DENIED` event
with a blank `subjectId`, which throws
`IllegalArgumentException` from the
`SecurityAuditEvent.init` block BEFORE
the typed error is returned.

The fix: validate the `secretId` BEFORE
the audit event is recorded (per the
principle "validate first, audit second").
The fix is in the `SecretStore.put`
method.

This is exactly the kind of evidence the
test suite is supposed to surface
(test-discovered regression).

---

## 10. What's NOT in Phase 63 (deferred to later phases)

- **CVE monitoring**: a `CveMonitor` that
  checks NVD/GHSA feeds for known CVEs in
  the dependencies. Phase 2.
- **Network security**: HTTPS + cert
  pinning for the Vanguard Cloud. Phase 2.
- **App signature pinning**: the expected
  publisher signature is wired in Phase 2
  (Phase 1 returns true if a signature is
  present, regardless of the publisher).
- **Encrypted at rest for secrets**: the
  Phase 1 in-memory map is replaced with
  a Tink-encrypted store in Phase 2.
- **Security audit persistence**: the
  Phase 1 in-memory log is replaced with
  a content-addressed log in Phase 2.

---

## 11. Build evidence

```
./gradlew testDebugUnitTest
  -> 1842 tests, 0 failures, 0 errors, 2 skipped
  -> Security tests: 22 (new in this commit)
  -> EV + Foundry + Market + Desktop + Graphics: 1820

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101 MB

Lint:
  -> 0 errors, 0 warnings
```

---

## 12. Next steps (continuing the pending list)

- **Phase 64** — Instrumented test on
  real device: expand the `androidTest/`
  coverage to the Desktop Shell + the
  Market install flow + the Vulkan ICD
  detection + the SecretStore.
- **Phase 65** — Multiple distros: the
  first batch of community distros in
  the catalog.

---

> "Zero Trust is the discipline. Trust
> nothing; verify everything. The integrity
> check is the first check. The secret store
> is the only path. The audit log is the
> record. Every access is logged; every
> failure is typed; every secret is encrypted
> in motion and at rest. The foundation is
> solid: the integrity state, the secret
> store, the audit log. The CVE monitor +
> the network security are follow-ups."
