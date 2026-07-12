# License compatibility and release obligations

**Status:** Phase 0 engineering assessment
**Last reviewed:** 2026-07-12
**Project license:** not selected
**Public APK distribution:** blocked

This matrix records engineering release gates. It is not legal advice. Final
distribution requires review by qualified counsel using the exact APK, source
offer, store terms and target jurisdictions.

## Decision matrix

| Relationship | Licenses | Assessment | Required action |
|---|---|---|---|
| Elysium APK aggregates and executes PRoot as a child using argv/pipes/PTY | Elysium TBD + GPL-2.0-only | Potentially separable aggregation; not automatically proven by packaging. | Keep process boundary narrow; include GPL text/notices; deliver complete corresponding source or a valid GPLv2 distribution mechanism; do not impose store terms that remove GPL rights. |
| PRoot executable dynamically loads talloc | GPL-2.0-only + LGPL-3.0-or-later | Conditional under LGPL combined-work permissions; obligations remain for replacement/relink and source of library modifications. | Preserve LGPL/GPLv3 texts, prominent notice, library source/changes and a practical relink/replace path; obtain legal confirmation for exact artifacts. |
| PRoot dynamically loads libandroid-shmem | GPL-2.0-only + BSD-3-Clause | Generally compatible with attribution. | Preserve copyright, license and disclaimer in source/binary distributions. |
| Elysium links Apache-2.0 Java libraries | Elysium TBD + Apache-2.0 | Usually compatible with common commercial/open-source choices. | Preserve license and required NOTICE content; document modifications. |
| Elysium links Google ML Kit binary artifacts | Elysium TBD + provider terms | Unknown until exact terms and distribution channel are reviewed. | Capture artifact POM/AAR terms, service terms and model restrictions. |
| User downloads distro rootfs from upstream | many licenses | Use differs from Elysium redistribution. | Preserve in-rootfs notices; show origin/hash. Do not mirror or bundle until package-level source obligations are satisfied. |
| Future Wine/Box64/X11/desktop stack | mixed LGPL/GPL/MIT/BSD | Not yet assessed. | No bundling or capability claim before a component-level ADR, SBOM and matrix update. |

## Blocking findings

### LC-001 — Elysium has no selected source/distribution license

The repository privacy policy states “license TBD”. Copyright permissions for
contributors and compatibility with release terms cannot be established.

**Exit:** choose and add a project license, confirm contributor ownership and
update source headers/notices.

### LC-002 — Corresponding source delivery is not yet reproducible

The APK bundles a modified-packaging PRoot executable and renamed talloc SONAME,
but the repository does not yet contain a deterministic recipe that rebuilds
the exact hashes or an accompanying source bundle/offer workflow.

**Exit:** preserve exact source archives, Termux recipe commit, patches,
toolchain lock and post-link transformations; produce a source archive beside
every binary APK and verify rebuild equivalence where feasible.

### LC-003 — LGPL relink/replace path is not documented

The packaged PRoot binary has a `NEEDED libtalloc2.so` dependency and the
library SONAME was changed. Notices alone do not establish the user replacement
mechanism required by the selected LGPL compliance option.

**Exit:** document and test replacement/relink instructions, provide required
object/source materials, and have counsel approve the path.

### LC-004 — Transitive Android and native SBOM is absent

Gradle direct declarations do not enumerate transitive Java/AAR/JNI artifacts.

**Exit:** generate CycloneDX/SPDX from the resolved release graph and scan the
actual APK for native libraries, models, assets, licenses and notices.

### LC-005 — Dependency license files are excluded from APK packaging

The Gradle packaging block excludes common `META-INF/LICENSE` and `NOTICE`
paths to resolve collisions. Required attributions must therefore be assembled
into a deterministic app-level notice instead of disappearing.

**Exit:** generate and package consolidated notices, expose them in Settings
and test their presence in the release APK.

## Engineering interpretation used

The FSF GPL FAQ describes `exec`, pipes and command-line arguments as mechanisms
normally used between separate programs, while warning that the complete legal
analysis also depends on communication semantics. GPLv2 section 3 requires
object-code distribution to be accompanied by corresponding source or one of
the license's permitted alternatives. Apache guidance requires preservation of
applicable NOTICE material. These sources support the conservative gates above;
they do not replace legal review.

Primary references:

- https://www.gnu.org/licenses/gpl-faq.html#MereAggregation
- https://www.gnu.org/licenses/old-licenses/gpl-2.0.html#section3
- https://www.gnu.org/licenses/lgpl-3.0.html
- https://www.apache.org/legal/apply-license

## Release checklist

- [ ] Project license and contributor rights approved.
- [ ] Exact release dependency graph and APK SBOM generated.
- [ ] All native sources, build scripts, patches and hashes archived.
- [ ] GPL corresponding-source delivery tested from a clean machine.
- [ ] LGPL replacement/relink path tested.
- [ ] Consolidated LICENSE/NOTICE UI and APK assets verified.
- [ ] Rootfs redistribution remains disabled unless separately cleared.
- [ ] Store EULA/DRM terms checked against recipients' open-source rights.
- [ ] Qualified legal review signs off the exact release candidate.
