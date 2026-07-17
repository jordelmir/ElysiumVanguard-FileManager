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
invisible.

## 2. In-scope

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

## 3. Out-of-scope

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

## 4. Inputs

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

## 5. Outputs

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

## 6. Workflow

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

## 7. Quality gates

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

## 8. Failure modes

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

## 9. Coordination contract

- **Input from**: skill 04 (spec), skill 06
  (3D), skill 07 (diagnostic), skill 10
  (marketplace), the user.
- **Output to**: skill 08 (events), skill 09
  (catalog), skill 12 (auth).
- **Triggered by**: every user interaction.
- **Frequency**: continuous.

## 10. Forbidden patterns

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

## 11. The mobile UX in the Elysium Automotive
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

## 12. Working with this skill

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
