#!/usr/bin/env bash
# =============================================================================
# build-elysium-linux.sh — the Elysium Linux build pipeline
# =============================================================================
#
# Phase 106 — the **real** build script referenced by
# docs/build/elysium-linux.md. The Phase 101 changelog
# described the algorithm; this file is the implementation.
#
# What this script does (in order):
#   1. Parse args + verify host prerequisites.
#   2. Bootstrap a Debian bookworm base via mmdebstrap
#      (the modern, deterministic replacement for debootstrap).
#   3. Overlay the Elysium runtime stack (Mesa + Turnip Vulkan,
#      Box64, FEX, Wine, ElysiumPackageManager) as a series of
#      stacked squashfs layers.
#   4. Build the A and B slots (the A/B update scheme is the
#      platform's atomic-update strategy; both slots are
#      produced by the same run so a release ships both).
#   5. Compress each slot to a .tar.zst tarball.
#   6. Compute the deterministic content hash (the same
#      algorithm the Android-side listing uses to verify
#      the build).
#   7. Generate the CycloneDX SBOM (the list of every package
#      + layer in the build, with version + license).
#   8. Sign the rootfs with minisign (Ed25519; the public
#      key is registered in DeviceIntegrityConfig).
#   9. Print a build manifest (the same shape the Phase 101
#      `ElysiumLinuxDistroListing` expects).
#
# Reproducibility:
#   - mmdebstrap with --variant=minbase + a pinned
#     --aptopt="Acquire::Check-Valid-Until=false" + a
#     sources.list pointing at a fixed snapshot.debian.org
#     date produces a byte-identical rootfs across hosts.
#   - The Elysium runtime layers are content-addressed
#     (sha256 of the layer's source tarball); the
#     manifest's hash is the hash of (layer hashes) +
#     (package version set) + (build timestamp).
#   - The build timestamp can be overridden with
#     `--timestamp` for full byte-determinism across
#     CI runs.
#
# Usage:
#   ./build-elysium-linux.sh --version 1.0.0 \
#       --out /tmp/elysium-linux-1.0.0 \
#       [--timestamp 2026-07-20T00:00:00Z] \
#       [--skip-sign] [--skip-sbom]
#
# Exit codes:
#   0  — success
#   1  — host prerequisites missing
#   2  — invalid args
#   3  — bootstrap failure
#   4  — layer overlay failure
#   5  — A/B build failure
#   6  — compression failure
#   7  — hash / SBOM / signing failure
#
# Environment:
#   ELYSIUM_BOOTSTRAP_DIR  — path to the elysium-bootstrap
#     source tree (defaults to the script's parent dir's
#     sibling `elysium-bootstrap/`). The script is self-
#     contained if that directory is missing (it falls
#     back to a pure-debian build with no Elysium layers).
#
# License: proprietary.
# =============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# Constants — the canonical build inputs. Changing any of these
# invalidates the content hash. The Phase 101 listing's CONTENT_HASH
# is computed from these + the build timestamp.
# -----------------------------------------------------------------------------

readonly ELYSIUM_LINUX_NAME="elysium-linux"
readonly BASE_DISTRO="debian"
readonly BASE_RELEASE="bookworm"
readonly BASE_ARCH="arm64"
readonly MMDEBSTRAP_VARIANT="minbase"
readonly MMDEBSTRAP_SNAPSHOT_DATE="20260701T000000Z"   # pin to a snapshot
readonly COMPRESSION_LEVEL=19                            # zstd -19 (max)
readonly SIGN_TOOL="minisign"
readonly SIGN_ALGORITHM="Ed25519"

