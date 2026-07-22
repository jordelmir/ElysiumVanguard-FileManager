# Capsule JSON Schema — public reference for creators

> **Phase 107** — the *public* Capsule JSON Schema.
> This is what capsule creators (Linux packagers, Wine
> profile maintainers, plugin authors, AI agent providers)
> see when they publish a capsule to the Elysium Vanguard
> Marketplace.

This document is the **public, normative** reference. The
canonical JSON Schema is in [`capsule.schema.json`](./capsule.schema.json);
the samples in [`samples/`](./samples/) are valid against
the schema. For how to *validate* a capsule before publishing,
see [`VALIDATION.md`](./VALIDATION.md).

## What is a capsule?

A **capsule** is the typed manifest for a single distributable
unit in the Elysium Vanguard Marketplace. A capsule carries:

  - **What** the package is (id, name, version, description).
  - **Where** it runs (runtime + architecture + distribution).
  - **How** it starts (entrypoint + args + working directory).
  - **What hardware** it needs (GPU API + driver).
  - **What access** it needs (network + storage permissions).
  - **Trust** (signature + content hash for
    content-addressed storage).

The Capsule is the **bridge** between the marketplace listing
("what's in the catalog") and the local workspace
("how the user runs it"). A `MarketListing` may wrap a
`Capsule`; a `WorkspaceDefinition` is built FROM a capsule
when the user installs it.

## Top-level structure

