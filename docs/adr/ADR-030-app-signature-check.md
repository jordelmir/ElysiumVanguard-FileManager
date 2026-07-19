# ADR-030 — App signature check (the publisher-keyed leg of the Security Zero Trust surface)

Status: **Accepted** (Phase 76, 2026-07-19)
Owners: Security + Build
Supersedes: the docstring of `DeviceIntegrityChecker.checkAppSignature` (Phase 1, 2026-07-15) that said "Phase 1: no expected signature configured; treat as valid to keep the development flow unblocked. Phase 2 wires the expected publisher's signature + verifies against it."
Superseded by: none

## Context

The Security Zero Trust surface (per `.ai/skills/12-security-zero-trust/SKILL.md`) requires the platform to verify **three** things on every device, on every launch:

1. **Rooted** — the device's bootloader is unlocked OR `su` is available. A rooted device can bypass the Android security model.
2. **Debugger** — a debugger is attached to the process. A debugger can read the process's memory + the Tink keyset.
3. **Signature** — the app's signing certificate matches the expected publisher. A tampered signature means the app was re-signed (e.g. by a malicious actor distributing a modified APK).

The first two were already real checks in the existing `DeviceIntegrityChecker` (Phase 7.4). The third was a placeholder:

> *"Phase 1: no expected signature configured; treat as valid to keep the development flow unblocked. Phase 2 wires the expected publisher's signature + verifies against it."*

The placeholder made the signature leg of the Zero Trust surface **silently succeed on every install**, even a tampered re-signed APK. A re-signed APK would have a different signing certificate than the one published by Elysium Vanguard; the placeholder trusted it anyway. The master vision's "secure by default" promise was not being kept on the signature leg.

Phase 76 closes this gap with a typed config (`DeviceIntegrityConfig`), a Hilt provider (`SecurityModule`), a pure-comparison function (`compareSignatureDigestsInternal`), and a fail-secure `init` block.

## Decision

### 1. Typed config (`DeviceIntegrityConfig`)

A new `data class` with two fields:

- `expectedPublisherSignatureSha256: String?` — the SHA-256 hex digest of the expected APK signing certificate bytes. Lowercase, no `:` separators (the `MessageDigest.toHex()` format). `null` = dev mode.
- `productionBuild: Boolean` — `true` when this is a release build.

The `init` block has a single fail-secure check:

```kotlin
init {
    if (productionBuild) {
        check(expectedPublisherSignatureSha256 != null) {
            "DeviceIntegrityConfig: productionBuild=true requires " +
                "expectedPublisherSignatureSha256 to be set. Wire the " +
                "release build's buildConfigField in app/build.gradle.kts."
        }
    }
}
```

A release build that forgot to set the expected digest fails at construction time. The check fires at the first `DeviceIntegrityConfig` instantiation (the Hilt graph), so the misconfiguration is caught at app start, not at the first security-sensitive operation.

### 2. Hilt provider (`SecurityModule`)

The `SecurityModule` (new in Phase 76) provides `DeviceIntegrityConfig` from the `BuildConfig` fields the build script generates:

```kotlin
@Provides
@Singleton
fun provideDeviceIntegrityConfig(): DeviceIntegrityConfig = DeviceIntegrityConfig(
    expectedPublisherSignatureSha256 = BuildConfig.EXPECTED_PUBLISHER_SIG_SHA256
        .takeIf { it.isNotEmpty() },
    productionBuild = BuildConfig.PRODUCTION_BUILD,
)
```

The empty-string → `null` collapse is deliberate: the `buildConfigField` can't store `null` directly, so the build script stores `""` for dev and the provider translates the empty string to `null`. The `init` block's fail-secure check would otherwise fire in dev.

### 3. Build-time `buildConfigField` defaults

The `app/build.gradle.kts` `defaultConfig` block declares:

```kotlin
buildConfigField("String", "EXPECTED_PUBLISHER_SIG_SHA256", "\"\"")
buildConfigField("boolean", "PRODUCTION_BUILD", "false")
```

The dev default is permissive: empty expected + `productionBuild = false`. A release build is responsible for **overriding** these to the actual publisher's digest + `productionBuild = true`. Until they are overridden, the build is "dev mode" — the integrity checker's signature path accepts the actual signature as valid if one is present.

The `release` build type does **not** auto-override these. The reason is that auto-overriding in `release` would force every developer who runs a release build locally to set the digest, breaking the existing "fall back to debug keystore" release flow (see `app/build.gradle.kts` `buildTypes.release.signingConfig`). The release override is a deliberate, manual step the release engineer takes at publish time.

### 4. Pure-comparison function (`compareSignatureDigestsInternal`)

The checker's signature path delegates to a top-level `internal` function:

```kotlin
internal fun compareSignatureDigestsInternal(
    actualDigest: String?,
    config: DeviceIntegrityConfig,
): Pair<Boolean, String?>
```

The function is `internal` (not `private`) so the JVM test suite can drive every (actual, expected, dev/prod) combination without standing up the Android `PackageManager` or the `Context` the checker's constructor requires. The function returns `(valid, observed)`:

- `valid = true` when the comparison passed (or dev-mode + actual present)
- `observed` is the actual digest the checker recorded (used for the audit log + the human-readable integrity report)

