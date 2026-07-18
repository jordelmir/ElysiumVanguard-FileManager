# Elysium Vanguard — Universal Computing Fabric Audit

**Date:** 2026-07-13
**Branch:** `main` (authorized)
**Baseline commit:** `5bc7c1e26ca182af8e224fedd347c74f9a30e806`
**Post-fix commit:** working tree (uncommitted)

---

## 1. Repository Architecture Summary

| Metric | Value |
|---|---|
| Gradle modules | 1 (`:app`) |
| Kotlin source files | 372 |
| C/H source files | 2 (spawn_shim) |
| Rust source files | 2 (lib.rs, build.rs) |
| Unit tests (JVM) | 846 executed, 0 failures |
| Android lint errors | 0 |
| Android lint warnings | 68 |
| Debug APK size | 112.9 MB |
| Native PTY | Real POSIX PTY (Rust JNI + C fork/exec) |
| VT Parser | Full VT100/VT220/xterm (521 lines) |
| Terminal Renderer | Canvas-based SurfaceView (263 lines) |
| Terminal Buffer | Main/alternate screen + scrollback (646 lines) |
| Session Manager | Multi-session with foreground service |
| DNS Provider | Android ConnectivityManager + fallback |
| PRoot Binary | Bundled arm64-v8a PIE |

---

## 2. What Already Exists (Verified)

### 2.1. Real PTY — NOT a pipe-based terminal

The codebase has a **complete native PTY implementation**:

- `native/runtime/src/spawn_shim.c` (199 lines): POSIX PTY spawn via `posix_openpt`, `grantpt`, `unlockpt`, `ptsname_r`, `fork`, `setsid`, `TIOCSCTTY`, `dup2`, `execve`, `TIOCSWINSZ`, `O_NONBLOCK`.
- `native/runtime/src/lib.rs` (703 lines): Rust JNI bridge owning master FD, child PID, process group, non-blocking I/O via `poll()`, exit status via `waitpid(WNOHANG)`, signal delivery via `kill(-pid, signal)`.
- `NativePty.kt` (161 lines): Kotlin facade with typed operations (`spawn`, `read`, `write`, `resize`, `signal`, `waitForExit`, `close`).
- `TerminalSession.kt` (375 lines): Session lifecycle with structured coroutine scope, pump coroutine, and proper cleanup.

**No `ProcessBuilder` is used for terminal sessions.** All `ProcessBuilder` references in the codebase are in KDoc comments only.

### 2.2. VT Parser — Comprehensive

`TerminalParser.kt` (521 lines) implements:
- C0 controls: NUL, BEL, BS, HT, LF, VT, FF, CR, SO, SI
- CSI: cursor moves, SGR (16/256/truecolor), erase ops, insert/delete line/char
- OSC: title changes (0/1/2)
- DECSC/DECRC, DEC 1048/1049
- Alternate screen buffer (47/1047/1049)
- Bracketed paste (2004)
- Device attributes (DA1, DA2), DSR/DECXCPR
- Scroll regions (DECSTBM)
- Incremental UTF-8 decoding with surrogate pair handling

### 2.3. Terminal Renderer — Canvas-based

`TerminalRenderer.kt` (263 lines) draws on Android `Canvas` via `SurfaceView`:
- Batched same-attribute cell rendering
- 256-color and truecolor palette
- Bold/dim/italic/underline/inverse/hidden attributes
- Cursor block rendering
- Dirty row tracking (no full repaint per byte)

### 2.4. Terminal Buffer — Complete

`TerminalBuffer.kt` (646 lines):
- Primary and alternate screens
- Bounded scrollback (1000 lines default)
- Scroll regions (DECSTBM)
- Insert/delete lines/chars
- Resize with reflow
- Wide character and combining mark support
- Dirty row accumulator consumed by renderer

### 2.5. DNS — Dynamic with Fallback

`AndroidGuestDnsConfigProvider.kt`:
- Reads DNS from `ConnectivityManager.activeNetwork.linkProperties.dnsServers`
- Falls back to well-known public DNS (Cloudflare 1.1.1.1, Google 8.8.8.8 + IPv6) when Android returns 0 nameservers
- Registers `NetworkCallback` for live updates on network changes
- Exposes `addListener` for session-creation-time updates
- `GuestDnsConfig.renderResolvConf()` generates valid `resolv.conf`

### 2.6. PRoot Integration

