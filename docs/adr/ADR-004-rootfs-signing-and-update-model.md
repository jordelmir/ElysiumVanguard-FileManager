# ADR-004: Rootfs signing and update model

- Status: Accepted
- Date: 2026-07-14
- Owners: Elysium Vanguard runtime
- Depends on: ADR-003
- Governing order: Universal Computing Fabric §11.5

## Context

Master order §11.5 mandates a signed update path for the
SystemLayer and ApplicationLayer pieces of the runtime. The
existing code (Phase 12.2) verifies the SHA-256 of each layer's
tarball but not the integrity of the *manifest* that lists them.
A signed manifest closes the gap:

- A manifest whose tarballs have valid SHA-256s but is itself
  authored by an attacker is currently accepted: the manifest
  can point at any set of tarballs the attacker chooses, as long
  as their hashes match the tarballs they actually serve.
- The tarballs are also vulnerable to "hash collision" attacks
  if the upstream CA / mirror is compromised; a manifest-level
  signature from a key the device trusts means the device will
  only accept the *exact* tarball set the team signed.

Phase 12.2 already shipped the SHA-256 per tarball and the
snapshot / rollback machinery. Phase 12.4 adds the manifest
signature on top, plus the operational key model that makes
signing practical.

## Decision

### Algorithm

**Ed25519** for the manifest signature.

Ed25519 is the right default:

- It is in the JDK since Java 15 (we ship JDK 17, so no new
  dependency).
- It is fast (hundreds of thousands of verifications per
  second on commodity ARM64).
- It produces compact 64-byte signatures and 32-byte public
  keys, which keep the manifest update over the wire small.
- It is the de-facto standard for supply-chain signing (used by
  sigstore, sigsum, and the major Linux distributions for
  reproducible-builds attestations).
- Its 128-bit security level is sufficient for "the device
  trusts the team that built it" — a quantum-relevant threat
  would need a separate analysis and a planned migration to
  PQ-signed manifests, which Phase 12.4 documents as a future
  concern.

### Key model

The team maintains one **offline signing key** per release
channel. The private key never touches a network-connected
machine. The corresponding public key ships in the APK at a
fixed path (`assets/elysium/keys/<channel>.pub`) and is loaded
at install time.

| Channel  | Private key location           | Public key in APK                          |
|----------|--------------------------------|--------------------------------------------|
| `stable` | Offline HSM, 1-of-1 operator    | `assets/elysium/keys/stable.pub` (Ed25519)  |
| `beta`   | Same offline HSM                | `assets/elysium/keys/beta.pub`             |
| `nightly`| CI secret store (lower trust)   | `assets/elysium/keys/nightly.pub`           |

The private key for `stable` and `beta` is *offline* in the
strongest sense: a hardware token (YubiKey, Nitrokey) that never
attaches to a network-connected machine. The nightly key can
live in CI because a compromise of nightly affects only users
who opt into the pre-release channel; a compromise of stable
would push a malicious update to every device.

The current channel is part of the APK build flavor
(`assembleStableDebug`, `assembleStableRelease`, etc.) so the
right public key is bundled for the right audience.

### Manifest format

The signed manifest is two files on the wire:

```
manifest.json       # the existing JSON, no signature fields
manifest.json.sig   # 64 raw bytes (Ed25519), base64 in the file
```

The signature is over the **exact bytes** of `manifest.json` —
not the parsed structure, not a re-serialization. This avoids
canonicalization drift (e.g. field order, whitespace, number
formatting) that has bitten other supply chains.

The build pipeline:

1. The build host signs `manifest.json` with the channel's
   private key and writes `manifest.json.sig` next to it.
2. The CDN serves both files.
3. The device fetches both, verifies the signature, and only
   then parses the manifest.

A manifest without a `.sig` file is **rejected**; there is no
unsigned-update escape hatch. The master order §11.5
explicitly says "manifest firmado" — we interpret that as
"no manifest, no update".

### Verification on the device

`ManifestVerifier.verify(manifestBytes, signatureBytes, channel)`:

1. Load the public key for the requested channel from APK
   assets. If the key is missing, the channel is unknown, the
   update is rejected.
2. Construct an `Ed25519` verifier with the public key.
3. Call `verify(manifestBytes, signatureBytes)`. If the result
   is `false`, reject the update with a typed error.
4. On success, hand the manifest bytes to the existing
   `SystemLayerManifest.load` for parsing.

The verifier is **strict** — it never falls back to "trust the
SHA-256 alone". A failed signature is a security event, not a
degraded mode.

### What is *not* signed

- The individual tarball bytes — they have their own SHA-256 in
  the manifest. The manifest signature transitively authenticates
  the tarball bytes: a manifest signed by the team is
  authoritative, and the team has already verified the
  tarball's hash against their build output.
- The rootfs itself. The rootfs is downloaded from the upstream
  distro's mirror and verified by the distro's own GPG
  signatures. The Elysium team does not (and should not)
  re-sign the rootfs; the layering model is what we own.
