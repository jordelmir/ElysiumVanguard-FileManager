# Phase 107 — Capsule JSON Schema (public creator reference)

**Vision gap closed**: #10 (Marketplace universal — Capsules
con JSON manifest como en la visión — el formato JSON para
creadores no estaba documentado como schema público).
**Status**: shipped
**Date**: 2026-07-20

## The gap

`Capsule.kt` existed with all the typed fields (apiVersion,
id, version, runtime, architecture, distribution, entrypoint,
gpu, permissions, signature, contentHash). The
`CapsuleCodec` had a JSON serializer. **But there was no
public schema for creators.** A third-party developer who
wanted to publish a capsule to the marketplace had no
authoritative reference for the JSON shape, the validation
rules, the enum values, or the signature format.

The vision calls this out explicitly:

> "Capsules con JSON manifest como en la visión — Capsule.kt
>  existe pero el formato JSON para creadores no está
>  documentado como schema público"

Phase 107 closes the gap with a full public schema doc +
a formal JSON Schema spec + a CLI validator.

## What shipped

### Docs (4 new files in `docs/capsule/`)

| File | Lines | Purpose |
|---|---|---|
| `docs/capsule/SCHEMA.md` | 280+ | The public, normative reference. Every field with type/required/validation. The enums (Runtime, Architecture, GpuApi, GpuDriver, StorageScope). The trust fields (signature, contentHash). The versioning policy. The publishing flow. |
| `docs/capsule/capsule.schema.json` | 200+ | The formal JSON Schema (Draft 2020-12). $id is `https://elysium-vanguard.io/schemas/capsule/v1.json`. Can be used with any JSON Schema validator (ajv, jsonschema, etc.). |
| `docs/capsule/VALIDATION.md` | 150+ | Three validation paths: CLI (zero-deps), JSON Schema (any validator), and signature verification (cryptographic). CI integration example. |
| `docs/capsule/samples/cli-tool.arm64.json` | 25 | A CLI tool example (no GPU, no network, USER_SELECTED storage). The minimal capsule. |
| `docs/capsule/samples/gpu-game.arm64.json` | 25 | A GPU game example (Vulkan + TURNIP, network + APP_PRIVATE + MEDIA_STORE). |
| `docs/capsule/samples/blender.arm64.json` | 25 (updated) | The canonical sample from the master vision's literal example. The placeholder signature was updated to match the new `ed25519:<base64>` pattern. |

### Tools (1 new file)

| File | Lines | Purpose |
|---|---|---|
| `tools/validate-capsule.sh` | 240+ | Zero-deps CLI validator (Python 3 stdlib). Catches everything the on-device `CapsuleCodec` catches. CI-friendly (single pass/fail count). Supports `--all` for directory recursion and `--verbose` for debugging. |

### Tests (1 new file, **+11 tests**)

| File | Tests |
|---|---|
| `CapsuleSchemaMatrixTest.kt` (new) | 11 tests pinning the GPU API × driver matrix (8 drivers × 4 APIs, all 32 combinations), the signature pattern (`ed25519:<base64>`), the contentHash shape (64 hex), the apiVersion pattern, the capsuleId pattern (reverse-DNS with at least one dot). |

## The GPU API × driver matrix (the schema's most interesting constraint)

The schema documents + the Kotlin tests pin + the JSON
Schema (loosely) + the CLI validator strictly enforce this
matrix:

| Driver | OpenGL ES | Vulkan | OpenCL |
|---|---|---|---|
| `NONE`         | ✅ | ✅ | ✅ |
| `TURNIP` (Adreno) | ✅ | ✅ | ❌ |
| `FREEDRENO` (legacy Adreno) | ✅ | ❌ | ❌ |
| `PANFROST` (Mali) | ✅ | ✅ | ❌ |
| `LIMA` (legacy Mali) | ✅ | ❌ | ❌ |
| `SOFTPIPE` (software) | ✅ | ❌ | ❌ |
| `DXVK` (Wine) | ❌ | ✅ (D3D→Vulkan) | ❌ |
| `VKD3D_PROTON` (Wine) | ❌ | ✅ (D3D12→Vulkan) | ❌ |

