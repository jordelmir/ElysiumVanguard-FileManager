#!/usr/bin/env bash
# =============================================================================
# validate-capsule.sh — Capsule JSON validator (zero dependencies)
# =============================================================================
#
# Phase 107 — the creator-side capsule validator. Catches
# everything the on-device CapsuleCodec catches, BEFORE
# the capsule is submitted to the marketplace. The script
# is zero-deps (Python 3 stdlib only) so it can run in any
# CI environment.
#
# What it validates:
#   1. JSON syntax (json.loads)
#   2. Required fields (the JSON Schema $required list)
#   3. Pattern fields (apiVersion, id, version, executable,
#      signature, contentHash, etc.)
#   4. Enum fields (runtime, architecture, gpu.api,
#      gpu.driver, storage scopes)
#   5. Pure-sandbox prohibition (network:false + storage:[] is
#      refused)
#   6. GPU API x driver matrix (TURNIP+OpenCL refused,
#      VULKAN+SOFTPIPE refused, etc.)
#   7. Signature pattern (ed25519:<base64>)
#   8. Content hash shape (64 hex chars)
#
# What it does NOT validate (out of scope for this tool):
#   - The actual signature (would need the platform's
#     public key; see docs/capsule/VALIDATION.md path 3)
#   - The actual content hash (would need the canonical
#     JSON library; see VALIDATION.md)
#
# Usage:
#   ./tools/validate-capsule.sh path/to/capsule.json
#   ./tools/validate-capsule.sh --all docs/capsule/samples/
#   ./tools/validate-capsule.sh --verbose path/to/capsule.json
#
# Exit codes:
#   0  all capsules valid
#   1  at least one capsule is invalid
#   2  script error (file not found, python not installed, etc.)
# =============================================================================

set -euo pipefail

VERBOSE=0
ALL=0
FILES=()

usage() {
    cat <<USAGE
Usage: $0 [options] <file-or-dir>...

Options:
  --verbose     Print every check (not just failures).
  --all         Treat the inputs as directories; validate
                every *.json file inside (recursively).
  -h, --help    Show this help.

Exit codes:
  0  all capsules valid
  1  at least one capsule is invalid
  2  script error

Examples:
  $0 docs/capsule/samples/blender.arm64.json
  $0 --all docs/capsule/samples/
  $0 --verbose path/to/broken.capsule.json
USAGE
}

# Parse args.
while [ $# -gt 0 ]; do
    case "$1" in
        --verbose) VERBOSE=1; shift ;;
        --all) ALL=1; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "unknown option: $1" >&2; usage; exit 2 ;;
        *) FILES+=("$1"); shift ;;
    esac
done

