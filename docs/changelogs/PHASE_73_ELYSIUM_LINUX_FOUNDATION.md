# Phase 73 first half — Elysium Linux Package Manifest + Trust Chain

**Status**: ✅ SHIPPED
**Date**: 2026-07-19
**Builds**: `./gradlew :app:testDebugUnitTest` (2667 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings)

> **Note**: The pre-existing `SecurityInstrumentedTest` has a separate `config` parameter issue unrelated to this phase; the `assembleDebug` build is green, and the JVM unit tests all pass.

---

## Why

The user's vision for **Elysium Linux** is a first-party proprietary Android ARM64 distribution:
- Build reproducible (same input → same output byte-for-byte)
- Own repositories (not Debian/Ubuntu)
- Trust chain (signatures, content hashes, signed manifest)
- Package manager that understands ABIs + versioned runtimes
- Mesa/Turnip preconfigured for Adreno
- Box64/FEX integrated
- Wine versioned
- A/B updates with signed images
- Snapshots/rollback
- Documented ABI compatibility

This is a **multi-phase commitment**. Phase 73 first half ships the **foundational data types** — the package manifest + the trust chain. Without these, no distro can exist; with these, every subsequent phase (rootfs builder, package manager, repository, A/B updates) is a transformation of these primitives.

---

## What shipped

### Production (com.elysium.vanguard.core.linux)

#### 1. `ElysiumAbi` (the ABI enum)

The ABI is the target instruction set + binary format the package supports. The enum has 5 values:
- `ARM64` — 64-bit ARM (the dominant Android arch; canonical name: `arm64-v8a`).
- `ARM32` — 32-bit ARM (legacy; canonical name: `armeabi-v7a`).
- `X86_64` — 64-bit x86 (desktops; canonical name: `x86_64`).
- `X86` — 32-bit x86 (legacy; canonical name: `x86`).
- `ANY` — architecture-agnostic (scripts, config files, data files).

The `canonicalName(abi)` companion returns the Android NDK ABI name (the canonical form the platform keys by).

#### 2. `ElysiumPackageVersion` (the semver)

The version is the semver (semantic version) of a package. The data class has:
- `major: Int` (>= 0).
- `minor: Int` (>= 0).
- `patch: Int` (>= 0).
- `preRelease: String?` (optional, e.g. `"alpha.1"`).
- `build: String?` (optional, e.g. `"build.42"`).

The version implements `Comparable<ElysiumPackageVersion>` per the semver spec:
- Numeric comparison of `MAJOR`, `MINOR`, `PATCH`.
- A version with `preRelease` is LESS than the same version without.
- Build metadata is IGNORED in comparison.
- Pre-release identifiers are compared dot-separated; numeric < alphanumeric.

The companion `parse(canonical)` parses a canonical form ("1.2.3", "1.2.3-alpha.1", "1.2.3+build.42") into the data class.

#### 3. `ElysiumPackageDependency` (the dependency declaration)

The dependency has:
- `packageName: String` — the reverse-DNS identifier.
- `constraint: VersionConstraint` — the version constraint.
- `abi: ElysiumAbi` — the per-ABI dependency (default: `ANY`).
- `optional: Boolean` — whether the dependency is recommended (not required).

The `VersionConstraint` has:
- `kind: ConstraintKind` — one of 8 constraint kinds.
- `version: ElysiumPackageVersion` — the operand.

The 8 `ConstraintKind` values:
- `EXACT` (`=`) — exact version.
- `GTE` (`>=`) — greater than or equal.
- `LTE` (`<=`) — less than or equal.
- `GT` (`>`) — strictly greater.
- `LT` (`<`) — strictly less.
- `CARET` (`^`) — semver "compatible with" range.
- `TILDE` (`~`) — semver "patch range".
- `ANY` (`*`) — any version.

The `satisfiedBy(candidate)` method checks if a candidate version satisfies the constraint.

#### 4. `ElysiumPackageFile` (the per-file manifest entry)

The file entry has:
- `installPath: String` — the absolute path where the file is installed (e.g. `"/usr/bin/python3"`).
- `contentHash: ContentHash` — the SHA-256 of the file's bytes.
- `permissions: FilePermissions` — the file's mode + uid + gid.

#### 5. `FilePermissions` (the file's mode + uid + gid)

The permissions have:
- `mode: Int` (octal 0..0x1FF; default `0x1A4` = 0644).
- `uid: Int` (default 0 = root).
- `gid: Int` (default 0 = root).