`NativeProotLauncher.kt` (222 lines):
- Builds complete proot command line with bind mounts
- DNS bind-mounts to `/etc/resolv.conf`, `/run/systemd/resolve/*`, `/run/NetworkManager/*`
- Atomic `resolv.conf` generation via staging + rename
- Stale DNS file cleanup

### 2.7. Session Lifecycle

- `TerminalSessionManager`: Application-scoped, survives Activity changes
- `TerminalService`: Foreground service with notification and stop action
- `SessionStateMachine`: Validated state transitions (Created→Validating→Preparing→Starting→Running→Stopping→Stopped)
- `ExitReport`: Structured exit information with process group cleanup

---

## 3. Critical Issues Found and Fixed

### 3.1. DNS Fallback (CRITICAL — Fixed)

**Problem:** `AndroidGuestDnsConfigProvider` returned `GuestDnsConfig.EMPTY` when Android provided no nameservers (common on first boot, Wi-Fi/mobile transitions, VPN toggle). This caused `Temporary failure resolving deb.debian.org` in every PRoot session.

**Root cause:** No fallback DNS when system returns empty list.

**Fix:** Added well-known public DNS fallback (Cloudflare 1.1.1.1/2606:4700:4700::1111, Google 8.8.8.8/2001:4860:4860::8888). Added `NetworkCallback` for live DNS updates. Added `source` field to `GuestDnsConfig` for diagnostic tracking.

**Files changed:**
- `AndroidGuestDnsConfigProvider.kt` — Complete rewrite with fallback + callback
- `GuestDnsConfig.kt` — Added `source` field, updated `renderResolvConf()`

### 3.2. runBlocking in Production (HIGH — Fixed)

**Problem:** 3 instances of `runBlocking` in production code:
1. `LocalFileServer.kt:157` — blocked caller during `stop()`
2. `LocalFileServer.kt:235` — blocked calling thread for every HTTP request dispatch
3. `LocalServerOrchestrator.kt:246` — blocked inside streaming download lambda

**Fix:**
1. `stop()`: Replaced `runBlocking { cancel() }` with direct `cancel()` (accept loop checks `isActive`)
2. `dispatch()`: Made `dispatch` a `suspend` function called from the existing coroutine context
3. `streamBody`: Changed from `(OutputStream) -> Unit` to `suspend (OutputStream) -> Unit`
4. `writeResponse`: Made `suspend` to propagate the streaming capability

**Files changed:**
- `LocalFileServer.kt` — Removed all `runBlocking`, made `dispatch`/`writeResponse` suspend
- `HttpResponse.kt` — Made `streamBody` a `suspend` lambda
- `LocalServerOrchestrator.kt` — Removed `runBlocking` from streaming handler

### 3.3. Terminal Cell Metrics (MEDIUM — Fixed)

**Problem:** `TerminalSurfaceView` used hardcoded `cellWidthPx = cellHeightPx * 0.6f` for cell width. This caused wrapping bugs when the actual monospace glyph width differed from the assumed 0.6 ratio (varies by font, density, fontScale).

**Fix:** Measure actual monospace glyph width using `Paint.measureText("M")` instead of assuming aspect ratio.

**Files changed:**
- `TerminalSurfaceView.kt` — `recomputeMetrics()` now uses measured glyph width

---

## 4. Remaining Issues (Prioritized)

### 4.1. Non-null Assertions (24 in production)

Key locations:
- `TerminalSurfaceView.kt:121` — `renderer!!.draw(canvas, buffer)` — could crash if renderer is null during surface transition
- `TerminalService.kt:51` — `foregroundSessionId!!` — could crash if activeIds is empty when foregroundSessionId is checked
- `TerminalBuffer.kt:209` — `alternateScreen!!` — guarded by `activeScreen !== primaryScreen` check

**Risk:** Crash on edge cases (surface destroyed during draw, rapid session changes).

### 4.2. Generic Catch Blocks (51 in production)

Files with most generic catches:
- `IntegratedDocumentViewer.kt` (7)
- `FileManagerRepository.kt` (5)
- `VaultViewModel.kt` (5)
- `FileOpenerUtil.kt` (5)
- `ConversionEngine.kt` (4)
- `TransferService.kt` (4)

**Risk:** Hides specific error types, makes debugging difficult.

### 4.3. Hardcoded Paths (2 in production)

- `FileManagerScreen.kt:1164` — `/data/data/com.termux/files/home` — Termux quick-link (intentional)
- `ProotNativeLibrary.kt:131` — `/data/data/com.termux/files/usr/bin/proot` — Termux fallback (intentional)

**Risk:** Low — these are fallback paths for Termux integration.