if [ ${#FILES[@]} -eq 0 ]; then
    echo "no input files" >&2
    usage
    exit 2
fi

# Resolve --all: expand directories.
if [ "$ALL" = "1" ]; then
    RESOLVED=()
    for f in "${FILES[@]}"; do
        if [ -d "$f" ]; then
            while IFS= read -r -d '' json; do
                RESOLVED+=("$json")
            done < <(find "$f" -type f -name '*.json' -print0)
        else
            RESOLVED+=("$f")
        fi
    done
    FILES=("${RESOLVED[@]}")
fi

if [ ${#FILES[@]} -eq 0 ]; then
    echo "no .json files found" >&2
    exit 2
fi

# Verify python3 is available.
if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required" >&2
    exit 2
fi

# The validator. Lives in a heredoc so the script is
# self-contained (no separate .py file to lose). The
# validator is a pure function of the JSON content;
# no side effects.
read -r -d '' VALIDATOR_PY <<'PYEOF' || true
import json
import re
import sys

# -------------------------------------------------------------------------
# Validation rules. Mirrors the Kotlin Capsule.kt init
# blocks and the JSON Schema in docs/capsule/capsule.schema.json.
# -------------------------------------------------------------------------

RUNTIME_ENUM = {"LINUX", "WINDOWS", "MACOS", "WEB"}
ARCH_ENUM = {"ARM64", "ARM32", "X86_64", "X86", "ANY"}
GPU_API_ENUM = {"NONE", "OPENGL_ES", "VULKAN", "OPENCL"}
GPU_DRIVER_ENUM = {
    "NONE", "TURNIP", "FREEDRENO", "PANFROST",
    "LIMA", "SOFTPIPE", "DXVK", "VKD3D_PROTON",
}
STORAGE_SCOPE_ENUM = {"USER_SELECTED", "APP_PRIVATE", "MEDIA_STORE", "NETWORK"}

# Per the SCHEMA.md 'GPU API x driver matrix'.
GPU_COMPAT = {
    "NONE":         {"NONE", "OPENGL_ES", "VULKAN", "OPENCL"},
    "TURNIP":       {"OPENGL_ES", "VULKAN"},
    "FREEDRENO":    {"OPENGL_ES"},
    "PANFROST":     {"OPENGL_ES", "VULKAN"},
    "LIMA":         {"OPENGL_ES"},
    "SOFTPIPE":     {"OPENGL_ES"},
    "DXVK":         {"VULKAN"},
    "VKD3D_PROTON": {"VULKAN"},
}

REQUIRED_TOP = {
    "apiVersion", "id", "name", "version", "description",
    "runtime", "architecture", "distribution",
    "entrypoint", "gpu", "permissions",
    "signature", "contentHash",
}
REQUIRED_ENTRYPOINT = {"executable"}
REQUIRED_GPU = {"api", "driver"}
REQUIRED_PERMS = {"network", "storage"}

API_VERSION_RE = re.compile(r"^elysium\.capsule/v\d+(\.\d+)?$")
ID_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$")
ABS_PATH_RE = re.compile(r"^/")
SIG_RE = re.compile(r"^ed25519:[A-Za-z0-9+/]+={0,2}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

def err(errors, msg):
    errors.append(msg)

def validate(capsule, path, errors):
    # 1. JSON syntax was already verified by json.loads.
    # 2. Required fields.
    for k in REQUIRED_TOP:
        if k not in capsule:
            err(errors, f"missing required field: {k!r}")
    # Stop here if required fields are missing (the rest
    # of the checks assume the shape).
    if errors:
        return

    # 3. Pattern fields.
    if not API_VERSION_RE.match(capsule["apiVersion"]):
        err(errors, f"apiVersion {capsule['apiVersion']!r} does not match ^elysium\\.capsule/v\\d+(\\.\\d+)?$")
    if not ID_RE.match(capsule["id"]):
        err(errors, f"id {capsule['id']!r} does not match ^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    if not VERSION_RE.match(capsule["version"]):
        err(errors, f"version {capsule['version']!r} does not match ^[0-9]+\\.[0-9]+\\.[0-9]+(-[A-Za-z0-9.]+)?$")
    if not capsule["name"].strip():
        err(errors, "name must not be blank")
    if not capsule["description"].strip():
        err(errors, "description must not be blank")

    # 4. Enum fields.
    if capsule["runtime"] not in RUNTIME_ENUM:
        err(errors, f"runtime {capsule['runtime']!r} is not one of {sorted(RUNTIME_ENUM)}")
    if capsule["architecture"] not in ARCH_ENUM:
        err(errors, f"architecture {capsule['architecture']!r} is not one of {sorted(ARCH_ENUM)}")

    # 5. Entrypoint.
    ep = capsule.get("entrypoint", {})
    for k in REQUIRED_ENTRYPOINT:
        if k not in ep:
            err(errors, f"entrypoint: missing required field {k!r}")
    if "executable" in ep and not ABS_PATH_RE.match(ep["executable"]):
        err(errors, f"entrypoint.executable {ep['executable']!r} must start with /")
    if "workingDirectory" in ep and not ABS_PATH_RE.match(ep["workingDirectory"]):
        err(errors, f"entrypoint.workingDirectory {ep.get('workingDirectory')!r} must start with /")

    # 6. GPU.
    gpu = capsule.get("gpu", {})
    for k in REQUIRED_GPU:
        if k not in gpu:
            err(errors, f"gpu: missing required field {k!r}")
    if "api" in gpu and gpu["api"] not in GPU_API_ENUM:
        err(errors, f"gpu.api {gpu['api']!r} is not one of {sorted(GPU_API_ENUM)}")
    if "driver" in gpu and gpu["driver"] not in GPU_DRIVER_ENUM:
        err(errors, f"gpu.driver {gpu['driver']!r} is not one of {sorted(GPU_DRIVER_ENUM)}")
    # The API x driver matrix.
    if "api" in gpu and "driver" in gpu and gpu["driver"] in GPU_COMPAT:
        if gpu["api"] not in GPU_COMPAT[gpu["driver"]]:
            err(errors, f"gpu.driver {gpu['driver']!r} does not support gpu.api {gpu['api']!r} (see SCHEMA.md 'GPU API x driver matrix')")

    # 7. Permissions.
    perms = capsule.get("permissions", {})
    for k in REQUIRED_PERMS:
        if k not in perms:
            err(errors, f"permissions: missing required field {k!r}")
    if "network" in perms and not isinstance(perms["network"], bool):
        err(errors, "permissions.network must be a boolean")
    if "storage" in perms:
        if not isinstance(perms["storage"], list):
            err(errors, "permissions.storage must be a list")
        else:
            if len(perms["storage"]) == 0:
                err(errors, "permissions.storage must have at least one entry")
            if len(perms["storage"]) != len(set(perms["storage"])):
                err(errors, "permissions.storage must not contain duplicates")
            for s in perms["storage"]:
                if s not in STORAGE_SCOPE_ENUM:
                    err(errors, f"permissions.storage contains unknown scope {s!r}")
    # The pure-sandbox prohibition.
    if "network" in perms and "storage" in perms:
        if perms["network"] is False and isinstance(perms["storage"], list) and len(perms["storage"]) == 0:
            err(errors, "permissions: network:false + storage:[] is a pure sandbox (refused)")

    # 8. Trust fields.
    if not SIG_RE.match(capsule.get("signature", "")):
        err(errors, f"signature {capsule.get('signature', '')!r} does not match ^ed25519:<base64>$")
    if not SHA256_RE.match(capsule.get("contentHash", "")):
        err(errors, f"contentHash {capsule.get('contentHash', '')!r} is not 64 hex chars")

def main():
    verbose = "--verbose" in sys.argv
    files = [a for a in sys.argv[1:] if a != "--verbose"]
    failed = 0
    total = 0
    for path in files:
        total += 1
        try:
            with open(path) as f:
                capsule = json.load(f)
        except json.JSONDecodeError as e:
            print(f"  FAIL  {path}")
            print(f"        invalid JSON: {e}")
            failed += 1
            continue
        except FileNotFoundError:
            print(f"  FAIL  {path}")
            print(f"        file not found")
            failed += 1
            continue

        errors = []
        validate(capsule, path, errors)
        if errors:
            print(f"  FAIL  {path}")
            for e in errors:
                print(f"        - {e}")
            failed += 1
        else:
            if verbose:
                print(f"  PASS  {path}")
            else:
                print(f"  OK    {path}")

    print()
    print(f"  {total - failed}/{total} capsules valid")
    return 0 if failed == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
PYEOF

# Run the validator on all files in one Python invocation.
# This gives a single pass/fail count at the end (rather
# than per-file exit codes that would be confusing).
if [ "$VERBOSE" = "1" ]; then
    python3 -c "$VALIDATOR_PY" --verbose "${FILES[@]}"
    rc=$?
else
    python3 -c "$VALIDATOR_PY" "${FILES[@]}"
    rc=$?
fi

exit $rc
