# Phase 100 — Kill Switch UI (3-step confirm + irreversible wipe)

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Phase        | 100                                                                  |
| Date         | 2026-07-20                                                           |
| Commit       | (this commit)                                                        |
| Depends on   | Phase 76 (signature check) + Phase 89 (sandbox) + Phase 100 (audit)  |
| ADR          | (none — closes gap #7 from the vision audit)                         |

## What this phase does

The vision calls for a Zero Trust surface that includes a
**kill switch**: when the device is lost, compromised, or
about to be decommissioned, the user can wipe every piece
of runtime data in one tap. The operation is
**irreversible** — there is no undo. The vision audit
explicitly called out the missing kill switch UI as gap #7
("Kill switch UI — grep `killSwitch` retornó VACÍO").

Phase 100 closes that gap:

- **`KillSwitch`** — the core class. Wipes every Room
  table, every on-disk wipeable directory, the secret
  store, and stops every running process. Records a
  `KILL_SWITCH_TRIGGERED` audit event as the last
  surviving trace.
- **`RuntimeDataWiper` interface** + **`RuntimeDataWiperImpl`**
  production adapter. The interface is a narrow seam
  (one method: `wipeAll()`) the kill switch consumes;
  the impl wraps the `RuntimeDatabase` and calls
  `clear()` on every DAO.
- **`WipeableDirectories`** + **`LaunchedProcessHandlesProvider`**
  typed wrappers (Hilt can't bind raw `List<File>` or
  `Function0`, so the wrappers carry the type).
- **`SecurityEventType.KILL_SWITCH_TRIGGERED`** + the
  matching `SecurityEventDetails.KillSwitchDetails`
  variant (the audit event records the reason + the
  inventory of what was wiped).
- **`SecretStore.clear()`** — bulk-wipe every secret
  (the kill switch is the canonical caller).
- **`KillSwitchViewModel`** — `@HiltViewModel` with a
  3-step state machine (`INITIAL` → `READY_TO_CONFIRM` →
  `READY_TO_EXECUTE` → `COMPLETED`).
- **`KillSwitchScreen`** — Compose UI with the 3-step
  flow. The user types `WIPE` + a reason to advance;
  the `EXECUTE` button is bright red and at the bottom.
- **Nav route** `kill_switch` wired to the screen.
- **Hilt providers** for `SecurityAudit` + `SecretStore`
  + `WipeableDirectories` + `LaunchedProcessHandlesProvider`
  + `RuntimeDataWiper`.
- **6 new tests** (full JVM test coverage of the kill
  switch happy path + edge cases). **3521/3521 green**.

## Files added

| File | Purpose |
|------|---------|
| `core/security/KillSwitch.kt` | The kill switch + `RuntimeDataWiper` interface + `KillSwitchResult` sealed class |
| `core/security/RuntimeDataWiperImpl.kt` | Production adapter (10 lines; wraps `RuntimeDatabase.clear()`) |
| `features/killswitch/KillSwitchViewModel.kt` | The 3-step state machine |
| `features/killswitch/KillSwitchScreen.kt` | The Compose UI |

## Files modified

| File | Change |
|------|--------|
| `core/security/SecurityAudit.kt` | Added `SecurityEventType.KILL_SWITCH_TRIGGERED` + `KillSwitchDetails` variant |
| `core/security/SecretStore.kt` | Added `clear()` method |
| `core/security/SecurityModule.kt` | Added 4 Hilt `@Provides` (audit, secret store, wipeable dirs, process handles, data wiper) |
| `MainActivity.kt` | Added `kill_switch` nav route |
| `app/src/test/.../SecurityZeroTrustTest.kt` | Updated event-type count from 5 to 6 |

## Algorithm — `trigger(reason: String)`

```kotlin
fun trigger(reason: String): KillSwitchResult = lock.withLock {
    if (triggered) return AlreadyTriggered
    if (reason.isBlank()) return Failure("reason must not be blank")
    try {
        // 1. Stop every running process.
        for (handle in launchedProcessHandles.handles()) {
            runCatching { handle.stop() }
        }
        // 2. Wipe the Room database.
        runBlocking(Dispatchers.IO) {
            dataWiper.wipeAll()
        }
        // 3. Wipe the on-disk directories.
        wipeDirectories()
        // 4. Wipe the secret store.
        runCatching { secretStore.clear() }
        // 5. Record the audit event last.
        audit.record(KILL_SWITCH_TRIGGERED)
        triggered = true
        Success
    } catch (e: Exception) {
        Failure("kill switch failed: ${e.message}")
    }
}
```

**Order matters**: we stop processes first (so a
half-wiped state can't be held open by a running
binary), wipe the database next (the per-DAO `clear()`
is idempotent), then directories, then secrets,
then record the audit event (the audit is the last
survivor — a post-mortem investigator can see the
trigger event after the wipe).

## Algorithm — 3-step confirm

```
Step 1: INITIAL
  - Show the warning text
  - User types a reason (recorded in audit)
  - User types WIPE in the confirm field
  - State advances to READY_TO_CONFIRM

Step 2: READY_TO_CONFIRM
  - Show the inventory of what will be wiped
  - User presses BACK (returns to Step 1)
  - User presses CONTINUE (advances to Step 3)

Step 3: READY_TO_EXECUTE
  - Show the final warning
  - User presses BACK (returns to Step 2)
  - User presses EXECUTE (triggers the kill switch)

Step 4: COMPLETED
  - Show the success indicator
  - User presses DONE (navigates back)
```

The friction is the feature. A kill switch is a
one-way operation; the 3-step confirm prevents
accidental triggers.

## Hilt provider pattern

Hilt can't directly bind `List<File>` or
`Function0<...>`. The pattern:

```kotlin
data class WipeableDirectories(val dirs: List<File>)
fun interface LaunchedProcessHandlesProvider {
    fun handles(): List<LaunchedProcess>
}
```

Both are typed wrappers. The `@Provides` methods
return the wrapper; the kill switch consumes the
wrapper. Tests pass a 5-line fake for each.

## Test count

3521/3521 green (6 new):
- 6 `KillSwitchTest` (full wipe happy path, blank
  reason rejection, idempotency, `hasBeenTriggered`,
  process-stop before wipe, robustness to per-dir
  failures)

## What this does NOT do (deferred)

- **Biometric confirmation** — Phase 100's confirm
  flow is text + button. A real production kill
  switch should require biometric (fingerprint /
  face) before the EXECUTE button is enabled.
  Phase 100+ will add a `BiometricPrompt` API call.
- **Remote kill switch** — the user can wipe the
  device from a web dashboard (the Foundry's
  Command Core sends a "wipe" intent to the
  device). Phase 100+ adds the server-side API.
- **Encrypted wipe** — the wipe deletes the file
  blocks but doesn't zero the underlying storage.
  A real production kill switch uses ATA Secure
  Erase (for HDDs) or crypto-shred (for SSDs with
  hardware encryption). Phase 100+ adds the
  platform-specific secure-erase path.

## Build verification

- `./gradlew testDebugUnitTest` — 3521/3521 green
- `./gradlew assembleDebug` — APK built
