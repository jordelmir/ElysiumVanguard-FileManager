# ADR-013: Remote runtime backend

- Status: Draft
- Date: 2026-07-13
- Owners: Elysium Vanguard runtime
- Depends on: ADR-001

## Context

The Universal Computing Fabric should not be limited to local execution. Users
may have powerful desktop machines, home servers or cloud instances where they
want to run applications that cannot run on the mobile device (x86-64-only
binaries, GPU-accelerated workloads, large data processing). The same capsule
and session abstractions should work locally and remotely with minimal UX
differences.

## Decision

Implement a RemoteRuntimeBackend that bridges the app's runtime domain to a
remote execution endpoint:

### Connection model

The remote backend supports three connection types:

| Type | Protocol | Use case |
|---|---|---|
| SSH | SSH v2 | Self-managed servers, headless machines |
| Tunnel | WebSocket + TLS | Cloud relays, NAT traversal |
| Direct | TCP + TLS | Dedicated Elysium Vanguard server |

### Session lifecycle

1. The backend connects to the remote endpoint and authenticates.
2. It creates a remote session on the server matching the SessionSpec.
3. The server reports its capabilities (architecture, GPU, memory, available
   runtimes).
4. Input/output streams are tunneled over the secure connection.
5. Display output uses VNC over the tunnel (see ADR-005).

### Capability mapping

The remote backend reports capabilities based on the server's probe, not the
local device. A remote server with an NVIDIA GPU reports GPU capability even
if the local device has no GPU. The UI shows which capabilities are local,
remote or both.

### Capsule deployment

- Capsules can be deployed to a remote server using the FilesystemBridge over
  the tunnel.
- The server verifies capsule integrity before execution.
- Capsule results (output files, modified data) can be retrieved on session
  stop.

### Security

- All connections use TLS 1.3 (or SSH v2 with key-based auth).
- Client certificates are stored in Android Keystore.
- Host keys are verified on first connection and pinned on subsequent
  connections.
- No passwords are stored; SSH keys and client certificates only.

### Offline behavior

- Remote sessions cannot start without connectivity.
- Active remote sessions survive brief network interruptions (30s timeout).
- Longer interruptions terminate the session and report a network error.
- Sessions that were running remotely are shown as "disconnected" in the UI
  with a reconnect option.

## Invariants

1. Every remote connection is authenticated and encrypted.
2. Host key verification is mandatory and cannot be bypassed in production.
3. Remote sessions report network latency as part of diagnostics.
4. Capsule deployment is atomic: all files or none.
5. No user credentials are stored in plaintext.

## Alternatives considered

### Local-first only

Rejected. Remote execution is a requirement of the Universal Computing Fabric
specification, phase 13.

### Only SSH

Rejected. SSH may not be available in all environments (corporate networks,
cloud services). WebSocket tunnel and direct TCP provide alternatives.

## Consequences

- Remote execution introduces network latency to all operations.
- Security configuration (certificates, host keys) adds complexity.
- The same capsule format works locally and remotely.
- Remote capabilities expand the device's effective capability set.
- Network connectivity becomes a hard dependency for remote sessions.

## Revisit triggers

- SSH library does not support required key types or ciphers.
- Tunnel latency exceeds 500ms RTT on reference network.
- Users request WebRTC-based display streaming for lower latency.
- A managed cloud service for capsule hosting is developed.
