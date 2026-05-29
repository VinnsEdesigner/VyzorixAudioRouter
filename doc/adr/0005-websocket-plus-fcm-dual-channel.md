# ADR-0005: WebSocket + FCM dual-channel instead of either alone

## Status
Accepted

## Context

Commands flow from a dashboard (on the operator's phone or laptop) to a device. The device must be reachable in two operating modes:

- **Active** — daemon is running, foreground service is up, network is available. The dashboard expects sub-second command latency.
- **Dormant** — daemon may have been killed by the OS during Doze, the device may have been moved off network and back on, the process may be cold. The dashboard expects "eventual" delivery (seconds to minutes is fine).

ADR-0001 already explains why we need a custom C2 stack at all. This ADR explains why that stack has two channels rather than one.

## Decision

Two channels:

1. **WebSocket (primary)** — `WebSocketClientManager` keeps a persistent WSS connection open whenever the daemon is alive and the network is up. Commands flow inbound, results flow outbound. Used for low-latency interactive commands.

2. **FCM (fallback)** — `FcmCommandParser` is the entry point when the server delivers a command via FCM silent-data push. Used to wake the daemon when WSS is unavailable.

The server (`vyzorix-update-server`) decides which channel to use:

- If `hub.Hub` has an active WSS connection for the target device → send via WSS.
- Otherwise → send via FCM silent push.

The device-side `CommandHmacValidator` validates the command identically regardless of which channel it arrived on. The HMAC + nonce design is channel-agnostic.

## Alternatives Considered

### WSS-only

Rejected because:
- WSS dies during Doze mode (Android stops the network for the app process).
- WSS dies when the app process is reaped by the OOM-killer.
- WSS dies on network handoff (Wi-Fi → cellular → Wi-Fi).
- Reconnect logic exists but **cannot detect "the OS killed our process"** — only "the connection dropped." If the process is dead, there is no thread to run reconnect logic. We need an out-of-process wake-up mechanism, which FCM provides.

### FCM-only

Rejected because:
- FCM delivery latency is **unbounded** under Doze. The system reserves the right to batch silent pushes for delivery hours later when Doze ends.
- FCM has a global quota for silent (data-only) pushes per app. Heavy command traffic could exhaust the quota.
- FCM has a 4 KB payload limit. A `CommandFrame` for some commands (with embedded parameter blobs) approaches this limit. The WSS channel has no equivalent limit.
- FCM acknowledges delivery to the FCM service, not to our daemon. Confirming a command was received-and-executed requires a return channel, which is what WSS provides.

### MQTT or other persistent IoT bus

Considered. Rejected because:
- Requires a broker we'd need to operate (or pay for a managed one).
- Doesn't solve the "wake the device from Doze" problem any better than WSS — we'd still need FCM as a wake signal.
- Doesn't have an Android-blessed delivery path (FCM is the OS-provided path that bypasses some Doze restrictions when used with HIGH priority).

### Polling HTTP

Rejected as documented in ADR-0001.

## Consequences

**Locked in:**
- Server-side complexity: `hub.Hub` maintains the WSS connection map; `services/fcm` provides the FCM forwarder; `controllers/command` decides per-command which channel to use.
- Device-side complexity: two entry points (`WebSocketFrameHandler` and `FcmCommandParser`) both feeding into the same `CommandHmacValidator` → `RemoteCommandExecutor` pipeline.
- Pending results: a command can be sent via FCM but its result can only flow back when WSS reconnects, so `PendingResultQueue` exists to bridge the two channels.

**Closed off:**
- A simpler single-channel design. Not acceptable given the operational requirements above.

**Opened up:**
- Adding a third channel (e.g. SMS-based wake for environments where FCM is blocked) is straightforward — just another entry point feeding into `CommandHmacValidator`. The HMAC + nonce design is channel-agnostic.

## References

- `doc/COMMAND_SECURITY.md` — HMAC + nonce contract (channel-agnostic).
- `doc/UPDATE_SERVER_ARCHITECTURE_SPEC.md` §6 — server-side routing.
- `doc/SYSTEM_MAP.md` §3 — service interaction matrix shows both entry points.
- ADR-0001 — overall C2 rationale.