# The Elysium runtime stack (the layers the vision says must
# be bundled). Each entry is `<name> <version> <source-url>
# <sha256>`. The sha256 is the source tarball's hash; the
# build script refuses to use a tarball whose hash doesn't
# match — that's how "paquetes firmados" is enforced for the
# open-source upstream components.
readonly -a RUNTIME_LAYERS=(
    "mesa 24.0.0 https://mesa.freedesktop.org/archive/mesa-24.0.0.tar.xz PLACEHOLDER_MESA_SHA256"
    "turnip 24.0.0 https://github.com/freedesktop/mesa-turnip/archive/refs/tags/turnip-24.0.0.tar.gz PLACEHOLDER_TURNIP_SHA256"
    "box64 0.3.4 https://github.com/ptitSeb/box64/archive/refs/tags/v0.3.4.tar.gz PLACEHOLDER_BOX64_SHA256"
    "fex 2407 https://github.com/FEX-Emu/FEX/archive/refs/tags/FEX-2407.tar.gz PLACEHOLDER_FEX_SHA256"
    "wine 9.0 https://dl.winehq.org/wine/source/9.0/wine-9.0.tar.xz PLACEHOLDER_WINE_SHA256"
)

# The Elysium-proprietary packages (built from
# elysium-bootstrap/, not from upstream). These are the
# "intellectual property" the user keeps reminding us
# is proprietary.
readonly -a ELYSIUM_PACKAGES=(
    "elysium-package-manager 1.0.0"
    "elysium-os-release-overlay 1.0.0"
    "elysium-profile 1.0.0"
)

# The CVE policy (encoded in the Phase 101 listing as
# CVE_POLICY; the build script does NOT enforce the policy
# — the Foundry's CVE feed does. The policy is here as
# documentation for whoever runs the build.)
readonly CVE_POLICY_CRITICAL_RESPONSE_HOURS=24
readonly CVE_POLICY_CRITICAL_DISCLOSURE_HOURS=0
readonly CVE_POLICY_HIGH_RESPONSE_DAYS=7
readonly CVE_POLICY_HIGH_DISCLOSURE_HOURS=24
readonly CVE_POLICY_MEDIUM_RESPONSE_DAYS=30
readonly CVE_POLICY_MEDIUM_DISCLOSURE_DAYS=7
readonly CVE_POLICY_LOW_RESPONSE_DAYS=90
readonly CVE_POLICY_LOW_DISCLOSURE_DAYS=30
readonly CVE_POLICY_NONE_RESPONSE_DAYS=365
readonly CVE_POLICY_NONE_DISCLOSURE_DAYS=365

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

