# ADR-003: Elysium Vanguard Linux derivation strategy

- Status: Accepted
- Date: 2026-07-14
- Owners: Elysium Vanguard runtime
- Depends on: ADR-001
- Governing order: Universal Computing Fabric §11

## Context

The master order §11 names the runtime distribution "Elysium Vanguard
Linux", with a derived `os-release` identity, a reproducible build
pipeline, four named profiles (Lite, Balanced, Desktop, Headless),
layered updates (Base, System, Application, User), and a manifest-signed
update mechanism.

Building a from-scratch ARM64 distribution is a multi-month undertaking
with its own security surface (signing keys, mirrors, build
infrastructure, CVE feed, package selection). The platform already has
working code paths that consume upstream rootfses verbatim: every entry
in `DistroCatalog` is downloaded from its official mirror on first
install, verified by SHA-256, extracted into the app's private
storage, and used as-is. The Elysium Vanguard team does not currently
operate any of the infrastructure a custom distribution would require.

We must therefore decide what "Elysium Vanguard Linux" means
operationally before the order's §11.1, §11.2, and §11.5 become
non-vacuous. Three options are real:

1. **Build and ship a true derived distribution.** Mirror the order:
   bootstrap Debian stable on an ARM64 build host, add the Elysium
   layer, sign and publish under a `elysium` ID. Requires its own
   CI pipeline, signing keys, and a mirror.
2. **Re-brand upstream rootfses with an `os-release` overlay.** Keep
   consuming Debian / Ubuntu / Alpine / Arch from their official
   mirrors. After install, write an `/etc/os-release` snippet
   identifying the system as Elysium Vanguard Linux and drop a
   `Version 1.0.0-TITAN+<phase>` marker. No build pipeline required.
3. **Drop the Elysium Vanguard Linux identity entirely.** Continue
   shipping upstream distros as-is and treat the order's references
   to "Elysium Vanguard Linux" as aspirational. The product
   documentation would say "runs Debian / Ubuntu / Alpine / Arch
   under PRoot", not "runs Elysium Vanguard Linux".

## Decision

**Option 2: re-brand upstream rootfses with an `os-release` overlay.**

The platform identifies itself inside the guest as:

```ini
NAME="Elysium Vanguard Linux"
ID=elysium
ID_LIKE=debian
PRETTY_NAME="Elysium Vanguard Linux"
VARIANT="Android Runtime Edition"
VERSION="1.0.0-TITAN+<phase>"
```

The `ID_LIKE=debian` declaration preserves the upstream behavior the
package manager depends on (e.g. `apt` will still treat the system as
a Debian derivative). The base distribution is recorded in a sibling
field (`ELYSIUM_BASE=debian-stable-13` or similar) so the user can
still see what they're running.

### Why

- **Build cost.** A from-scratch distribution is multiple engineer-months
  of build-pipeline work before a single user can install it. Option 2
  reuses the upstream distribution's package selection, mirrors, and
  CVE feed, and adds a one-file overlay.
- **Security surface.** A new distribution is a new security surface.
  Every CVE on the upstream distro is now two CVE feeds. Option 2
  inherits the upstream's signing key, mirror, and update cadence.
- **Compatibility.** `apt` / `apk` / `pacman` already know how to
  install on the upstream distro. Option 2 keeps that working with
  no shim.
- **Reversibility.** The overlay is a single file. We can ship option
  2 today and graduate to option 1 later without breaking user data:
  the user upgrades, the overlay is replaced by a real os-release,
  and their packages are unchanged.

### What the overlay looks like

Installed into every guest at provisioning time, regardless of
underlying distro:

```text
/etc/os-release.d/elysium.conf
/etc/elysium/VERSION
/etc/elysium/BASE_DISTRO      # e.g. "debian-stable-13"
/etc/elysium/CHANNEL          # "stable" | "beta" | "nightly"
/opt/elysium/bin/elysium-cli   # symlink to a Python or shell shim
                              # that calls the host's bridge
```

The `/etc/os-release.d/` directory is read by `systemd` and most
distro-detect tools in addition to `/etc/os-release` itself, so the
overlay survives `apt` upgrades that rewrite `/etc/os-release`.

### Profiles

The order's §11.4 named four profiles (Lite, Balanced, Desktop,
Headless). Option 2 implements profiles as **package sets on top of
the same base rootfs**, not separate rootfses:

- **Lite**: `openbox`, `lxterminal`, `pcmanfm`, base system.
- **Balanced**: `xfce4`, `xfce4-terminal`, `thunar`, base system.
- **Desktop**: `lxqt`, `qterminal`, `pcmanfm-qt`, base system.
- **Headless**: base system only. No X.

Each profile is a metapackage `elysium-profile-<name>` that the
provisioner installs during `apt-get install` after the base rootfs
is laid down. The catalog exposes all four for the same base distro.

