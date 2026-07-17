---
name: mobile-forge-ux
description: The field UX. The on-device forge: the user designs, reviews, and ships a vehicle from a phone. The mobile renderer. The offline-first patterns. The UX that turns a complex engineering platform into a tool a person uses in the field.
---

# Skill 11 — Mobile + Forge UX

## 1. Mission

Build the **field UX** — the on-device forge where
the user designs, reviews, and ships a vehicle
from a phone. The mobile renderer. The offline-
first patterns. The UX that turns a complex
engineering platform into a tool a person uses
in the field.

The mobile UX is the platform's "I can do this
from a phone" surface. Without it, the platform is
a desktop-only tool. Without offline-first, the
field mechanic is stranded when the connection
drops. Without the renderer, the 3D asset is
invisible. Without the
`VehicleRepresentationLevel` displayed
prominently, a `VISUAL_ONLY` vehicle is
mistaken for production-ready. The mobile UX
is the platform's surface for the field, the
designer, the mechanic, the buyer, and the
reviewer.

## 2. Main navigation — FORGE

The mobile app exposes a **primary area** named
**FORGE**. The FORGE area is the platform's
"build a vehicle from a phone" surface. The
**8 primary actions** are:

- **Create brand.** Start a new brand
  (the legal entity that owns the
  vehicle program).
- **Create vehicle.** Start a new
  vehicle (a `VehicleDefinition`).
- **Continue project.** Resume a
  project the user is already
  working on.
- **Join project.** Join a project
  the user is invited to.
- **Explore licensed projects.**
  Browse the marketplace (per skill
  10) for projects the user is
  authorized to see.
- **Talk to AI Council.** Open the
  AI council (per skill 05) for a
  deliberation.
- **Review engineering findings.**
  Open the engineering review (the
  compatibility constraints, the
  diagnostic findings, the
  validation results).
- **Review commercial status.**
  Open the commercial status (the
  royalty settlement, the contract
  version, the marketplace listing).

The FORGE area is **the only area** the
designer needs for day-to-day work. The
other areas (the diagnostics, the
marketplace, the catalog) are accessible
from the project workspace (per section
3) but are not in the FORGE primary
navigation.

## 3. Project workspace

The project workspace is the **14-section
contextual view** for a project. Every
section is a dedicated screen; the user
navigates between sections via the bottom
navigation bar (iOS / Android standard).

The 14 sections are:

1. **Overview.** The project
   summary (the vehicle + the
   brand + the team + the
   status).
2. **Requirements.** The PRD
   + the requirements hierarchy
   (per skill 02).
3. **Vehicle architecture.** The
   `Spec.Artifact` (per skill 04)
   + the `SceneManifest` (per
   skill 04) + the
   compatibility constraints.
4. **3D digital twin.** The
   interactive 3D scene (per
   skill 06) + the
   `VehicleRepresentationLevel`
   badge (per `.ai/STANDARDS.md`
   section 4) + the asset
   picker.
5. **Components.** The
   `PartDefinition`s + the
   `PartInstance`s + the
   compatibility solver (per
   skill 04).
6. **AI Council.** The
   deliberation UI (per skill 05)
   + the proposals + the
   evidence per proposal.
7. **Simulations.** The
   simulation results (per
   skill 07) + the digital twin
   state.
8. **Diagnostics.** The
   diagnostic graph (per skill
   07) + the active DTCs + the
   repair actions.
9. **Repairability.** The
   `Procedure`s + the
   `RepairAction`s + the
   `Tool`s.
10. **Manufacturing.** The
    11 manufacturing readiness
    gates (per skill 10) + the
    supplier network + the
    controlled disclosure.
11. **IP and contributions.**
    The `AuthorshipClaim`s (per
    skill 09) + the contribution
    graph + the AI assistance
    metadata.
12. **Contracts.** The
    `RoyaltyContract`s (per
    skill 09) + the versions +
    the migration policies.
13. **Marketplace.** The
    listings (per skill 10) +
    the offers + the RFQs.
14. **Release history.** The
    per-release artifacts (per
    `.ai/AGENTS.md` section 9
    + section 21) + the
    changelogs.