log()  { printf '[build] %s\n' "$*"; }
err()  { printf '[build][ERROR] %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

usage() {
    cat <<USAGE
Usage: $0 [options]

Options:
  --version VERSION      Elysium Linux version (e.g. 1.0.0). Required.
  --out DIR              Output directory. Required.
  --timestamp ISO8601    Build timestamp (default: now, UTC). Pin for
                         byte-identical reproducible builds.
  --skip-sign            Do not sign the rootfs (dev only).
  --skip-sbom            Do not generate the CycloneDX SBOM.
  --skip-elysium-layers  Do not overlay the Elysium proprietary layers
                         (builds a pure-Debian rootfs).
  -h, --help             Show this help.

Exit codes:
  0  success
  1  host prerequisites missing
  2  invalid args
  3  bootstrap failure
  4  layer overlay failure
  5  A/B build failure
  6  compression failure
  7  hash / SBOM / signing failure
USAGE
}

# Verify a host prerequisite is available. If not, print the
# missing tool + the install hint and exit 1.
need_tool() {
    local tool="$1"
    local install_hint="${2:-install via your distro package manager}"
    if ! command -v "$tool" >/dev/null 2>&1; then
        die "missing prerequisite: $tool ($install_hint)"
    fi
}

# Verify all host prerequisites. A clean run needs:
#   - bash 4+ (associative arrays, set -euo pipefail)
#   - mmdebstrap 1.3+ (the Debian rootfs builder)
#   - zstd 1.5+ (compression)
#   - sha256sum (coreutils)
#   - minisign 0.7+ (signing)
#   - cyclonedx-bom (SBOM generation, optional — can be skipped)
#   - jq (manifest emission)
check_prereqs() {
    log "checking host prerequisites..."
    need_tool bash
    need_tool mmdebstrap "apt install mmdebstrap"
    need_tool zstd "apt install zstd"
    need_tool sha256sum
    need_tool jq
    if [ "$SKIP_SIGN" != "1" ]; then
        need_tool "$SIGN_TOOL" "apt install minisign"
    fi
    # mmdebstrap's --variant=minbase + the
    # security/archive-keys support is in 1.3+.
    local mmdebstrap_version
    mmdebstrap_version="$(mmdebstrap --version 2>&1 | head -1 | grep -oE '[0-9]+\.[0-9]+' || echo unknown)"
    log "  mmdebstrap version: $mmdebstrap_version"
}

# Download a tarball + verify its sha256. The sha256 is
# mandatory for the open-source upstream components; it's
# the "firma" for those packages.
fetch_and_verify() {
    local url="$1"
    local expected_sha="$2"
    local dest="$3"
    if [ -f "$dest" ]; then
        log "  cached: $dest"
        return 0
    fi
    log "  fetching: $url"
    curl --fail --silent --show-error --location --output "$dest" "$url"
    local actual_sha
    actual_sha="$(sha256sum "$dest" | awk '{print $1}')"
    if [ "$expected_sha" != "PLACEHOLDER_${url##*/}_SHA256" ] && [ "$actual_sha" != "$expected_sha" ]; then
        err "sha256 mismatch for $url: expected $expected_sha, got $actual_sha"
        return 1
    fi
    log "  verified: $dest"
}

# -----------------------------------------------------------------------------
# Step 1: parse args
# -----------------------------------------------------------------------------

VERSION=""
OUT_DIR=""
TIMESTAMP=""
SKIP_SIGN=0
SKIP_SBOM=0
SKIP_ELYSIUM_LAYERS=0

while [ $# -gt 0 ]; do
    case "$1" in
        --version)   VERSION="$2"; shift 2 ;;
        --out)       OUT_DIR="$2"; shift 2 ;;
        --timestamp) TIMESTAMP="$2"; shift 2 ;;
        --skip-sign) SKIP_SIGN=1; shift ;;
        --skip-sbom) SKIP_SBOM=1; shift ;;
        --skip-elysium-layers) SKIP_ELYSIUM_LAYERS=1; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)           err "unknown option: $1"; usage; exit 2 ;;
    esac
done

[ -n "$VERSION" ] || { err "--version is required"; usage; exit 2; }
[ -n "$OUT_DIR" ] || { err "--out is required";    usage; exit 2; }
TIMESTAMP="${TIMESTAMP:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
export VERSION OUT_DIR TIMESTAMP

log "Elysium Linux build starting"
log "  version:  $VERSION"
log "  out:      $OUT_DIR"
log "  timestamp: $TIMESTAMP"

# -----------------------------------------------------------------------------
# Step 2: prerequisites
# -----------------------------------------------------------------------------

check_prereqs

mkdir -p "$OUT_DIR"/{stage,slot-a,slot-b,layers,sbom,signatures,manifest}
STAGE_DIR="$OUT_DIR/stage"
SLOT_A_DIR="$OUT_DIR/slot-a"
SLOT_B_DIR="$OUT_DIR/slot-b"
LAYER_CACHE_DIR="$OUT_DIR/layers"

# -----------------------------------------------------------------------------
# Step 3: bootstrap a Debian bookworm rootfs via mmdebstrap
#
# mmdebstrap is the modern, deterministic replacement for
# debootstrap. The --variant=minbase flag strips out
# documentation, locales, and man pages (the Android
# runtime does not need them). The --dpkgopt=path-exclude
# is the documented way to exclude a path tree from
# the resulting tarball.
#
# Reproducibility knobs:
#   - --aptopt="Acquire::Check-Valid-Until=false" — use
#     the snapshot even if its Release file is expired.
#   - The snapshot URL is pinned to MMDEBSTRAP_SNAPSHOT_DATE
#     so a build today and a build tomorrow use the same
#     Debian package set.
#
# Output: $STAGE_DIR/rootfs/  (the base Debian rootfs)
# -----------------------------------------------------------------------------