```json
{
  "apiVersion": "elysium.capsule/v1",
  "id": "com.example.myapp.arm64",
  "name": "My App",
  "version": "1.0.0",
  "description": "A short, user-readable description.",
  "runtime": "LINUX",
  "architecture": "ARM64",
  "distribution": "elysium-linux-1",
  "entrypoint": {
    "executable": "/usr/bin/myapp",
    "args": ["--config", "/etc/myapp.conf"],
    "workingDirectory": "/workspace"
  },
  "gpu": {
    "api": "VULKAN",
    "driver": "TURNIP"
  },
  "permissions": {
    "network": true,
    "storage": ["USER_SELECTED", "APP_PRIVATE"]
  },
  "signature": "ed25519:...",
  "contentHash": "a1b2c3d4..."
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `apiVersion` | string | yes | Must match `^elysium\.capsule/v\d+(\.\d+)?$`. Today: `elysium.capsule/v1`. |
| `id` | string | yes | Reverse-DNS namespace id. Must match `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$`. |
| `name` | string | yes | User-readable display name. Non-blank. |
| `version` | string | yes | [SemVer 2.0](https://semver.org/). Must match `^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$`. |
| `description` | string | yes | One-paragraph description. Plain text, no markup. |
| `runtime` | string | yes | One of: `LINUX`, `WINDOWS`, `MACOS`, `WEB`. |
| `architecture` | string | yes | One of: `ARM64`, `ARM32`, `X86_64`, `X86`, `ANY`. |
| `distribution` | string | yes | Distro id (e.g. `elysium-linux-1`) or `any`. |
| `entrypoint` | object | yes | The binary to launch. See below. |
| `gpu` | object | yes | The GPU API + driver the capsule needs. See below. |
| `permissions` | object | yes | The runtime access the capsule needs. See below. |
| `signature` | string | yes | `ed25519:<base64>` of the canonical JSON. The platform verifies the signature before extraction. |
| `contentHash` | string | yes | SHA-256 of the canonical JSON (with the `signature` + `contentHash` fields omitted). 64 hex chars. |

## `entrypoint`

```json
{
  "executable": "/usr/bin/myapp",
  "args": ["--config", "/etc/myapp.conf"],
  "workingDirectory": "/workspace"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `executable` | string | yes | Absolute path inside the capsule's rootfs. Must start with `/`. |
| `args` | string[] | no (default: `[]`) | CLI args passed to the executable. Each arg is a literal token. |
| `workingDirectory` | string | no (default: `/`) | Absolute path inside the rootfs. Must start with `/`. |

## `gpu`

```json
{
  "api": "VULKAN",
  "driver": "TURNIP"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `api` | string | yes | One of: `NONE`, `OPENGL_ES`, `VULKAN`, `OPENCL`. |
| `driver` | string | yes | One of: `NONE`, `TURNIP`, `FREEDRENO`, `PANFROST`, `LIMA`, `SOFTPIPE`, `DXVK`, `VKD3D_PROTON`. |

### GPU API × driver matrix

| Driver | OpenGL ES | Vulkan | OpenCL |
|---|---|---|---|
| `NONE` | ✅ | ✅ | ✅ |
| `TURNIP` (Adreno) | ✅ | ✅ | ❌ |
| `FREEDRENO` (legacy Adreno) | ✅ | ❌ | ❌ |
| `PANFROST` (Mali) | ✅ | ✅ | ❌ |
| `LIMA` (legacy Mali) | ✅ | ❌ | ❌ |
| `SOFTPIPE` (software) | ✅ | ❌ | ❌ |
| `DXVK` (Wine) | ❌ | ✅ (D3D→Vulkan) | ❌ |
| `VKD3D_PROTON` (Wine) | ❌ | ✅ (D3D12→Vulkan) | ❌ |

A request for an unsupported combination is rejected by the
runtime at install time with a typed error.

## `permissions`

```json
{
  "network": false,
  "storage": ["USER_SELECTED"]
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `network` | boolean | yes | `true` if the capsule can reach the network. The runtime's deny-by-default policy (Phase 105) starts at `false` and requires the workspace's `NetworkPolicySpec.ALLOW_LIST` to enable it. |
| `storage` | string[] | yes | List of storage scopes the capsule can read. See below. |

### `storage` scopes

| Scope | Meaning |
|---|---|
| `USER_SELECTED` | The user picks the paths at install time (a runtime file picker). |
| `APP_PRIVATE` | The capsule's own data dir (Android-style scoped storage). |
| `MEDIA_STORE` | The shared MediaStore (photos, music, videos). |
| `NETWORK` | SMB / SFTP / WebDAV (network storage, gated by the network permission). |

The vision's literal example declares
`storage: ["user-selected"]` for a GPU-only capsule — a
single-element allowlist is the recommended default.

### Pure-sandbox prohibition

A capsule that declares **both** `network: false` AND
`storage: []` is a **pure sandbox** — the runtime would deny
every I/O. The schema (and the Kotlin `init` block) reject
this combination at parse time. If you genuinely need a
zero-I/O capsule, declare `storage: ["APP_PRIVATE"]` and
write nothing to it.

## `runtime` enum

| Value | Meaning | Today's backend |
|---|---|---|
| `LINUX` | A Linux ELF binary | proot (Phase 9.6.4) + native-exec (Phase 10.4) + rooted chroot (Phase 102) |
| `WINDOWS` | A Windows PE binary | Wine + QEMU QMP (Phase 99/103) |
| `MACOS` | A macOS Mach-O binary | not yet shipped; reserved |
| `WEB` | A web application (PWA-style) | the embedded browser runtime (Phase 70+) |

## `architecture` enum

| Value | Meaning | Translation layer |
|---|---|---|
| `ARM64` | 64-bit ARM (the dominant Android arch) | native |
| `ARM32` | 32-bit ARM (legacy) | native (rare on modern devices) |
| `X86_64` | 64-bit x86 (desktops, QEMU on ARM64) | Box64 (Phase 109+) |
| `X86` | 32-bit x86 (legacy) | FEX (Phase 109+) |
| `ANY` | Architecture-agnostic (interpreted, JVM) | the orchestrator picks the host arch |

## `distribution` field

| Value | Meaning |
|---|---|
| `any` | The user's preferred distro is used (the orchestrator decides). The recommended default for cross-distro apps. |
| `elysium-linux-1` | The Elysium Vanguard Linux distro (the proprietary one, Phase 101/106). |
| `<other>` | A community-distro id from the `ElysiumLinuxDistroListing` / `CommunityDistros` catalog. The orchestrator checks the listing exists + is installed. |

## Trust fields

### `signature`

```
"signature": "ed25519:<base64-bytes>"
```

The signature is the **Ed25519** signature of the canonical
JSON form of the capsule (with the `signature` field set to
the empty string). The signing key is the creator's
**publishing key**; the platform registers the corresponding
public key in `DeviceIntegrityConfig`.

#### Canonical JSON form

The canonical form is the JSON object with:

  - All keys sorted alphabetically.
  - No insignificant whitespace (one space after `:`
    and `,`, no whitespace elsewhere).
  - The `signature` field set to `""`.
  - The `contentHash` field set to `""`.

Most languages have a library for this:

  - **Python**: `python-json-canonical` (RFC 8785).
  - **Node.js**: `canonicalize` (npm).
  - **Go**: `github.com/cyberphone/json-canonicalization`.

### `contentHash`

The SHA-256 of the canonical JSON form (with the `signature`
+ `contentHash` fields omitted). 64 hex chars. Computed as:

```
contentHash = sha256(canonical_json_without_signature_and_contentHash)
```

The platform verifies:

1. The `contentHash` matches the canonical JSON.
2. The `signature` is a valid Ed25519 signature over the
   canonical JSON (with `signature` set to `""`).
3. The signing public key is registered in
   `DeviceIntegrityConfig`.

A capsule that fails any of these checks is rejected with a
typed `CapsuleSignatureException`.

## Versioning policy

The schema follows **append-only** versioning (per the
master vision's `.ai/AGENTS.md` section 24.1):

  - **Patch bumps** (v1.0 → v1.1): additive — new optional
    fields, new enum values. Existing capsules still parse.
  - **Minor bumps** (v1 → v2): breaking — removed fields,
    tightened constraints, new required fields. A migration
    tool is provided.
  - **Major bumps** (v1 → v2 → v3): a *new* `apiVersion` string
    + a new codec class. The old codec remains for
    back-compat (reads old files; refuses to write them).

The current schema is `elysium.capsule/v1`. The next
non-breaking revision will be `v1.x`; the next breaking
revision will be `v2`.

## How to publish

1. Build your capsule's `Capsule.kt` (or equivalent data).
2. Sign the canonical JSON with your publishing key.
3. Compute the content hash.
4. Validate against `capsule.schema.json` (see
   `VALIDATION.md`).
5. Submit to the Elysium Vanguard Marketplace via
   `MarketPublisher.publishCapsule(capsule)`.

The marketplace verifies the signature + content hash,
then publishes the capsule to the catalog.

## See also

  - [`capsule.schema.json`](./capsule.schema.json) — the
    formal JSON Schema (Draft 2020-12) for validation.
  - [`samples/blender.arm64.json`](./samples/blender.arm64.json) —
    the canonical sample (matches the master vision's literal example).
  - [`samples/cli-tool.arm64.json`](./samples/cli-tool.arm64.json) —
    a CLI tool example (no GPU, minimal permissions).
  - [`samples/gpu-game.arm64.json`](./samples/gpu-game.arm64.json) —
    a GPU-intensive game example.
  - [`VALIDATION.md`](./VALIDATION.md) — how to validate a capsule
    before publishing (CLI + library).
  - `core/runtime/capsule/Capsule.kt` — the in-memory typed
    representation (the source of truth for the schema).
  - `core/runtime/capsule/CapsuleCodec.kt` — the JSON
    serializer/deserializer (the on-disk format).
