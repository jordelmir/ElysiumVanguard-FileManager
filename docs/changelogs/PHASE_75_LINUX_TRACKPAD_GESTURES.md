# Phase 75 — Linux Desktop trackpad gestures (Microsoft-style pointer)

The user asked for a Microsoft-style trackpad experience for the
Linux Desktop screen:

  - 1-finger drag → mouse move (no button)
  - 2-finger tap   → left click
  - 2-finger drag  → left click + drag (selection)
  - 3-finger tap   → right click

The previous Phase 74 redesign shipped a polished visual shell
but the actual touch surface (the VNC framebuffer) had no
gesture interpretation — the user had to interact with the
guest via... well, nothing. Phase 75 wires the trackpad.

## What shipped

### 1. The trackpad gesture detector

`app/src/main/java/com/elysium/vanguard/features/runtime/desktop/TrackpadGestures.kt`

A [Modifier] extension that intercepts pointer events before
they reach the underlying [RfbSurfaceView] and dispatches
RFB pointer events via the [RfbSession].

The state machine:

  - `Track1` — 1 finger down. Every move event becomes a
    `sendPointer(x, y, BUTTON_NONE)` (mouse move).
  - `Track2` — 2 fingers down. Every move event becomes a
    `sendPointer(x, y, BUTTON_LEFT)` (left button held + move).
  - `Track3` — 3 fingers down. No move events dispatched
    (the right-click fires on the final release).

Transitions:

  - 1 → 2 fingers: a new finger joined → mode is now Track2.
  - 2 → 3 fingers: a new finger joined → mode is now Track3.
    The 2 → 3 transition also sends a defensive `button up`
    so the server-side mouse is never left in a pressed state
    if the user starts a right-click during a left-drag.
  - 2 → 1 fingers: one finger lifted during a left drag →
    send `button up`, mode is now Track1.
  - 3 → 0 fingers: all fingers lifted → fire the right-click
    (`sendPointer(x, y, BUTTON_RIGHT)` + `sendPointer(x, y,
    BUTTON_NONE)`) at the last known position.
  - 2 → 0 fingers: all fingers lifted → fire `button up` to
    release the left button.
  - 1 → 0 fingers: a single-finger tap. No event (a trackpad
    single-tap is not a click).

The detector is **stateless across gestures**: every press
starts a new state-machine run. This avoids "stuck button" bugs
when a gesture is interrupted by an external event.

### 2. Coordinate mapping

Touch events arrive in the rendered Compose View's pixel
space; the VNC server's framebuffer is in the server's pixel
space (e.g. 1920×1080). The handler captures the rendered
size via `onSizeChanged` and scales the touch coordinates to
the server's space with explicit clamping to
`[0, serverWidth - 1] × [0, serverHeight - 1]`. The
`pointerInput` is keyed on `(renderedSize, serverWidth,
serverHeight)` so the gesture scope restarts when any of them
change.

A defensive guard skips pointer events when the rendered size
is `0 × 0` (before first layout). Without this guard, the
first touch would dispatch a `sendPointer(0, 0, mask)`
because the scale-to-server math collapses to 0.

### 3. The live indicator

A small floating chip ("1F MOVE" / "2F DRAG" / "3F CLICK")
that mirrors the gesture state so the user can see what the
detector is currently interpreting. The chip fades in on
the first non-idle state and fades out when the gesture
ends (`AnimatedVisibility` + `fadeIn` / `fadeOut`, 120-220ms
tween).

### 4. Toolbar integration

The `LiveDesktopWorkspace` now has:
- The `trackpadGestures` modifier on the wrapping Box (so
  events are captured before they reach the
  [RfbSurfaceView]).
- A floating chip below the toolbar that shows the
  current trackpad state.
- The same back / status / disconnect buttons as before
  (unchanged).

### 5. State preservation

- The same `LinuxDesktopViewModel` powers the new gestures
  (no behavior change).
- The same `RfbSession` is the dispatch target.
- The same `RfbHost` renders the VNC stream.
- The trackpad is a pure addition on top of the
  Phase 74 surface.

## Build / test status

- `compileDebugKotlin` — green.
- `assembleDebug` — green.
- `testDebugUnitTest` — **all 2582 unit tests green, 0 failures**
  (the trackpad is a UI modifier; no new JVM tests are added
  — the JVM suite asserts the dispatch helpers + the data
  layer, not the visual gesture state).
- 0 new lint warnings.
- Install on the user's device — verified.

## Files

- `app/src/main/java/com/elysium/vanguard/features/runtime/desktop/TrackpadGestures.kt` (NEW)
- `app/src/main/java/com/elysium/vanguard/features/runtime/desktop/LinuxDesktopScreen.kt` (UPDATED: `LiveDesktopWorkspace` now wires the trackpad)

## Notes for follow-ups

- **Right-click coordinates**: the right-click is fired at
  the **last known position** (the position of the most
  recent move event). On a trackpad this matches macOS /
  Windows behavior (the right-click appears at the cursor's
  last-known position, not the centroid of the three
  fingers). A future variant could fire at the centroid
  for a different feel.
- **Scroll wheel**: a 2-finger vertical drag is the
  trackpad's scroll gesture. Phase 75 doesn't translate
  vertical drag to a scroll wheel event; a follow-up
  phase adds `sendWheel(deltaY)` (RFB supports wheel
  events natively).
- **Three-finger swipe**: macOS uses 3-finger swipes for
  app switching. A future phase could add
  `sendKey(KEY_LEFTMETA, down)` + `sendKey(KEY_LEFT, down)`
  pairs for "back" / "forward" gestures.
- **Pinch**: a two-finger pinch is not implemented.
  The RFB protocol supports it via pointer-button-2 +
  button-3, but the current detector treats any 2-finger
  gesture as a left-drag. A future phase adds a
  `Modifier.transformable` overlay for pinch-to-zoom in
  supported apps (image viewers, file managers).