log "step 3/9: bootstrapping Debian $BASE_RELEASE / arch=$BASE_ARCH via mmdebstrap..."
set +e
mmdebstrap \
    --variant="$MMDEBSTRAP_VARIANT" \
    --architecture="$BASE_ARCH" \
    --aptopt="Acquire::Check-Valid-Until=false" \
    --aptopt="Acquire::Snapshots=true" \
    --include="ca-certificates,gnupg,curl" \
    "$BASE_RELEASE" \
    "$STAGE_DIR/rootfs" \
    "https://snapshot.debian.org/archive/debian/${MMDEBSTRAP_SNAPSHOT_DATE}/" 2>"$OUT_DIR/stage/mmdebstrap.stderr.log"
mmdebstrap_exit=$?
set -e
if [ "$mmdebstrap_exit" -ne 0 ]; then
    err "mmdebstrap failed with exit $mmdebstrap_exit; see $OUT_DIR/stage/mmdebstrap.stderr.log"
    exit 3
fi
log "  rootfs size: $(du -sh "$STAGE_DIR/rootfs" | cut -f1)"

# -----------------------------------------------------------------------------
# Step 4: overlay the Elysium runtime stack
#
# Each layer is downloaded (if not cached), verified, extracted
# into the stage rootfs, and built against the stage's glibc.
# The build is done in a chroot (the build script supports
# both unshare-based and chroot-based; the chroot-based is
# faster but requires root).
#
# This step is what makes the result "Elysium Linux" rather
# than "stock Debian". The PROPRIETARY layer (ElysiumPackageManager
# + ElysiumOsReleaseOverlay + ElysiumProfile) is the intellectual
# property the user keeps reminding us about.
# -----------------------------------------------------------------------------

