# ADR-0001: Why a custom WebSocket + FCM C2 stack instead of off-the-shelf

## Status
Accepted

## Context

The project needs a way to send commands to a Nokia C22 daemon from a remote dashboard. Examples of commands: "force speaker for next 60s", "trigger soft-reboot diagnostic dump", "rotate FCM token", "enter safe mode", "fetch crash bundle".

The straightforward off-the-shelf alternatives:

- **SSH + shell script** — Standard for fleet management. Requires an SSH server on the device + reliable network ingress.
- **ADB over USB or local network** — Standard for Android development. Requires a host PC physically present or on the same network.
- **Tasker / Automate** — User-facing Android automation apps that can run arbitrary scripts.
- **Firebase Functions + Firestore listener** — Fully-managed, no infrastructure to maintain.
- **MQTT broker** — Standard IoT command bus.

Constraints that rule these out:

- No PC available — the operator's laptop was destroyed; ADB is not on the table.
- Tasker/Automate cannot be installed via Play Store on the C22 (no Google Play in scope), and even sideloaded they cannot reach into another foreground service to control it without their own AccessibilityService permission.
- SSH on stock Android requires root or a special userspace SSH daemon; neither is portable across system updates.
- Firebase Functions costs money once you cross the free tier AND its delivery latency is FCM-bounded anyway (the same as our current design).
- MQTT requires a broker that must be exposed to the public internet, plus device-side library + TLS pinning. Not significantly simpler than what we have.

## Decision

We built a **custom C2 stack with two channels**:

1. **WebSocket (primary)** — `WebSocketClientManager` maintains a persistent WSS connection to `vyzorix-update-server`. Used while the daemon is running and the network is up.
2. **FCM (fallback)** — `FcmCommandParser` receives silent push notifications when the daemon is offline / dozing / cold. Used to wake the daemon and deliver a single command.

Commands are authenticated with HMAC-SHA256 over a per-device shared secret (`command_secret`), with a 30s timestamp window and a `NonceCache` for replay protection. See `COMMAND_SECURITY.md` and ADR-0005 for the dual-channel rationale.

## Alternatives Considered

- **FCM-only** — Rejected because FCM delivery latency is unbounded under Doze and silent pushes have stricter delivery quotas. Real-time commands (e.g. "force speaker for the next 30 seconds while I'm on a call") need WSS responsiveness.
- **WSS-only** — Rejected because WSS connections drop during Doze / network loss / app cold-starts, and we need a wake-up path. That's what FCM is for.
- **Polling HTTP** — Rejected because it would either drain battery (frequent polls) or have unacceptable command latency (sparse polls).

## Consequences

**Locked in:**
- Server-side complexity: we now need a Go server that maintains WSS connections AND forwards to FCM. See `UPDATE_SERVER_ARCHITECTURE_SPEC.md`.
- Device-side complexity: HMAC validation, nonce cache, pending-result queue, projection-aware reconnect logic.
- Shared-secret management: `DeviceSecretStore`, server-side `secretstore.SecretStore` (see `DEVICE_REGISTRATION.md` §6.1).

**Closed off:**
- Dropping the server entirely. We are committed to running infrastructure.

**Opened up:**
- Multi-device scaling: the architecture supports any number of devices without redesign. Even though the current use case is one device, the C2 stack is the infrastructure that lets this expand later without a rewrite.
- Defense-in-depth security posture: even though the threat model is currently personal-deployment, the HMAC + nonce + per-device secret design is appropriate for adversarial environments. See ADR-0010 (future) when key rotation is added.

## References

- `doc/COMMAND_SECURITY.md` — HMAC contract.
- `doc/DEVICE_REGISTRATION.md` — registration flow that establishes `command_secret`.
- `doc/SYSTEM_MAP.md` §3 — command validation chain in the service interaction matrix.
- ADR-0005 — dual-channel rationale.