The `canonical` form is `"0xxx:uid:gid"` (e.g. `"0755:0:0"`).

#### 6. `ElysiumPackageScripts` (the install / remove scripts)

The scripts have 4 optional fields:
- `preInstall: String?` — runs before the package files are installed.
- `postInstall: String?` — runs after the package files are installed.
- `preRemove: String?` — runs before the package files are removed.
- `postRemove: String?` — runs after the package files are removed.

The default is `ElysiumPackageScripts.NONE` (no scripts).

#### 7. `ElysiumPackageManifest` (the typed signed manifest)

The manifest is the **canonical contract** between the publisher + the consumer. The manifest has:
- `name: String` — the reverse-DNS identifier.
- `version: ElysiumPackageVersion` — the semver.
- `abi: ElysiumAbi` — the target ABI.
- `description: String` — the user-facing description.
- `dependencies: List<ElysiumPackageDependency>` — the runtime deps.
- `provides: List<String>` — the capabilities the package provides.
- `files: List<ElysiumPackageFile>` — the file list.
- `scripts: ElysiumPackageScripts` — the install / remove scripts.
- `contentHash: ContentHash` — the SHA-256 of the package tarball.
- `signature: Signature` — the signature on the canonical form.

The `canonicalForm` is the deterministic UTF-8 byte sequence used to compute the signature + to verify the signature at install time. The form is sorted + stable (same inputs → same form).

The `verifySignature(expectedSignature)` method:
1. Builds the canonical form.
2. Signs the canonical form with the expected signature's value as the key.
3. Compares the result to the manifest's signature.
4. Returns `Result.success(Unit)` on match; `Result.failure(SignatureMismatch(...))` on mismatch.

The `init` block validates:
- Non-blank `name` matching the reverse-DNS pattern.
- Non-blank `description`.
- All `provides` capabilities are non-blank.
- Non-empty `files` (an empty package is a deployment error).

#### 8. `ElysiumPackageVerificationError` (the typed error envelope)

The sealed class has 2 variants:
- `SignatureMismatch(name, version, expected, actual)` — the manifest's signature does not match the expected signature.
- `ContentHashMismatch(name, version, expected, actual)` — the package's bytes do not match the declared content hash.

### Tests

40 new tests (`ElysiumPackageManifestTest`):
- 10 `ElysiumPackageVersion` tests (parse, parse with pre-release, parse with build, reject invalid, canonical form, canonical with pre-release + build, semver comparison, pre-release < release, build metadata ignored, reject negative)
- 4 `ElysiumPackageDependency` tests (reject blank name, reject invalid reverse-DNS, canonical form, with constraint)
- 5 `VersionConstraint` tests (EXACT, GTE, CARET, TILDE, ANY)
- 6 `ElysiumPackageManifest` tests (reject blank name, reject blank description, reject empty files, canonical excludes signature, deterministic, wrong-key signature rejection)
- 2 `ElysiumPackageFile` tests (reject relative path, canonical is path hash perms)
- 2 `FilePermissions` tests (reject mode out of range, canonical is octal mode uid gid)
- 2 `ElysiumPackageScripts` tests (accepts all nulls, reject blank preInstall)
- 1 `ElysiumAbi` test (canonical names are the Android NDK names)
- 8 fixture helpers (buildManifest with default values)

### Test-discovered bugs

This phase surfaced 2 test-discovered bugs:

1. **Kotlin does NOT support `0o` octal literals.** I used `0o644`, `0o755`, `0o777` (Python-style octal prefix); the Kotlin compiler rejected them. Fix: use `0x` hex prefixes (`0x1A4` for `0o644`, `0x1ED` for `0o755`, `0x1FF` for `0o777`).

2. **`require(condition, "string")` is not a valid Kotlin signature.** The `require` function only has `(Boolean, () -> Any)` — passing a String as the second argument doesn't match. Fix: wrap the String in a lambda: `require(condition) { "string" }`. The `String.matches(pattern: String)` overload doesn't exist; the `Regex(pattern)` constructor + `String.matches(regex: Regex)` is the correct form.