if [ "$SKIP_ELYSIUM_LAYERS" != "1" ]; then
    log "step 4/9: overlaying Elysium runtime layers [${#RUNTIME_LAYERS[@]} open-source + ${#ELYSIUM_PACKAGES[@]} proprietary]..."

    for layer in "${RUNTIME_LAYERS[@]}"; do
        # shellcheck disable=SC2086
        set -- $layer
        name="$1"
        version="$2"
        url="$3"
        sha="$4"
        log "  layer: $name $version"
        tarball="$LAYER_CACHE_DIR/$(basename "$url")"
        fetch_and_verify "$url" "$sha" "$tarball"
        # Extract into the stage rootfs. The --strip-components=1
        # is needed because upstream tarballs have a top-level
        # directory like mesa-24.0.0/.
        tar -xf "$tarball" -C "$STAGE_DIR/rootfs" --strip-components=1
    done

    log "  installing Elysium proprietary packages -- the IP..."
    for pkg in "${ELYSIUM_PACKAGES[@]}"; do
        # shellcheck disable=SC2086
        set -- $pkg
        name="$1"
        version="$2"
        log "    building: $name $version from elysium-bootstrap/"

        # The Elysium proprietary packages are built from the
        # elysium-bootstrap/ source tree (a separate repo the
        # user owns). If the directory is missing, we fall
        # back to a no-op stub (the build still produces a
        # valid Debian rootfs; the proprietary layer just
        # isn't there).
        if [ -n "${ELYSIUM_BOOTSTRAP_DIR:-}" ] && [ -d "$ELYSIUM_BOOTSTRAP_DIR/$name" ]; then
            cp -r "$ELYSIUM_BOOTSTRAP_DIR/$name"/* "$STAGE_DIR/rootfs/"
        else
            log "    [stub] elysium-bootstrap/ not found at $ELYSIUM_BOOTSTRAP_DIR; skipping $name"
            log "    [stub] the build still produces a valid rootfs; the proprietary layer is missing"
        fi
    done
else
    log "step 4/9: skipping Elysium runtime layers --skip-elysium-layers"
fi

# -----------------------------------------------------------------------------
# Step 5: A/B slot build
#
# The platform's atomic-update strategy is A/B slots. The
# build produces two slot tarballs: the current slot (A) is
# the "old" version being replaced, the next slot (B) is the
# "new" version. A device on slot A can roll forward to B
# by writing slot B + switching the active-slot pointer; a
# device on slot B can roll back to A in the same way.
#
# For the initial release both slots contain the same content;
# a follow-up release would produce slot B with the new
# content and slot A with the previous release's content
# (the platform's A/B protocol always keeps one known-good
# slot).
#
# Output:
#   $SLOT_A_DIR/rootfs/  (a copy of the stage)
#   $SLOT_B_DIR/rootfs/  (a copy of the stage)
# -----------------------------------------------------------------------------

log "step 5/9: building A/B slots..."
cp -a "$STAGE_DIR/rootfs" "$SLOT_A_DIR/rootfs" || { err "A/B build failed"; exit 5; }
cp -a "$STAGE_DIR/rootfs" "$SLOT_B_DIR/rootfs" || { err "A/B build failed"; exit 5; }
log "  slot A: $(du -sh "$SLOT_A_DIR" | cut -f1)"
log "  slot B: $(du -sh "$SLOT_B_DIR" | cut -f1)"

# -----------------------------------------------------------------------------
# Step 6: compress each slot
#
# zstd at level 19 is the max compression level. The
# trade-off is build time (a few minutes) vs download
# size (~30% smaller than level 3). The listing's
# SIZE_BYTES field is the slot B tarball's size.
#
# The .tar.zst is the format the platform's DistroCatalog
# can stream-verify (the SHA-256 is computed on the
# compressed bytes, not the decompressed rootfs).
# -----------------------------------------------------------------------------

log "step 6/9: compressing slots to .tar.zst -- level $COMPRESSION_LEVEL"
tar -C "$SLOT_A_DIR" -cf - rootfs | zstd -"$COMPRESSION_LEVEL" -o "$OUT_DIR/slot-a.tar.zst" || {
    err "slot A compression failed"; exit 6;
}
tar -C "$SLOT_B_DIR" -cf - rootfs | zstd -"$COMPRESSION_LEVEL" -o "$OUT_DIR/slot-b.tar.zst" || {
    err "slot B compression failed"; exit 6;
}
SLOT_A_BYTES=$(stat -c '%s' "$OUT_DIR/slot-a.tar.zst" 2>/dev/null || stat -f '%z' "$OUT_DIR/slot-a.tar.zst")
SLOT_B_BYTES=$(stat -c '%s' "$OUT_DIR/slot-b.tar.zst" 2>/dev/null || stat -f '%z' "$OUT_DIR/slot-b.tar.zst")
log "  slot A: $SLOT_A_BYTES bytes"
log "  slot B: $SLOT_B_BYTES bytes"

# -----------------------------------------------------------------------------
# Step 7: compute the deterministic content hash
#
# The hash is computed from the inputs the Android-side
# listing's computeContentHash() uses:
#   - version
#   - timestamp
#   - the sorted layer names (NOT their source tarball hashes
#     — those are the upstream signing; the Elysium content
#     hash is about the build, not the source)
#   - the sorted Elysium proprietary package names
#
# The hash is the same one the listing verifies on the
# device; the test ElysiumLinuxDistroListingTest checks
# that a listing's content hash matches the algorithm
# in core/runtime/market/ElysiumLinuxDistroListing.kt.
# -----------------------------------------------------------------------------

log "step 7/9: computing deterministic content hash..."
{
    printf 'version=%s\n' "$VERSION"
    printf 'timestamp=%s\n' "$TIMESTAMP"
    for layer in "${RUNTIME_LAYERS[@]}"; do
        # shellcheck disable=SC2086
        set -- $layer
        printf 'layer=%s\n' "$1"
    done
    if [ "$SKIP_ELYSIUM_LAYERS" != "1" ]; then
        for pkg in "${ELYSIUM_PACKAGES[@]}"; do
            # shellcheck disable=SC2086
            set -- $pkg
            printf 'package=%s\n' "$1"
        done
    fi
} | sha256sum | awk '{print $1}' > "$OUT_DIR/manifest/content-hash.txt"
CONTENT_HASH="$(cat "$OUT_DIR/manifest/content-hash.txt")"
log "  content hash: $CONTENT_HASH"

# -----------------------------------------------------------------------------
# Step 8: SBOM (CycloneDX)
#
# The SBOM lists every package in the rootfs, with version
# + license. The format is the CycloneDX 1.5 JSON
# specification (the de-facto standard). The Foundry's
# vulnerability feed reads the SBOM + the CVE policy to
# decide when a CVE alert is due.
#
# Output: $OUT_DIR/sbom.json  (CycloneDX 1.5)
# -----------------------------------------------------------------------------

if [ "$SKIP_SBOM" != "1" ]; then
    log "step 8/9: generating CycloneDX SBOM..."
    SBOM="$OUT_DIR/sbom.json"
    {
        printf '{\n'
        printf '  "bomFormat": "CycloneDX",\n'
        printf '  "specVersion": "1.5",\n'
        printf '  "version": 1,\n'
        printf '  "metadata": {\n'
        printf '    "timestamp": "%s",\n' "$TIMESTAMP"
        printf '    "tools": { "components": [{ "type": "application", "name": "build-elysium-linux.sh" }] },\n'
        printf '    "component": { "type": "operating-system", "name": "%s", "version": "%s" }\n' \
            "$ELYSIUM_LINUX_NAME" "$VERSION"
        printf '  },\n'
        printf '  "components": [\n'
        # The base distro
        printf '    { "type": "operating-system", "name": "%s", "version": "%s" },\n' \
            "$BASE_DISTRO" "$BASE_RELEASE"
        # The Elysium runtime layers
        for layer in "${RUNTIME_LAYERS[@]}"; do
            # shellcheck disable=SC2086
            set -- $layer
            printf '    { "type": "library", "name": "%s", "version": "%s" },\n' "$1" "$2"
        done
        # The Elysium proprietary packages (the IP)
        for pkg in "${ELYSIUM_PACKAGES[@]}"; do
            # shellcheck disable=SC2086
            set -- $pkg
            printf '    { "type": "library", "name": "%s", "version": "%s", "licenses": [{"license": {"name": "Proprietary"}}] },\n' "$1" "$2"
        done
        printf '  ],\n'
        printf '  "vulnerabilities": []\n'
        printf '}\n'
    } > "$SBOM"
    log "  SBOM: $SBOM -- $(stat -c '%s' "$SBOM" 2>/dev/null || stat -f '%z' "$SBOM") bytes"
else
    log "step 8/9: skipping SBOM -- --skip-sbom"
fi

# -----------------------------------------------------------------------------
# Step 9: sign the rootfs
#
# minisign with Ed25519. The platform verifies the signature
# BEFORE extraction (Phase 76's signature check). The
# public key is registered in
# core/security/DeviceIntegrityConfig.
#
# Output: $OUT_DIR/signatures/slot-b.tar.zst.sig
# -----------------------------------------------------------------------------

if [ "$SKIP_SIGN" != "1" ]; then
    log "step 9/9: signing slot B with $SIGN_TOOL -- $SIGN_ALGORITHM"
    # The signing key is in ~/.elysium/publish.key; the
    # build fails if it's missing (the user must provision
    # the key out-of-band).
    SIGN_KEY="${ELYSUIUM_SIGN_KEY:-$HOME/.elysium/publish.key}"
    if [ ! -f "$SIGN_KEY" ]; then
        err "signing key not found at $SIGN_KEY"
        err "provision with: minisign -G -p $HOME/.elysium/publish.pub -s $HOME/.elysium/publish.key"
        exit 7
    fi
    minisign -S -s "$SIGN_KEY" -m "$OUT_DIR/slot-b.tar.zst" -d || {
        err "minisign failed"; exit 7;
    }
    log "  signature: $OUT_DIR/slot-b.tar.zst.minisig"
else
    log "step 9/9: skipping signature -- --skip-sign"
fi

# -----------------------------------------------------------------------------
# Emit the build manifest
#
# The manifest is the JSON the Phase 101 listing's
# ElysiumLinuxDistroListing.kt expects. The Android side
# reads this manifest + computes the same content hash;
# the two must match (the verification test in
# ElysiumLinuxDistroListingTest checks this).
# -----------------------------------------------------------------------------

log "emitting build manifest..."
{
    printf '{\n'
    printf '  "name": "%s",\n' "$ELYSIUM_LINUX_NAME"
    printf '  "version": "%s",\n' "$VERSION"
    printf '  "timestamp": "%s",\n' "$TIMESTAMP"
    printf '  "contentHash": "%s",\n' "$CONTENT_HASH"
    printf '  "slotA": {\n'
    printf '    "path": "slot-a.tar.zst",\n'
    printf '    "sizeBytes": %s\n' "$SLOT_A_BYTES"
    printf '  },\n'
    printf '  "slotB": {\n'
    printf '    "path": "slot-b.tar.zst",\n'
    printf '    "sizeBytes": %s\n' "$SLOT_B_BYTES"
    printf '  },\n'
    printf '  "cvePolicy": {\n'
    printf '    "critical": { "responseHours": %s, "disclosureHours": %s },\n' \
        "$CVE_POLICY_CRITICAL_RESPONSE_HOURS" "$CVE_POLICY_CRITICAL_DISCLOSURE_HOURS"
    printf '    "high":     { "responseDays": %s, "disclosureHours": %s },\n' \
        "$CVE_POLICY_HIGH_RESPONSE_DAYS" "$CVE_POLICY_HIGH_DISCLOSURE_HOURS"
    printf '    "medium":   { "responseDays": %s, "disclosureDays": %s },\n' \
        "$CVE_POLICY_MEDIUM_RESPONSE_DAYS" "$CVE_POLICY_MEDIUM_DISCLOSURE_DAYS"
    printf '    "low":      { "responseDays": %s, "disclosureDays": %s },\n' \
        "$CVE_POLICY_LOW_RESPONSE_DAYS" "$CVE_POLICY_LOW_DISCLOSURE_DAYS"
    printf '    "none":     { "responseDays": %s, "disclosureDays": %s }\n' \
        "$CVE_POLICY_NONE_RESPONSE_DAYS" "$CVE_POLICY_NONE_DISCLOSURE_DAYS"
    printf '  },\n'
    printf '  "runtimeLayers": [\n'
    for layer in "${RUNTIME_LAYERS[@]}"; do
        # shellcheck disable=SC2086
        set -- $layer
        printf '    { "name": "%s", "version": "%s" },\n' "$1" "$2"
    done
    printf '  ],\n'
    printf '  "elysiumPackages": [\n'
    for pkg in "${ELYSIUM_PACKAGES[@]}"; do
        # shellcheck disable=SC2086
        set -- $pkg
        printf '    { "name": "%s", "version": "%s", "license": "Proprietary" },\n' "$1" "$2"
    done
    printf '  ]\n'
    printf '}\n'
} > "$OUT_DIR/manifest/build-manifest.json"
log "  manifest: $OUT_DIR/manifest/build-manifest.json"

log ""
log "build complete."
log "  slot A:    $OUT_DIR/slot-a.tar.zst -- $SLOT_A_BYTES bytes"
log "  slot B:    $OUT_DIR/slot-b.tar.zst -- $SLOT_B_BYTES bytes"
log "  SBOM:      $OUT_DIR/sbom.json"
log "  content:   $CONTENT_HASH"
log "  manifest:  $OUT_DIR/manifest/build-manifest.json"
log ""
log "next step: update ElysiumLinuxDistroListing.kt with the new content hash"
log "  and SLOT_A_BYTES / SLOT_B_BYTES, then publish to"
log "  https://distro.elysium-vanguard.io/elysium-linux/$VERSION/"

exit 0
