# Capsule Validation

> **Phase 107** — how creators validate a capsule before
> publishing it to the Elysium Vanguard Marketplace.

The platform's on-device validator (`CapsuleCodec.decode`)
rejects malformed capsules with a typed
`CapsuleCodecException`, but you want to catch errors
**before** you submit. This document covers three
validation paths:

1. **CLI** — `tools/validate-capsule.sh <file>` (zero
   dependencies, uses Python 3 stdlib only).
2. **JSON Schema** — validate against
   [`capsule.schema.json`](./capsule.schema.json) with
   any JSON Schema validator (ajv, jsonschema, etc.).
3. **Canonical JSON + signature verification** — the
   cryptographic check the platform performs on-device.

## Path 1: CLI (`tools/validate-capsule.sh`)

The CLI is a self-contained Python 3 script with no external
dependencies. It performs:

  - JSON syntax check
  - Required-field check
  - Pattern check on `apiVersion`, `id`, `version`,
    `entrypoint.executable`, `entrypoint.workingDirectory`,
    `signature`, `contentHash`
  - Enum check on `runtime`, `architecture`, `gpu.api`,
    `gpu.driver`, `permissions.storage[]`
  - **Pure-sandbox rejection** (network:false + storage:[]
    is refused)
  - **GPU API × driver matrix check** (TURNIP+OpenCL is
    refused; VULKAN+SOFTPIPE is refused; etc.)
  - **Signature pattern** (must match `^ed25519:<base64>$`)
  - **Content hash shape** (64 hex chars)

The CLI does **not** verify the signature itself (it would
need the platform's public key, which the marketplace's
publisher has but a third-party creator does not). For
signature verification, see **Path 3**.

### Usage

```bash
# Validate a single capsule.
./tools/validate-capsule.sh docs/capsule/samples/blender.arm64.json

# Validate a directory of capsules (CI usage).
./tools/validate-capsule.sh --all docs/capsule/samples/

# Show what's wrong (verbose).
./tools/validate-capsule.sh --verbose path/to/broken.capsule.json
```

### Exit codes

  - `0` — all capsules valid
  - `1` — at least one capsule is invalid
  - `2` — script error (file not found, python not installed, etc.)

## Path 2: JSON Schema

The formal JSON Schema is in
[`capsule.schema.json`](./capsule.schema.json) (Draft
2020-12). To validate a capsule:

```bash
# ajv (Node.js)
npx ajv validate -s docs/capsule/capsule.schema.json \
                 -d docs/capsule/samples/blender.arm64.json

# jsonschema (Python)
pip install jsonschema
python3 -c "import json, jsonschema; \
  jsonschema.validate( \
    json.load(open('docs/capsule/samples/blender.arm64.json')), \
    json.load(open('docs/capsule/capsule.schema.json')) \
  )"

# check-jsonschema (Python, zero deps)
pip install check-jsonschema
check-jsonschema --schemafile docs/capsule/capsule.schema.json \
                 docs/capsule/samples/blender.arm64.json
```

The JSON Schema catches:

  - All enum mismatches
  - All pattern mismatches
  - The pure-sandbox prohibition (via `allOf[0].not`)
  - The required-field list

It does **not** catch the GPU API × driver matrix
(that's a cross-property check the JSON Schema can't
express; the CLI does it). The CLI is the most
thorough validator.

## Path 3: Canonical JSON + signature verification

The platform verifies the signature on-device using the
creator's public key registered in `DeviceIntegrityConfig`.
A creator (or a third-party auditor) can verify the
signature off-device too, but they need the public key.
The platform publishes the public key alongside the
capsule in the catalog.

### Canonical JSON form (RFC 8785)

The canonical form is the JSON object with:

  - All keys sorted alphabetically (lexicographic, byte-wise).
  - One space after `:` and `,`, no whitespace elsewhere.
  - The `signature` field set to `""`.
  - The `contentHash` field set to `""`.

Most languages have a library for this:

  - **Python**: `python-json-canonical` (RFC 8785).
  - **Node.js**: `canonicalize` (npm).
  - **Go**: `github.com/cyberphone/json-canonicalization`.

### Verification algorithm

```python
import json, hashlib, base64
from canonicaljson import encode_canonical_json

# 1. Load the capsule.
capsule = json.load(open("capsule.json"))

# 2. Extract the signature + public key.
sig_b64 = capsule["signature"].split(":", 1)[1]
public_key = ... # from the marketplace's creator registry

# 3. Compute the canonical JSON with signature + contentHash empty.
capsule_for_signing = {**capsule, "signature": "", "contentHash": ""}
canonical = encode_canonical_json(capsule_for_signing)

# 4. Verify the Ed25519 signature.
# (Use a crypto library: ed25519 in Python, nacl in Node.js, etc.)
ed25519.verify(public_key, canonical, base64.b64decode(sig_b64))

# 5. Verify the content hash.
content_hash_computed = hashlib.sha256(canonical).hexdigest()
assert content_hash_computed == capsule["contentHash"], "hash mismatch"
```

The on-device `CapsuleCodec` runs the same algorithm. The
`CapsuleCodecException` error envelope is the typed result
of any of these steps failing.

## CI integration

The recommended CI pipeline is:

```yaml
# .github/workflows/capsule-validate.yml
- name: Validate capsules
  run: |
    for capsule in docs/capsule/samples/*.json; do
      ./tools/validate-capsule.sh --verbose "$capsule"
    done
```

The CI step fails if any capsule is malformed. The
Marketplace's submitter runs the same check server-side
before accepting the capsule; the CI step is the
creator-side mirror.
