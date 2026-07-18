# ADR-012: Observability and diagnostics

- Status: Draft
- Date: 2026-07-13
- Owners: Elysium Vanguard runtime

## Context

The app manages multiple concurrent runtimes (terminals, distros, display
servers, VMs, remote services, hardware brokers). When a session fails, the
user and developer need to understand why without connecting a debugger or
reading raw logcat output. The runtime backend abstraction provides typed
events, but there is no structured observability layer for collecting,
persisting and presenting diagnostic information.

## Decision

Implement a three-pillar observability layer:

### Pillar 1 — Structured event bus

A runtime event bus emits typed events for every state transition, error and
diagnostic signal:

```kotlin
sealed class RuntimeEvent {
    data class SessionTransition(from: SessionState, to: SessionState, sessionId: SessionId) : RuntimeEvent()
    data class Error(sessionId: SessionId, code: RuntimeErrorCode, message: String, cause: Throwable?) : RuntimeEvent()
    data class Diagnostic(sessionId: SessionId, severity: DiagnosticSeverity, message: String, data: Map<String, String>) : RuntimeEvent()
    data class ResourceUsage(sessionId: SessionId, cpuPercent: Double, memoryKb: Long, uptimeMs: Long) : RuntimeEvent()
}
```

The event bus is an in-process `SharedFlow`. Components subscribe to relevant
event types. Events are never emitted from a constructor or init block.

### Pillar 2 — Session diagnostic buffer

Every `RuntimeSession` maintains a circular diagnostic buffer:
- Capacity: 1024 entries per session.
- Each entry: timestamp, severity (DEBUG/INFO/WARN/ERROR/FATAL), message,
  optional structured data.
- The buffer is exposed through the session's diagnostic API and is available
  in the session detail UI.
- On session stop, the buffer may be persisted to Room for post-mortem
  analysis.

### Pillar 3 — Health check and watchdog

- Every backend reports a health status on demand: `SessionHealth(healthy: Boolean, lastHeartbeat: Instant, details: String)`.
- A watchdog coroutine pings each session every 15 seconds.
- If a session does not respond within 30 seconds, it is force-stopped and a
  FATAL diagnostic is recorded.
- Health check history is retained for the last 24 hours.

### Developer diagnostics

A developer mode (enabled via 7 taps on the build number in Settings) provides:
- Live event stream in a scrollable overlay.
- Session diagnostic buffer viewer with copy-to-clipboard.
- Resource usage graphs (CPU, memory, FD count) per session.
- Capability probe results with raw probe output.

### Privacy

Diagnostics never contain:
- User credentials, tokens or session input.
- File contents or paths outside the app's data directory.
- Network packet payloads.
- Location data.

Users can opt out of diagnostic collection in Settings.

## Invariants

1. Every error produces at least one RuntimeEvent.
2. The diagnostic buffer is bounded per session.
3. Developer mode is off by default and opt-in.
4. Health check does not block on unresponsive sessions (timeout-based).
5. Persistent diagnostics are cleaned up after 7 days.

## Alternatives considered

### logcat only

Rejected. logcat is global, not scoped to sessions, and is not available to
users without ADB. Structured events enable UI integration.

### Export all events to a remote server

Rejected for v1. Remote export may be added later as an opt-in feature for
beta testers. Local diagnostics are sufficient for debugging and support.

## Consequences

- Event bus adds a small memory overhead per event type.
- Diagnostic buffer requires `1024 × avgEntrySize` per session (~100 KB).
- Health check pings add negligible CPU overhead.
- Developer mode overlay may impact UI performance.
- The event bus interface enables future remote monitoring and alerting.

## Revisit triggers

- Event bus becomes a bottleneck with >20 concurrent sessions.
- Diagnostic storage exceeds 50 MB on typical devices.
- Users request remote diagnostic export for support.