The comparison is case-insensitive (`String.equals(other, ignoreCase = true)`) so the digest format (`MessageDigest.toHex()` lowercase vs. an uppercase reading from `apksigner`) does not matter.

### 5. The checker's signature path

The private `checkAppSignature(config: DeviceIntegrityConfig)` reads the actual APK signing certificate's SHA-256 from `PackageManager.getPackageInfo(GET_SIGNING_CERTIFICATES)` (API 28+) and delegates to `compareSignatureDigestsInternal`. The function returns the `(valid, observed)` pair; the `DeviceIntegrity` records the observed digest even when the comparison fails, so the audit log + the integrity report show the actual signer (not just "invalid").

## Consequences

### Positive

- **The signature leg of Zero Trust is real.** A re-signed APK with a different signing certificate now fails the integrity check. The platform refuses to operate.
- **Fail-secure at construction time.** A release build that forgot the expected digest fails at the first `DeviceIntegrityConfig` instantiation (Hilt graph build), not at the first security-sensitive operation. The misconfiguration is impossible to ship.
- **Testable.** 14 JVM tests cover the full truth table: dev/prod × null/empty/matching/mismatching/case-variant. The Android-side `PackageManager` path is exercised at app start; the pure logic is exercised in milliseconds.
- **No behavior change for dev.** Debug builds are unchanged: empty expected + `productionBuild = false` = the actual signature is accepted if one is present. The development flow stays unblocked.
- **Documented release procedure.** The `DeviceIntegrityConfig` KDoc enumerates the three steps to publish a real release: `apksigner verify --print-certs` → `buildConfigField` → flip `PRODUCTION_BUILD`.

### Negative / risks

- **The release build is still "dev mode" by default.** Until the release engineer manually overrides the `buildConfigField` values in `app/build.gradle.kts` (or in a CI override file), the published APK has the same signature surface as the debug build. This is intentional (so local release builds still work) but it is also a foot-gun: a published release with the default `PRODUCTION_BUILD = false` has the same gap Phase 76 was meant to close.
- **The release procedure is not automated.** There is no CI check that fails a release build with `PRODUCTION_BUILD = false`. A future Phase should add a CI guard that requires `PRODUCTION_BUILD = true` for any build published to a public store.
- **The checker's signature path reads the signing certificate only at `check()` time.** A repackaged APK that swapped the certificate at install time (via `pm install`) will still be caught at the next `check()` call. A long-lived session that doesn't re-check is not safe; the Security Zero Trust surface requires `check()` before every security-sensitive operation, which the platform already does.
- **No certificate pinning across updates.** The expected digest is the current publisher's digest; an update that rotates the publisher's keystore (e.g. compromised key) requires a coordinated config update + a re-publish. The platform does not currently support multiple valid digests.

## What we are NOT doing (yet)

- **Hard-fail the app on signature mismatch.** Currently, a failed `check()` returns a `DeviceIntegrity` with `isSignatureValid = false`; the consumer decides what to do (the existing zero-trust policy is to refuse to operate, but the platform's surface today records the failure and continues). A future phase should add a "kill switch" that exits the app on signature mismatch in production mode.
- **Roll the expected digest into the platform's attestation keychain.** The current design reads the digest from `BuildConfig` (compile-time). A future phase could read it from a remote attestation endpoint (Play Integrity API, custom server) for higher assurance. Not in scope for Phase 76.
- **Audit persistence.** The signature failure is recorded in the in-memory `SecurityAudit`. The persistent on-disk log is the existing `RuntimeEventLog` (NDJSON at `<filesDir>/runtime/audit.ndjson`); the security audit does not yet write to it. A future phase (audit persistence) closes this.

## Test plan (14 tests, all green in `DeviceIntegrityConfigAndCheckerTest`)

- `config defaults are dev mode` — getters return the dev defaults
- `config getters return the supplied values` — value-based getter behavior
- `config init does not fail when productionBuild is false and expected is null` — dev default
- `config init does not fail when productionBuild is false and expected is set` — release-candidate
- `config init does not fail when productionBuild is true and expected is set` — production default
- `config init fails fast when productionBuild is true and expected is null` — fail-secure check
- `config is a data class with value-based equality` — Kotlin data class contract
- `compare returns invalid when dev mode and actual digest is null` — dev + no signature
- `compare returns valid when dev mode and actual digest is present` — dev + signature
- `compare returns invalid when production mode and actual digest is null` — prod + tampered
- `compare returns valid when production mode and actual matches expected` — prod + matching
- `compare returns invalid when production mode and actual differs from expected` — prod + mismatched
- `compare is case insensitive on the digest comparison` — casing tolerance
- `compare rejects empty actual digest in production mode` — empty string treated as null

## References

- `core/security/DeviceIntegrityConfig.kt` — the typed config
- `core/security/DeviceIntegrityChecker.kt` — the check + the pure-comparison function
- `core/security/DeviceIntegrity.kt` — the integrity state (`isTrusted`, `failures`)
- `core/security/SecurityModule.kt` — the Hilt provider
- `app/build.gradle.kts` (`defaultConfig.buildConfigField`) — the build-time defaults
- `test/security/DeviceIntegrityConfigAndCheckerTest.kt` — the 14-test truth table
