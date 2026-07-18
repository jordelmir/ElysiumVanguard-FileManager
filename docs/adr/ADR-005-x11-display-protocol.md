# ADR-005: X11 display protocol and remote-framebuffer bridge

- Status: Accepted
- Date: 2026-07-13
- Owners: Elysium Vanguard terminal/runtime
- Depends on: ADR-001

## Context

Linux desktop applications require an X11 or Wayland display server. On Android
there is no native display server. The app must provide a software framebuffer
that guest X11 applications can render into, and forward input events from the
Android touch/keyboard layer back to the guest.

The X11DisplayService currently manages a VNC server lifecycle inside the
rootfs. The app connects to this VNC server over a local TCP socket and renders
the framebuffer onto an Android SurfaceView.

## Decision

Adopt a Remote Framebuffer Protocol (RFP) architecture where:

### Display server

- A VNC server (TigerVNC or libvncserver) runs inside the rootfs, configured
  to listen on `127.0.0.1:5900`.
- The VNC server is started as part of the X11 desktop session (XFCE, LXQt or
  standalone window manager).
- The desktop environment uses the VNC server's X11 display (`:0`).

### Client bridge

- `RfbSurfaceView` is an Android `SurfaceView` that connects to the VNC server
  over TCP.
- It implements the RFB protocol (version 3.8) sufficient for framebuffer
  updates: `SetPixelFormat`, `SetEncodings` (preferring raw/hextile/zlib),
  `FramebufferUpdateRequest` with incremental support.
- Framebuffer updates are decoded into an `IntArray` (ARGB_8888) and drawn via
  `Canvas.drawBitmap()`.
- Dirty rectangles are coalesced per frame. Only changed regions are repainted.

### Input forwarding

- Touch events on `RfbSurfaceView` are translated to RFB `PointerEvent`
  (button mask + x/y).
- Hardware keyboard events are forwarded as RFB `KeyEvent` using X11 keysym
  mapping.
- Touch-to-mouse mapping: single-finger touch generates a left-click at the
  touch position. Long-press generates right-click. Two-finger scroll generates
  mouse-wheel events.
- The `DisplayService` owns the mapping between device coordinates and
  framebuffer coordinates.

### State machine

The `DisplayService` manages a sealed state machine:

```
Disconnected → Connecting → Connected(Authenticating) → Connected(Initialized) → Connected(Running)
                                                                                       ↓
                                                                              Disconnecting → Disconnected
```

Each state transition is validated. Only `Disconnected` allows `connect()`.
Only `Connected(Running)` allows `sendPointer()` and `sendKey()`.

## Invariants

1. The VNC server must be verified running before the RFB client connects.
2. Reconnect uses exponential backoff (1s, 2s, 4s, max 30s).
3. Framebuffer updates are throttled to the display refresh rate.
4. The connection is localhost-only; no remote VNC exposure.
5. Session destruction closes the VNC connection and signals the guest to stop.

## Alternatives considered

### Wayland

Rejected. Wayland requires a compositor inside the rootfs, increasing the guest
footprint and complexity. VNC provides a display-server-agnostic protocol that
works with both X11 and Wayland (via wayland-vnc or similar).

### Direct framebuffer device (/dev/fb0)

Rejected. /dev/fb0 is not available without kernel drivers. VNC is a
cross-platform framebuffer transport that works with standard Linux packages.

## Consequences

- All desktop rendering goes through the VNC→RFB pipeline.
- Input latency depends on the VNC server's polling interval and network
  round-trip (even over loopback).
- The RFB protocol implementation must handle all encoding types supported by
  the server.
- Rotation requires re-encoding the framebuffer to the new resolution.

## Revisit triggers

- RFB performance on loopback exceeds 50ms per frame.
- Wayland becomes the default for all target distros and VNC server quality
  degrades.
- Android Virtualization Framework provides a native display solution.
