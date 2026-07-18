package com.elysium.vanguard.core.runtime.observability

/**
 * Phase 25 — every event the runtime can emit.
 *
 * The runtime is a multi-component system: a network
 * broker, a hardware broker, a workspace manager, a
 * Windows VM manager, a distro manager. Each emits
 * events as state changes happen. The [RuntimeEventBus]
 * is the seam that fans the events out to consumers
 * (UI, observability dashboard, crash reporter, audit
 * log).
 *
 * The events are a sealed class so the runtime's UI can
 * `when` over them without a default branch. A new event
 * type is a deliberate code change.
 *
 * Every event carries a [workspaceId] (nullable — system-
 * level events have no workspace) and an [atMs] (the
 * timestamp the event was emitted, set by the producer
 * via [System.currentTimeMillis] in production; tests
 * pass an explicit clock for determinism).
 */
sealed class RuntimeEvent {
    abstract val atMs: Long
    abstract val workspaceId: String?

    // --- Network (Phase 13) ---

    data class NetworkDecisionEvent(
        override val atMs: Long,
        override val workspaceId: String?,
        val sessionId: String,
        val dest: String,
        val port: Int,
        val decision: String  // "Allow" | "AllowWithConfirmation" | "Deny"
    ) : RuntimeEvent()

    // --- Hardware (Phase 19) ---

    data class HardwareDecisionEvent(
        override val atMs: Long,
        override val workspaceId: String?,
        val sessionId: String,
        val hardwareClass: String,
        val action: String,
        val decision: String  // "Granted" | "PendingConsent" | "Denied" | "Error"
    ) : RuntimeEvent()

    // --- Workspace (Phase 24) ---

    data class WorkspaceStateChangedEvent(
        override val atMs: Long,
        override val workspaceId: String,
        val fromState: String,
        val toState: String
    ) : RuntimeEvent()

    data class SessionAddedEvent(
        override val atMs: Long,
        override val workspaceId: String,
        val sessionId: String,
        val sessionKind: String
    ) : RuntimeEvent()

    data class SessionRemovedEvent(
        override val atMs: Long,
        override val workspaceId: String,
        val sessionId: String
    ) : RuntimeEvent()

    // --- Session runner (Phase 30) ---

    data class SessionStartedEvent(
        override val atMs: Long,
        override val workspaceId: String,
        val sessionId: String,
        val kind: String,
        val launcherKind: String?,
        val pid: Int
    ) : RuntimeEvent()

    data class SessionStoppedEvent(
        override val atMs: Long,
        override val workspaceId: String,
        val sessionId: String,
        val exitCode: Int
    ) : RuntimeEvent()

    data class SessionStartFailedEvent(
        override val atMs: Long,
        override val workspaceId: String,
        val sessionId: String,
        val kind: String,
        val error: String
    ) : RuntimeEvent()

    // --- Windows VM (Phase 22) ---

    data class VmStateChangedEvent(
        override val atMs: Long,
        override val workspaceId: String?,
        val vmId: String,
        val fromState: String,
        val toState: String
    ) : RuntimeEvent()

    // --- Distro (Phase 17) ---

    data class DistroInstalledEvent(
        override val atMs: Long,
        override val workspaceId: String?,
        val distroId: String,
        val profileId: String,
        val elapsedMs: Long
    ) : RuntimeEvent()

    data class DistroInstallFailedEvent(
        override val atMs: Long,
        override val workspaceId: String?,
        val distroId: String,
        val error: String
    ) : RuntimeEvent()

    // --- Snapshots (Phase 49) ---

    data class SnapshotCreatedEvent(
        override val atMs: Long,
        override val workspaceId: String,
        val snapshotId: String,
        val label: String,
        val copyStrategy: String  // "HARDLINK" | "FULL_COPY"
    ) : RuntimeEvent()

    data class SnapshotRestoredEvent(
        override val atMs: Long,
        override val workspaceId: String,
        val snapshotId: String,
        val label: String
    ) : RuntimeEvent()

    data class SnapshotDeletedEvent(
        override val atMs: Long,
        override val workspaceId: String,
        val snapshotId: String
    ) : RuntimeEvent()
}
