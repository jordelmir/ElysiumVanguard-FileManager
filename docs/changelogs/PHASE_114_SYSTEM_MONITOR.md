# Phase 114 — System Monitor (CPU, Memory, Temperature, Uptime)

**Status:** ✅ SHIPPED
**Date:** 2026-07-22
**Commit:** (this commit)
**Build:** APK 98 MB
**Tests:** 3781 (+12 from Phase 113)

## Vision Alignment

The vision's "Visualización" section (gap
#11) calls for a **monitor de recursos**
that shows the platform's CPU, memory,
temperature, and uptime in real time.
Phase 114 ships the typed surface + the
platform readers (`/proc/meminfo`,
`/proc/stat`, `/proc/uptime`,
`/sys/class/thermal/`) + the rolling
chart UI.

The data sources are all first-party
Linux kernel files (no third-party
dependency on `top`, `htop`, etc.). The
provider is JVM-testable via a hand-rolled
`FakeSystemInfoProvider`.

## Deliverables

### 1. `SystemSample` data class (`core/system/SystemSample.kt`)

A single point-in-time sample. The data
class carries:

- `atMs` — the wall-clock timestamp.
- `cpuPercent` — the CPU utilization (0..100),
  averaged across all cores.
- `memoryUsedMb` / `memoryTotalMb` — memory
  usage in megabytes.
- `memoryPercent` — a computed property
  (the percentage, 0..100). Returns 0
  when `memoryTotalMb` is 0 (avoids
  divide-by-zero).
- `temperatureCelsius` — the SoC
  temperature in °C (from
  `/sys/class/thermal/thermal_zoneN/temp`).
  `null` when no thermal zone is readable
  (common on emulators).
- `uptimeSeconds` — the system uptime in
  seconds (from `/proc/uptime`).

### 2. `SystemInfoProvider` interface