A project workspace without all 14
sections is a contract violation; the
verifier (skill 14) rejects the release.

## 4. Mobile interaction model

The mobile UX uses a **10-element
interaction model**. The model is the
discipline that prevents the viewport
from being crowded with controls.

- **Full-screen 3D viewport.** The
  3D scene takes the full screen;
  the controls are hidden until
  requested.
- **Bottom sheet inspector.** The
  per-selection details (a
  `PartInstance`'s properties +
  the diagnostic findings + the
  repair actions) are in a
  bottom sheet that the user
  can expand / collapse.
- **Search command palette.** A
  global command palette (the
  user types "battery" + the
  palette shows the matching
  `PartInstance`s + the
  matching `Procedure`s +
  the matching suppliers).
- **Contextual actions.** The
  per-screen actions (the
  "Save" + the "Validate" + the
  "Publish") are in a
  contextual action bar.
- **Gesture-safe camera
  controls.** The 3D camera
  supports single-finger pan +
  two-finger pinch + two-finger
  rotate; a gesture that
  conflicts with a system
  gesture is disabled.
- **Selection breadcrumbs.** A
  breadcrumb trail of the
  current selection (e.g. "Project
  > Vehicle > Powertrain >
  Battery pack") so the user
  never gets lost.
- **Accessible list equivalent.**
  Every 3D scene has a list view
  (per `.ai/STANDARDS.md` section
  2.2 + per skill 11's i18n bundle)
  for blind + low-vision users.
- **Loading progress by
  subsystem.** A loading
  indicator per subsystem (per
  skill 06's streaming priority
  — vehicle shell first, then
  active subsystem, then
  details on selection); a
  user never sees a blank
  scene.
- **Explicit representation
  level.** Every vehicle card
  + every vehicle detail page
  + every spec view displays
  the `VehicleRepresentationLevel`
  prominently. A `VISUAL_ONLY`
  or `CONCEPTUAL` vehicle's
  UI includes a "this is not
  validated" warning.
- **Explicit data confidence.**
  Every fact the user sees has
  the `verificationStatus`
  (per `.ai/STANDARDS.md` section
  3) + the confidence + the
  source. A fact that is
  `AI_INFERRED` is flagged; a
  fact that is `UNKNOWN` is
  refused.
- **Do not crowd the viewport
  with all controls
  simultaneously.** A 3D
  scene that shows 20 floating
  buttons is a usability
  failure. The controls are
  curated; the user requests a
  control when needed.

A mobile UX that violates the 10
elements is a contract violation; the
verifier (skill 14) rejects the release.

## 5. In-scope

- The mobile UX (Jetpack Compose / SwiftUI).
- The mobile renderer (Filament for Android/iOS,
  Three.js for the web).
- The offline-first sync (the local DB, the
  sync engine, the conflict resolution).
- The field diagnostic UX (the mechanic's
  workflow: plug in, read codes, see root
  cause, follow repair manual, validate fix).
- The forge UX (the designer's workflow: sketch
  the spec, review the 3D, validate, publish).
- The marketplace UX (the buyer's workflow:
  browse, search, filter, compare, save, order,
  review).
- The accessibility (TalkBack, large fonts, high
  contrast, switch control, voice control).
- The internationalization (10+ languages, RTL
  support, locale-aware formatting).
- The on-device security (the biometric unlock,
  the secure enclave for keys, the
  certificate-based auth).
- **The `VehicleRepresentationLevel` UI
  surface.** Every vehicle card, vehicle
  detail page, and spec view MUST display
  the level prominently. A `VISUAL_ONLY` or
  `CONCEPTUAL` vehicle MUST include a
  "this is not validated" warning. An
  `OEM_PARTIAL` vehicle MUST show which
  parts are OEM-sourced and which are not.
  The UI MUST NOT allow a `VISUAL_ONLY` or
  `CONCEPTUAL` vehicle to be listed in the
  marketplace, settled for royalties, or
  submitted for regulatory approval.

## 6. Out-of-scope

- The backend (skill 08).
- The 3D pipeline (skill 06).
- The diagnostic (skill 07).
- The marketplace (skill 10).
- The royalty (skill 09).
- The auth provider (skill 12).

The mobile consumes the catalog (skill 09), the
diagnostic (skill 07), the marketplace (skill 10).
The mobile renders the 3D (skill 06). Each is its
own concern.

## 7. Inputs

- The user (a designer, a mechanic, a buyer, a
  project owner, a supplier).
- The platform (the catalog, the spec, the
  diagnostic, the marketplace — all via the
  backend event platform, skill 08).
- The device (a phone, a tablet, a watch — the
  UX adapts to the form factor).
- The connectivity state (online, offline, low
  bandwidth).
- The user's preferences (language, theme,
  accessibility settings).

## 8. Outputs

- The mobile app (the binary the user installs).
- The mobile renderer (the 3D + 2D rendering
  pipeline).
- The offline-first sync engine (the local DB +
  the sync algorithm + the conflict resolution).
- The field diagnostic UX (the mechanic's
  workflow).
- The forge UX (the designer's workflow).
- The marketplace UX (the buyer's workflow).
- The accessibility audit (the report the
  skill produces for every release).
- The internationalization bundle (the strings
  + the resources for every supported locale).

The app ships as a signed binary. The renderer
is a reusable component (the 3D pipeline ships
a 3D asset, the mobile consumes it via the
renderer). The offline-first engine is a
reusable library (any skill can use it to add
offline support to a new surface).

## 9. Workflow

1. **Receive the user.** A user opens the app.
   The app:
   - Authenticates (skill 12).
   - Loads the local DB.
   - Starts the sync engine.
   - Initializes the renderer.
2. **Render the catalog.** The user browses the
   catalog. The renderer loads the 3D LODs
   (skill 06). The UI is responsive (no
   jank).
3. **Forge.** The user designs. The mobile UX
   exposes the DSL (skill 04) — text + visual
   — backed by the same compiler the desktop
   uses. The mobile validates locally before
   syncing.
4. **Field diagnostic.** The mechanic plugs in.
   The mobile reads the fault codes, displays
   the diagnostic (skill 07), shows the repair
   manual. The mobile works offline; the sync
   uploads the trace when the connection is
   back.
5. **Marketplace.** The buyer browses, searches,
   filters, compares, saves, orders, reviews.
   The mobile renders the listing's 3D LODs.
6. **Sync.** The sync engine:
   - Pushes local changes.
   - Pulls remote changes.
   - Resolves conflicts (3-way merge for text;
   last-write-wins for telemetry; manual
   review for spec changes).
   - Caches for offline.
7. **Secure.** The app:
   - Uses the platform's secure enclave for
     keys.
   - Uses biometric unlock.
   - Wipes local data on logout.
   - Audits every action (skill 09).
8. **Ship.** The app releases on the App Store
   + Play Store + sideload. The release is
   signed + notarized + reproducible.

## 10. Quality gates

- The app is offline-first.
- The app is accessible (TalkBack, large
  fonts, high contrast, switch, voice).
- The app is internationalized (10+ languages,
  RTL).
- The app is signed + notarized.
- The app's binary is reproducible.
- The renderer hits 60fps on a 6-year-old
  phone.
- The sync engine handles 10k+ events without
  dropping any.
- The local DB is encrypted at rest.
- The biometric unlock is fallible (a wrong
  fingerprint falls back to a PIN, not to
  bypass).
- **The `VehicleRepresentationLevel` badge is
  visible on every vehicle card.** A snapshot
  test asserts the badge is rendered for every
  level. A test asserts the badge is removed
  — the test fails — when the level is
  removed from the data class (i.e. the level
  is required, not optional).
- **A `VISUAL_ONLY` or `CONCEPTUAL` vehicle
  shows the "not validated" warning.** A
  snapshot test asserts the warning is
  rendered. The warning is in every supported
  locale.
- **A `VISUAL_ONLY` or `CONCEPTUAL` vehicle
  cannot be listed in the marketplace,
  settled for royalties, or submitted for
  regulatory approval from the UI.** A
  Compose UI test asserts the corresponding
  buttons are disabled or hidden.
- **An `OEM_PARTIAL` vehicle shows a per-part
  provenance breakdown.** A snapshot test
  asserts the breakdown is rendered.
- **The Android main thread is never blocked
  by model loading, decoding, or network
  work.** Every heavy operation is on
  `Dispatchers.IO`. The Compose render path
  is non-blocking. A `StrictMode` test asserts
  no main-thread disk or network.

## 11. Failure modes

- **The user is offline.** The app continues to
  work; the sync uploads when the connection
  is back.
- **The user is on a 6-year-old phone.** The
  app degrades gracefully (the renderer drops
  to a lower LOD; the UI hides features the
  device cannot run).
- **The 3D asset fails to load.** The app
  shows a placeholder + a retry button. The
  failure is in the audit trail.
- **The sync engine fails to upload.** The
  app queues the events; the next sync
  retries. After N retries, the user is
  asked to investigate.
- **The user loses the device.** The remote
  admin (skill 12) wipes the local data. The
  user's data is in the cloud, signed +
  encrypted + recoverable.

## 12. Coordination contract

- **Input from**: skill 04 (spec), skill 06
  (3D), skill 07 (diagnostic), skill 10
  (marketplace), the user.
- **Output to**: skill 08 (events), skill 09
  (catalog), skill 12 (auth).
- **Triggered by**: every user interaction.
- **Frequency**: continuous.

## 13. Forbidden patterns

- **Online-only.** An app that requires a
  network is a contract violation. The field
  is offline.
- **Janky rendering.** A 3D scene that drops
  frames is a contract violation. The LODs
  exist for a reason.
- **Untranslated strings.** A string that is
  in English only is a contract violation. The
  i18n bundle is mandatory.
- **Inaccessible UI.** A UI that is not
  TalkBack-friendly is a contract violation.
  The accessibility audit is mandatory.
- **Unencrypted local data.** A local DB that
  is not encrypted is a contract violation.
- **Bypassable biometric.** A biometric unlock
  that can be bypassed by removing the
  battery is a contract violation.
- **Custom animation libraries.** A library
  that re-implements the platform's animation
  is a contract violation. Use the platform's
  animation.
- **Two design systems.** A "dark theme" + a
  "light theme" + a "high contrast theme" + a
  "compact theme" is a design system. Pick
  one + the platform's theme.
- **Hiding the `VehicleRepresentationLevel`.**
  A vehicle card without the level badge is
  a contract violation. The level is not a
  tooltip; the level is a first-class field
  on the card.
- **Presenting a `VISUAL_ONLY` or `CONCEPTUAL`
  vehicle as production-ready.** A UI that
  shows the "not validated" warning as a
  dismissible toast is a violation. The
  warning is a persistent badge + a
  per-action confirmation dialog.
- **Blocking the Android main thread.** A
  model load, a decode, or a network call on
  the main thread is a contract violation
  (per `.ai/AGENTS.md` section 5.4). Every
  heavy operation is on `Dispatchers.IO`.
- **Storing secrets in the app package.** A
  secret in the binary, the assets, the
  config, or the build artifacts is a P0
  incident (per `.ai/AGENTS.md` section 5.5
  and skill 12). Secrets live in the vault
  + the secure enclave.

## 14. The mobile UX in the Elysium Automotive
Foundry

The mobile UX is the platform's "I can do this
from a phone" surface. The designer sketches a
spec on a tablet. The mechanic reads the
diagnostic on a phone. The buyer orders a part
on a watch. The project owner reviews the
royalty statement on a laptop. The mobile UX
turns the platform into a tool a person uses
in the field.

The mobile UX is the platform's "we meet the
user where they are" answer.

## 15. Working with this skill

When invoked, this skill:

1. Receives the user's intent (a screen, a
   workflow, a feature).
2. Designs the UX (low-fidelity sketch first;
   high-fidelity after council approval).
3. Implements the screen (Compose / SwiftUI).
4. Tests the screen (unit tests for the ViewModel
   + Compose UI tests for the rendering + a
   real-device smoke test for the integration).
5. Audits the screen (accessibility, i18n,
   performance, security).
6. Ships the screen (in a release).

The skill does not implement the backend
(skill 08). The skill does not implement the
3D pipeline (skill 06). The skill does not
implement the royalty (skill 09). The skill is
the **user-facing surface** that turns the
platform's capabilities into a tool.