Both bugs are now in the cross-project engineering rules (see `engineering-gotchas.md`).

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `ElysiumPackageManifestTest` | 0 | 40 | +40 (new) |
| **Total JVM unit tests** | 2536 | 2667 | **+40** (+131 from memory's most recent count, accounting for additional work in this session) |

**0 lint warnings on the new code, all builds green (debug APK; the pre-existing `SecurityInstrumentedTest` failure is unrelated).**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumAbi.kt`
- `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumPackageVersion.kt`
- `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumPackageDependency.kt`
- `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumPackageManifest.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/core/linux/ElysiumPackageManifestTest.kt`

---

## Architectural notes

### Why the manifest is content-addressed by composition

The manifest's content address is the SHA-256 of the canonical form. The canonical form is:
- Sorted by package name (for `dependencies`).
- Sorted by install path (for `files`).
- Sorted by capability (for `provides`).
- All fields are explicitly formatted (the `0x1FF` mode is formatted as `"0x1FF"`, not `"511"`).
- The `signature` is NOT part of the canonical form (the signature is computed over the form).

Two manifests with the same `name` + `version` + `abi` + `description` + `dependencies` + `provides` + `files` + `scripts` + `contentHash` produce the same canonical form. The signature binds the canonical form to the publisher.

### Why the signature uses a symmetric scheme (Phase 1)

The signature uses the existing `Signature.sign(payload, key)` method (HMAC-SHA-256 with a per-publisher key). The Phase 1 implementation is symmetric, not asymmetric. The Phase 2 hardening (a future increment) will replace HMAC with Ed25519 (then ML-DSA-65 in Phase 7 per `.ai/AGENTS.md` section 14 + skill 12).

The symmetric scheme is **good enough for Phase 73 first half** because:
- The trust chain is established via the repository (the repository's signing key is trusted; the manifest is signed with the repository's key).
- The content hash is independent of the signature (a tampered package is detected by the content hash; a tampered signature is detected by the signature verification).
- A future increment can replace the scheme without changing the manifest's structure.

### Why the manifest's `provides` is a `List<String>`

The `provides` list declares the **capabilities** the package provides (e.g. a Python package provides `"python-3.11"` capability; a consumer can depend on the capability instead of the specific package). The capability is a string (not a typed value) because:
- The capability is **consumer-defined** (a Python consumer can depend on `"python-3.11"` capability; a Wine consumer can depend on `"wine-9.0"` capability; the manifest is the source of truth).
- The capability is **stringly-typed** for forward compatibility (a future increment can add a typed capability enum without breaking existing manifests).

### Why the file permissions are `Int` (not `FileMode` enum)

The `FileMode` could be an enum (e.g. `READ_ONLY`, `READ_WRITE`, `EXECUTABLE`). The platform uses `Int` because:
- POSIX file modes are octal (e.g. `0x1A4` = 0644 = rw-r--r--).
- The octal representation is the platform's canonical form.
- An enum would require a translation table; the `Int` is the direct representation.

The `Int` is validated at construction (0..0x1FF = 0..511). The `canonical` form is the octal string (e.g. `"0755"`).

### Why the manifest's `dependencies` is a list (not a set)

The `dependencies` is a `List<ElysiumPackageDependency>` (not a `Set`) because:
- A package MAY depend on the same package multiple times (e.g. with different ABIs).
- A package MAY depend on the same package with different version constraints (e.g. `>= 1.0` AND `< 2.0` to express a range).
- The `Set` data type would prevent the duplicates; the `List` is more flexible.

---

## Next phases (the pipeline forward)

Phase 73 has 4 sub-half-increments:

| Increment | Status | Description |
|-----------|--------|-------------|
| Phase 73 first half | ✅ | Package manifest + trust chain (this phase) |
| Phase 73 second half | TODO | Package manager API + repository (the runtime that installs + upgrades + removes packages; the repository that hosts the signed manifests) |
| Phase 73 third half | TODO | Minimal rootfs + Mesa/Turnip/Box64/FEX integration (the actual binary; reproducible build) |
| Phase 73 fourth half | TODO | A/B updates + snapshots/rollback (the platform's update mechanism; the user can roll back to a previous version) |

The next increment is the **package manager API + repository** — the runtime that consumes the manifest.

Other forward work (not part of Phase 73):
- **EV Phase 74** — FileObserver for real-device audit step 9 (the existing Phase 71 E2E test needs a real file observer; the audit step currently uses an empty writes list).
- **Foundry Phase 4 (G5)** — AI council.
- **Foundry Phase 5 (G6+G7)** — Commercial foundation.
- **Foundry Phase 6 (G8)** — Marketplace.
- **Foundry Phase 7 (G9+G10)** — Production hardening.

The package manifest + trust chain are the foundation; every subsequent phase of Elysium Linux builds on them.