### Layers

The order's §11.3 named four layers (Base, System, Application, User).
Option 2 maps them to overlayfs-style upperdirs:

- **BaseLayer**: the upstream rootfs, mounted read-only.
- **SystemLayer**: `/etc/elysium`, `/opt/elysium`, `/usr/local/elysium`.
  Read-write but stamped by the host so it survives rollback.
- **ApplicationLayer**: `/opt/apps/<capsule-id>`. Per-application
  filesystem views from the master order §14. Optional.
- **UserLayer**: `/home/elysium`, `/data/media/elysium/<user-id>`.
  Personal data, never touched by updates.

This is not full overlayfs (Android's kernel does not always expose
it cleanly); the first cut writes a tarball diff into the SystemLayer
on every update and applies it at session start. A future phase can
swap the tarball diff for an actual overlayfs mount when the kernel
s the API.

### Update model

The order's §11.5 calls for a signed manifest, integrity verification,
atomic apply, snapshot before update, rollback. Option 2 keeps that
contract for the SystemLayer and ApplicationLayer; the BaseLayer is
immutable (the upstream distro provides its own update channel and
security feed).

The manifest is signed with the team's offline key; the public key
ships in the APK. Snapshots are journaled before each update; rollback
restores the previous SystemLayer tarball.

## Consequences

Positive:

- The order's §11.1, §11.2, §11.3, §11.4, §11.5 become non-vacuous in
  a single phase, without a multi-month build-pipeline investment.
- A user running `cat /etc/os-release` inside the guest sees
  `Elysium Vanguard Linux`, with `ID_LIKE=debian` so package
  management continues to work.
- The platform inherits the upstream distribution's CVE feed,
  mirror, and signing chain.
- The Elysium layer is reversible: deleting `/etc/os-release.d/elysium.conf`
  returns the guest to a vanilla upstream.

Negative:

- A user running `lsb_release -a` or similar will see the
  `ID=elysium` declaration but the upstream distro's package
  version strings. The two views can disagree.
- The "Elysium Vanguard Linux" name is a marketing / branding
  identity, not a security-isolated distribution. A CVE on the
  upstream distro affects us.
- Profiles are not separate rootfses; a user who installed Lite and
  upgrades to Desktop gets the new packages layered onto the same
  BaseLayer. This is fine for software but means the rootfs grows
  over time.
- A future move to option 1 (a real derived distribution) requires
  re-publishing the SystemLayer manifests under the new build
  pipeline. The data model survives but the build-side work is
  substantial.

## Alternatives considered

- **Option 1, full derived distribution.** Rejected: build cost,
  security surface, and maintenance burden are incompatible with
  the current engineering team size. Documented as the path
  forward if the team grows.
- **Option 3, drop the Elysium Vanguard Linux identity.** Rejected:
  the order explicitly names the product. Without a consistent
  identity, the master order's §11, §14, and §22 (Application
  Capsules, Workspaces) all become harder to reason about
  because the system has no canonical name.
- **Re-brand at the app level only, not inside the guest.**
  Rejected: a user running `apt-cache policy` inside the guest
  would see the upstream distro's ID and packages. A package the
  team marks as "Elysium-provided" would not be distinguishable
  from an upstream package without an in-guest marker.

## Rollback

- Remove `/etc/os-release.d/elysium.conf` and `/opt/elysium` from
  the provisioning step. The guest reverts to a vanilla upstream
  distro on next install.
- The SystemLayer and ApplicationLayer tarballs keep working
  without the os-release overlay; the only thing that breaks is
  user scripts that grep for `elysium` in `/etc/os-release`.

## Risks

- **`ID=elysium` breaks a script that hard-codes a check for
  `debian` or `ubuntu`.** Mitigation: keep `ID_LIKE=debian` so
  `id_like`-based detection still matches.
- **The user thinks they have a security-isolated distribution
  when they only have a label.** Mitigation: the documentation
  must say, plainly, "Elysium Vanguard Linux is a labeling layer
  on top of Debian / Ubuntu / Alpine / Arch. It is not a security
  boundary. CVE feed is inherited from the upstream."
- **Two users on the same device, with different channel settings
  (stable vs beta), get inconsistent behavior.** Mitigation: the
  channel is a property of the workspace (master order §22), not
  the device. The Elysium layer is the same; the user-data layer
  is what differs.

## Status

- Decision recorded: 2026-07-14.
- Implementation: not yet started. The first phase that ships the
  overlay is a `ElysiumOsReleaseOverlay` installer invoked by
  `DistroInstaller` after extraction. Tracking under Phase 12.1.
- The `ElysiumCli` shim in `/opt/elysium/bin` is a future phase; it
  will call into the host's bridge via the master order §18
  hardware broker.