### 4.4. No CI/CD Pipeline

No `.github/workflows/`, `Jenkinsfile`, or `.gitlab-ci.yml` found. Builds are manual.

### 4.5. Missing Top-Level LICENSE File

The repository has no top-level `LICENSE` file. Third-party licenses are documented in `docs/legal/` but the project's own license is undefined.

---

## 5. Architecture Assessment

### 5.1. Strengths

1. **Clean separation**: Terminal engine, renderer, buffer, session, and UI are properly separated.
2. **Real PTY**: Not a pipe hack — full POSIX PTY with process group management.
3. **Comprehensive VT parser**: Handles the sequences needed for vim, htop, tmux, less.
4. **Structured domain**: `RuntimeContracts.kt` defines proper domain model with typed errors.
5. **Session state machine**: Validated transitions prevent inconsistent states.
6. **Foreground service**: Proper Android lifecycle integration.
7. **DNS integration**: Dynamic with fallback and live updates.

### 5.2. Technical Debt

1. **Single Gradle module**: 372 Kotlin files in `:app` — should be modularized.
2. **No multi-module architecture**: Core, features, and native should be separate modules.
3. **51 generic catches**: Error handling needs systematic improvement.
4. **24 non-null assertions**: Crash risk on edge cases.
5. **No dependency injection for terminal components**: `TerminalSession` creates its own `TerminalParser`.

### 5.3. What's Missing for Universal Computing Fabric

| Component | Status | Effort |
|---|---|---|
| Native PTY | ✅ Complete | — |
| VT Parser | ✅ Complete | — |
| Terminal Renderer | ✅ Complete | — |
| DNS Integration | ✅ Fixed | — |
| PRoot Backend | ✅ Complete | — |
| Rootfs Manager | ✅ Complete | — |
| VNC/RFB Client | ✅ Complete | — |
| Filesystem Bridge | ✅ Complete | — |
| X11 Display Server | ❌ Not started | High |
| WinLayer (Wine+Box64) | ❌ Not started | Very High |
| Windows VM | ❌ Not started | Very High |
| Hardware Broker | ❌ Not started | High |
| Application Capsules | ❌ Not started | Medium |
| Workspaces | ❌ Not started | Medium |
| Clipboard Broker | ❌ Not started | Medium |
| Audio Broker | ❌ Not started | Medium |
| AI Operator | ⚠️ Partial (tool executor) | Medium |

---

## 6. Verification Evidence

| Gate | Result |
|---|---|
| Build | `BUILD SUCCESSFUL` |
| Unit tests | 846 executed, 0 failures, 0 errors |
| Lint errors | 0 |
| Lint warnings | 68 (all pre-existing) |
| `runBlocking` in production | 0 (was 3) |
| `GlobalScope` | 0 |
| `chmod 777` | 0 |
| `Runtime.exec` | 0 |

---

## 7. Recommended Next Steps

### Phase 1 (Immediate)
1. ~~Fix DNS fallback~~ ✅ Done
2. ~~Remove runBlocking~~ ✅ Done
3. ~~Fix terminal cell metrics~~ ✅ Done
4. Add `performClick` to `TerminalSurfaceView` for accessibility
5. Add unit tests for DNS fallback behavior
6. Add unit tests for the network callback integration

### Phase 2 (Next Sprint)
1. Modularize Gradle into `:core:terminal`, `:core:runtime`, `:core:network`, `:feature:terminal`
2. Replace generic catches with typed exceptions in terminal subsystem
3. Add CI/CD pipeline (GitHub Actions)
4. Add top-level LICENSE file
5. Implement X11 display server integration

### Phase 3 (Future)
1. Application Capsule format and launcher
2. Workspace reproducible environments
3. Clipboard broker with policies
4. Audio broker
5. Hardware broker for USB/BT/serial

---

## 8. Files Modified in This Audit

| File | Change | Lines |
|---|---|---|
| `AndroidGuestDnsConfigProvider.kt` | DNS fallback + NetworkCallback | +130 -8 |
| `GuestDnsConfig.kt` | Added `source` field | +6 -3 |
| `LocalFileServer.kt` | Removed runBlocking, suspend dispatch | +4 -6 |
| `HttpResponse.kt` | Made streamBody suspend | +1 -1 |
| `LocalServerOrchestrator.kt` | Removed runBlocking | +1 -1 |
| `TerminalSurfaceView.kt` | Measured glyph width | +8 -3 |

**Net change:** ~150 lines added, ~20 lines removed.