A request for an unsupported combination is rejected at
parse time by the CLI validator + the on-device `CapsuleCodec`.

## The trust fields (signature + contentHash)

The schema documents + the Kotlin tests pin:

- **Signature**: `^ed25519:[A-Za-z0-9+/]+={0,2}$` — the
  Ed25519 signature of the canonical JSON form
  (with the `signature` field set to `""`).
- **ContentHash**: `^[0-9a-f]{64}$` — the SHA-256 of the
  canonical JSON (with the `signature` + `contentHash` fields
  omitted).

The canonical form is RFC 8785 (JCS — JSON Canonicalization
Scheme). The `VALIDATION.md` doc shows how to verify the
signature off-device using Python (`python-json-canonical`),
Node.js (`canonicalize` npm), or Go
(`github.com/cyberphone/json-canonicalization`).

## Three validation paths

1. **CLI** (`tools/validate-capsule.sh`) — zero-deps. Runs
   everywhere Python 3 is installed. Catches all 8 categories
   of errors (syntax, required, pattern, enum, pure-sandbox,
   GPU matrix, signature, contentHash). Designed for CI.
2. **JSON Schema** (any validator: ajv, jsonschema, etc.) —
   catches all enum + pattern + required errors. Doesn't
   catch the GPU matrix (cross-property constraint).
3. **Signature verification** (the cryptographic check) —
   the platform verifies on-device. Third-party auditors
   can verify off-device using the public key from the
   marketplace's creator registry.

## What this enables

- **Third-party creators** can publish capsules to the
  marketplace with confidence the JSON is well-formed
  BEFORE submitting (no more "your capsule was rejected
  for unknown reasons" loops).
- **CI integration** — `for capsule in samples/*.json; do
  ./tools/validate-capsule.sh "$capsule"; done` in any
  creator's CI catches errors before they reach the
  marketplace.
- **A single source of truth** — the Kotlin `Capsule.kt`,
  the JSON Schema, the CLI validator, the docs, and the
  Kotlin tests all reference the same matrix / patterns /
  enums. A future refactor that changes one must update
  all five.
- **The GPU matrix is now documented as a schema
  constraint**, not as a runtime error. Creators can
  verify their capsule's GPU request is supported BEFORE
  publishing, not AFTER install fails.

## Test counts

- Before: 3629 tests
- After: **3640 tests**, 0 new failures (+11 new)
- Pre-existing flake: 1 (`FoundryServiceRepositoryIntegrationTest`,
  unchanged from `f08dad5`)

## Build

- `compileDebugKotlin`: green
- `assembleDebug`: green (98MB APK)
- `testDebugUnitTest`: 3640/3641 green
- `tools/validate-capsule.sh --all docs/capsule/samples/`:
  3/3 valid (blender, cli-tool, gpu-game)

## What's still missing (next phases)

- **Reference implementations** in Python / Node.js / Go
  for the canonical-JSON + signature-verification code.
  Today the `VALIDATION.md` shows the algorithm in
  pseudocode; libraries exist for all three languages but
  no ready-to-paste snippet.
- **A capsule linter** (not just a validator) — a tool that
  suggests improvements (e.g. "your capsule declares
  `storage: [APP_PRIVATE, MEDIA_STORE]` but you don't seem
  to use the MediaStore; consider dropping it").
- **Capsule submission API** — the marketplace's
  `MarketPublisher.publishCapsule(capsule)` exists but
  isn't documented. Phase 108+ should add a creator
  onboarding guide.
- **Schema evolution tooling** — when v2 ships, a
  `migrate-capsule.py` script that converts v1 to v2.
  Today the spec says "a migration tool is provided" but
  no tool exists.
