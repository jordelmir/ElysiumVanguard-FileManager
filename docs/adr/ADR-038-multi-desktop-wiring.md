# ADR-038 — Multi-Desktop Shell as the default desktop surface

**Date**: 2026-07-23
**Status**: Accepted
**Phase**: 115

## Context

Phase 113 introduced a **multi-desktop shell** ([MultiDesktopShellScreen] +
[MultiDesktopShellViewModel]) that added session tabs (Spaces / Virtual
Desktops) on top of the existing single-session shell. The intent was to
make the DESKTOP card on the dashboard open the multi-shell — the strict
superset of the single-shell (same windows, dock, layout mode + per-session
isolation).

In Phase 113, the multi-shell was created as a new Composable + ViewModel
but **never wired into the navigation graph**. The DESKTOP card continued
to route to the single-session shell. Additionally, when a developer (or
test) did reach the multi-shell, six of the window-state callbacks were
no-ops because the multi-ViewModel did not expose the methods the screen
called.

Visual review of the Android device in Phase 115 caught all three issues
together.

## Decision

1. **The `desktop_shell` route now resolves to `MultiDesktopShellScreen`.**
   The single-session shell is no longer reachable from the dashboard; it
   remains available for tests that exercise single-session invariants
   directly.
2. **`MultiDesktopShellViewModel` exposes the full window-state-machine
   surface** (`focusWindow`, `minimizeWindow`, `maximizeWindow`,
   `restoreWindow`, `updateWindowBounds`, `pinApp`) — all of which
   delegate to the **active session**.
3. **`MultiDesktopShellScreen` is wired end-to-end.** The `SessionTabStrip`
   is pinned to `TopStart` with `zIndex(1f)` so it draws above the
   desktop content (which would otherwise overlap it).

## Consequences

- **Single source of truth for the desktop surface**: any new window-state
  method added to `DesktopShellViewModel` must be added to
  `MultiDesktopShellViewModel` as well. The two VMs are now in lockstep.
- **Per-session state isolation is a contract**: the multi-VM only mutates
  `sessions[activeIndex]`. Tests cover the invariant explicitly
  (`focusWindow targets the active session only`).
- **The dashboard's DESKTOP card is the canonical desktop surface** —
  no need to expose a separate "spaces" / "virtual desktops" affordance.
- **Test feedback** (15 new tests for the new VM methods; 0 regressions):
  the new methods exercise the active-session invariant + the
  window-state-machine rules.

## Alternatives considered

- **Two separate routes (`desktop_shell` for single, `desktop_spaces` for
  multi)**: rejected because the multi-shell is a strict superset; there
  is no reason to expose the weaker single-shell to the user.
- **Use `hiltViewModel()` with a `@HiltViewModel` annotation on
  `MultiDesktopShellViewModel`**: rejected because the single-session VM
  uses an explicit `ViewModelProvider.Factory` so the tests can inject a
  deterministic clock. Switching to Hilt would make the test seam harder
  to wire. (Both VMs are constructed the same way — `MutableStateFlow` +
  `TimestampSource`.)
- **Compose the multi-shell as a child of the single-shell (the
  single-shell renders the tab strip + delegate)**: rejected because the
  multi-shell has different state semantics (list of sessions vs. single
  session) and a different VM. A wrapper is cleaner than a polymorphic
  shell.