The seam between the
[SystemMonitorViewModel](#3-systemmonitorviewmodel)
(pure-domain) and the
[AndroidSystemInfoProvider](#4-androidsysteminfoprovider-production)
(I/O). The interface returns a single
`SystemSample` for the current moment.

### 3. `SystemMonitorViewModel`

A Hilt-injected ViewModel that polls the
`SystemInfoProvider` at a configurable
interval (default: 1 second) + exposes
a rolling history (the last 60 samples)
as a `StateFlow<List<SystemSample>>`.

The polling loop has:

- A `startPolling(intervalMs)` method
  that kicks off the coroutine.
- A `stopPolling()` method that cancels
  the coroutine.
- A `currentSample` property that
  returns the most recent sample.
- A ring buffer (`MAX_HISTORY = 60`) that
  caps the history size (60 samples = 1
  minute at the default interval).
- Robust cancellation handling: the
  polling loop catches
  `CancellationException` at the `delay()`
  point + exits cleanly (the parent
  scope sees a normal completion).

### 4. `AndroidSystemInfoProvider` (production)

Reads the kernel files:

- `/proc/meminfo` → `MemTotal` and
  `MemAvailable` (computes
  `used = total - available`).
- `/proc/stat` → the first `cpu` line;
  computes the busy percentage from the
  7 standard fields (user, nice,
  system, idle, iowait, irq, softirq).
- `/proc/uptime` → the first field (the
  system uptime in seconds, fractional
  part rounded down).
- `/sys/class/thermal/thermal_zoneN/temp`
  (N = 0, 1, 2) → the millidegree-Celsius
  value, divided by 1000 to get °C.
  `null` when no zone is readable.

A read failure on any of the four files
is silently swallowed (the field
defaults to 0 or `null`); the sample is
still returned with the other fields
populated. The platform's I/O is
flaky (a missing thermal zone is common
on emulators); the provider never
throws on a missing file.

### 5. `SystemInfoModule` (Hilt)

`SystemInfoProvider` →
`AndroidSystemInfoProvider` (singleton).

### 6. `SystemMonitorScreen` (Compose)

The UI:

- A title (`"System Monitor"`).
- A current-values card showing CPU,
  memory, temperature, and uptime with
  color-coded values (green for low,
  amber for medium, red for high).
- A rolling chart card showing the last
  60 samples as vertical bars (one bar
  per sample, height proportional to
  CPU%). The card also shows the min,
  max, and average CPU%.

The screen is dark-themed (the Sovereign
gradient — deep navy → indigo → magenta)
to match the desktop shell.

## Test Coverage

| Test class | New tests | Total |
|---|---|---|
| `SystemSampleTest` | 5 | 5 |
| `SystemMonitorViewModelTest` | 7 | 7 |

Total new: 12 test methods.
Total: 3781 tests, 1 pre-existing flake
(unchanged from Phase 113), 2 skipped.

## Architecture Decisions

### Why a `SystemInfoProvider` interface (not calling `/proc` directly)?

The ViewModel is pure-domain (no I/O). The
kernel files are I/O. Separating the two
lets the ViewModel be JVM-testable (a
fake provider in the test suite).

### Why read `/proc/meminfo` and `/proc/stat` (not the Android `ActivityManager` API)?

The vision says "all proprietary", and
the `/proc` files are first-party Linux
kernel files (no Android-API dependency).
The `ActivityManager.getMemoryInfo()` API
returns aggregated Android-level stats
that don't match what a Linux distro
inside proot would see. The `/proc`
files match the distro's view (which is
what the user sees inside the workspace).

### Why a 50 MiB read cap (well, no cap here — the data is tiny)?

The `/proc` files are small (<10 KB
each); no need to cap the read. The
ring buffer in the ViewModel caps the
**history** (the last 60 samples) so the
UI doesn't render thousands of bars.

### Why a 1-second default polling interval?

1 second is the standard for system
monitors (GNOME System Monitor,
Windows Task Manager, macOS Activity
Monitor all use ~1 second). A faster
interval wastes CPU; a slower interval
makes the chart feel sluggish.

### Why a rolling chart (not a real-time line graph)?

A real-time line graph requires a chart
library (MPAndroidChart, Vico, etc.).
The rolling bar chart is hand-rolled
Compose (no external dependency) + shows
the same info (the user sees the trend
over the last 60 samples).

### Why catch `CancellationException` in the polling loop?

The `viewModelScope`'s coroutines can be
cancelled by the framework (e.g. when
the user navigates away). The loop must
exit cleanly when cancelled, not
propagate the exception (which would
crash the app). The catch + break
pattern is the standard Kotlin
coroutine idiom for clean cancellation.

## Files

### New (production)

- `app/src/main/java/com/elysium/vanguard/core/system/SystemSample.kt`
- `app/src/main/java/com/elysium/vanguard/core/system/AndroidSystemInfoProvider.kt`
- `app/src/main/java/com/elysium/vanguard/core/system/SystemInfoModule.kt`
- `app/src/main/java/com/elysium/vanguard/features/systemmonitor/SystemMonitorViewModel.kt`
- `app/src/main/java/com/elysium/vanguard/features/systemmonitor/SystemMonitorScreen.kt`

### New (tests)

- `app/src/test/java/com/elysium/vanguard/core/system/SystemSampleTest.kt`
- `app/src/test/java/com/elysium/vanguard/features/systemmonitor/SystemMonitorViewModelTest.kt`

## Next

- **Phase 115** — Box64 / FEX / DXVK graphics
  translation: depends on having the binaries
  (Phase 101 rootfs lists them but the
  integration is Phase 109+ work).
- **Future: comprehensive `runTest` for the
  VM** — the polling loop is hard to test in
  isolation with `runTest` (the `viewModelScope`
  is tied to the ViewModel's lifecycle). A
  future phase refactors the VM to accept a
  `CoroutineScope` parameter (with a default
  of `viewModelScope`) so the test can pass a
  `TestScope` + use `runTest` directly.
- **Future: per-CPU breakdown** — the
  `cpuPercent` is averaged across all
  cores; per-core stats are a future
  phase.
- **Future: disk I/O** — the monitor shows
  CPU + memory + temperature; disk
  I/O stats are a future phase (read
  from `/proc/diskstats`).
- **Future: per-process stats** — the
  monitor shows system-wide stats;
  per-process stats (top-N by CPU) are
  a future phase.