- The `os-release.d/elysium.conf` overlay. The overlay is part
  of provisioning (Phase 12.1) and is installed by the
  `DistroInstaller` from code shipped in the APK, not from a
  network artifact. Its integrity is guaranteed by the APK
  signature.

### Key rotation

Rotating the signing key is a deliberate, multi-release
operation:

1. The new key is generated offline. The corresponding public
   key is added to `assets/elysium/keys/<channel>.pub.next`.
2. For one release, the APK carries BOTH the old and the new
   public key. A manifest signed by either key is accepted.
3. The new key signs releases going forward. The old key
   continues to sign emergency hotfixes for the previous
   release.
4. After the previous release goes out of support, the old
   public key is removed. From this point, only manifests
   signed by the new key are accepted.

This is the same flow used by the major Linux distributions
when they rotate distro-signing keys; documented here so the
operational team has a runbook.

## Consequences

Positive:

- A compromised CDN or mirror cannot push a malicious update:
  the attacker would need the team's private key, which never
  leaves the offline HSM.
- A device that has been tampered with (e.g. a fake APK) does
  not get updates that match the real team's manifest — the
  tampered APK's public key won't verify the team's signatures.
- The verification path is small and well-isolated: 64-byte
  signature + 32-byte key + a one-line `Ed25519.verify` call.
  It is JVM-native (no Tink, no Bouncy Castle), so it has no
  new supply-chain surface.

Negative:

- Key rotation is a multi-release operation. We accept this
  cost; rotating more often would mean more "stuck on old
  release" devices in the field.
- The build pipeline now has a signing step that depends on an
  offline HSM. This is a real operational cost. We accept it
  because the threat model (malicious update) is severe enough
  to warrant the friction.
- Ed25519 is not post-quantum. The master order §11.5 does not
  call for PQ signatures today, but we flag it here so the
  next rotation can move to a hybrid (Ed25519 + ML-DSA) if
  the threat model changes.

## Alternatives considered

- **RSA-PSS / RSA-PKCS1v1.5.** Rejected: larger signatures and
  keys, slower verification, no upside for our use case.
  Ed25519 is the modern default.
- **HMAC with a shared secret in the APK.** Rejected: a shared
  secret leaks (once it leaks, all devices are compromised);
  asymmetric signing lets the public key be public without
  compromising the private key.
- **In-toto attestations.** Rejected as the primary mechanism:
  they are heavier, the in-toto spec is a moving target, and
  Ed25519-over-the-manifest covers the actual threat (a
  swapped manifest).
- **minisign.** Considered: a thin Ed25519 wrapper with a
  friendly CLI. Rejected because the JDK already gives us
  Ed25519 and adding a dependency for what is essentially
  "Ed25519 + base64" is not worth the supply-chain surface.
- **Trust on first use with TOFU pinning.** Rejected: the team
  has an offline key, the public key ships in the APK. TOFU
  is what you do when you have neither, which is not our case.

## Rollback

If the signing key is lost or compromised:

- The team can publish an "unsigned update" mode that REQUIRES
  a separate, user-visible "trust this update" gesture. The
  user must explicitly accept an unverified update. This is
  the only scenario where the master order §11.5 "manifest
  firmado" rule is relaxed.
- The team can ship a one-time APK update that swaps the
  embedded public key. This is a regular APK upgrade from the
  user's POV; the new key is committed at build time and
  shipped via the standard Play / sideload flow.

Neither of these is a happy path. Documented here so the
operational team has a runbook.

## Risks

- **Key loss.** If the offline signing key is destroyed and no
  backup exists, every device must accept a one-time
  unverified update. Mitigated by storing the private key on
  two independent HSMs in geographically separate locations.
- **HSM compromise.** If the signing HSM is compromised, an
  attacker can sign malicious updates that the device accepts.
  Mitigated by a hardware PIN on the HSM, a separate
  revocation flow that lets the team publish a "this key is
  revoked" manifest that devices can pin, and a key-rotation
  cycle that limits the blast radius.
- **Ed25519 weakness discovered.** The Ed25519 algorithm has
  been studied since 2011; no practical break is known. A
  theoretical break would force a hard key rotation. The
  rotation flow above is the same path.
- **APK tampering.** A tampered APK with a fake public key
  would still accept the real team's signatures — the attacker
  has changed which signatures the device trusts. Mitigated
  by Play Integrity / sideload signature verification on the
  APK itself, which is the Android platform's responsibility
  and out of scope for this ADR.

## Status

- Decision recorded: 2026-07-14.
- Implementation: shipped in this Phase 12.4 commit
  ([`ManifestSigner`](../../app/src/main/java/com/elysium/vanguard/core/runtime/distros/layer/ManifestSigner.kt),
  [`ManifestVerifier`](../../app/src/main/java/com/elysium/vanguard/core/runtime/distros/layer/ManifestVerifier.kt)).
- Key generation: not yet performed. The team needs to run
  the documented key ceremony before the first signed
  release.
- Build pipeline signing step: Phase 13.x. The current
  manifest is unsigned on the wire; the verifier rejects
  manifests without a signature, which is a deliberate
  "fail closed" choice.
