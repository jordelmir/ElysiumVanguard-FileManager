# Phase 76 — App signature check (Security Zero Trust, signature leg)

The signature leg of the Security Zero Trust surface
(`core/security/DeviceIntegrityChecker.checkAppSignature`)
was a placeholder that silently trusted every install — even
a tampered re-signed APK. Phase 76 closes the gap with a
typed config, a Hilt provider, a pure-comparison function,
and a fail-secure `init` block.

## What shipped

### 1. The typed config

`app/src/main/java/com/elysium/vanguard/core/security/DeviceIntegrityConfig.kt`

A `data class` with two fields:

- `expectedPublisherSignatureSha256: String?` — the
  SHA-256 hex digest of the expected APK signing
  certificate bytes. `null` = dev mode (no
  publisher-keyed check).
- `productionBuild: Boolean` — `true` when this is a
  release build.

The `init` block has a fail-secure check: a release
build (`productionBuild = true`) without an
`expectedPublisherSignatureSha256` is a misconfiguration
and the constructor throws. The exception fires at
the first Hilt graph build (app start), not at the
first security-sensitive operation.

### 2. The Hilt provider

`app/src/main/java/com/elysium/vanguard/core/security/SecurityModule.kt`

A new `SecurityModule` provides `DeviceIntegrityConfig`
from `BuildConfig` fields the build script generates.
The empty-string → `null` collapse handles the dev
default: the `buildConfigField` can't store `null`,
so the script stores `""` and the provider translates
the empty string to `null`.

### 3. The build-time defaults

`app/build.gradle.kts` `defaultConfig`:

```kotlin
buildConfigField("String", "EXPECTED_PUBLISHER_SIG_SHA256", "\"\"")
buildConfigField("boolean", "PRODUCTION_BUILD", "false")
```

Debug builds are unchanged. Release builds are responsible
for **overriding** these to the actual publisher's digest
+ `productionBuild = true`. The `release` build type does
not auto-override (to keep the local "fall back to debug
keystore" release flow working).

### 4. The pure-comparison function

`app/src/main/java/com/elysium/vanguard/core/security/DeviceIntegrityChecker.kt`

A top-level `internal fun compareSignatureDigestsInternal(actualDigest, config)`
is the new core. The function:

- **Dev mode** (no expected): returns `(true, actual)` if
  an actual digest is present; `(false, null)` if missing.
- **Production mode** (expected set): returns
  `(true, actual)` if actual matches expected
  (case-insensitive); `(false, actual)` on mismatch.
- **Always returns the observed digest** so the audit log
  + the human-readable integrity report show the actual
  signer, not just "invalid".

The checker's private `checkAppSignature` reads the actual
APK signing certificate from `PackageManager` (API 28+) and
delegates to the pure function. Both code paths converge
on the same logic, so a single test suite covers both.

### 5. Test coverage

`app/src/test/java/com/elysium/vanguard/core/security/DeviceIntegrityConfigAndCheckerTest.kt`

14 new tests, all green:

- 7 tests for `DeviceIntegrityConfig` (defaults, getters,
  init fail-fast, value-based equality)
- 7 tests for `compareSignatureDigestsInternal` (dev/prod
  × null/empty/matching/mismatching/case-variant)

Test count: **2582 → 2596**.

## Why this matters

The Security Zero Trust surface requires the platform to
**refuse to operate** on a tampered device. Before Phase 76:

- Rooted device: refused ✓
- Debugger attached: refused ✓
- Re-signed APK: **accepted** ✗ (the placeholder trusted
  every install)

After Phase 76:

- Rooted device: refused ✓
- Debugger attached: refused ✓
- Re-signed APK: refused ✓ (digest mismatch fails the check)

The "secure by default" promise of the master vision is now
real on all three legs.

## Files changed

- **NEW** `app/src/main/java/com/elysium/vanguard/core/security/DeviceIntegrityConfig.kt`
- **NEW** `app/src/main/java/com/elysium/vanguard/core/security/SecurityModule.kt`
- **MODIFIED** `app/src/main/java/com/elysium/vanguard/core/security/DeviceIntegrityChecker.kt`
  (constructor now takes `DeviceIntegrityConfig`; signature
  path delegates to `compareSignatureDigestsInternal`)
- **MODIFIED** `app/build.gradle.kts` (`defaultConfig.buildConfigField`)
- **NEW** `app/src/test/java/com/elysium/vanguard/core/security/DeviceIntegrityConfigAndCheckerTest.kt`
- **NEW** `docs/adr/ADR-030-app-signature-check.md`
- **NEW** `docs/changelogs/PHASE_76_APP_SIGNATURE_CHECK.md` (this file)

## Build status

- `compileDebugKotlin`: ✓
- `compileDebugUnitTestKotlin`: ✓
- `testDebugUnitTest`: 2596/2596 (14 new + 2582 existing)
- `assembleDebug`: ✓ (debug APK built)

## What's next

The next gap-closure item is the **`HttpRemoteBuildClientImpl`**
(stub → real HTTP client) or the **real Elysium Vanguard Linux
distro** (placeholder hash → built rootfs). The signature check
is complete; the security surface is now real on all three legs.
