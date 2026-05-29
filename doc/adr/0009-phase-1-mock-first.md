# ADR-0009: Phase 1 ships against a mock server, not vyzorix-update-server

## Status
Accepted

## Context

The project has two top-level deliverables:

- **VyzorixAudioRouter** — the Android daemon (this repository).
- **vyzorix-update-server** — the Go server providing C2 + OTA.

The original phase plan was:

```
Phase 1 = Android daemon fully done (Layers 0-8 from BUILD_ORDER.md)
Phase 2 = Server
```

This creates a chicken-and-egg problem at Layer 8 (the C2 stack in `BUILD_ORDER.md`). Layer 8 includes `WebSocketClientManager`, `FcmTokenManager`, `CommandHmacValidator`, etc. These cannot be meaningfully tested without a server to talk to. So "Phase 1 done" is impossible until Phase 2 also has at least registration + WSS handshake + a command dispatch endpoint.

## Decision

**Reframe the phases. Phase 1 ships against a mock server.**

```
Phase 1   = Device runs Layers 0-8 against a MOCK SERVER
            (mockserver is a small Go binary at
             vyzorix-update-server/cmd/mockserver/)
Phase 1.5 = Mock server is replaced with the real
            vyzorix-update-server (registration, SQLite, secret
            store, command routing, telemetry).
Phase 2   = Dashboard, OTA update mechanism, key rotation,
            multi-device support.
Phase 3   = Hardening, monitoring, scaling.
```

The mock server is a real Go binary, not test fixtures. It implements just enough of `DEVICE_REGISTRATION.md` to make Layer 8 testable:

- `POST /v1/device/register` returns a deterministic `command_secret` (all-zeros for CI; configurable for local development).
- `PATCH /v1/device/:id/fcm-token` accepts and acks.
- WSS `/v1/ws` accepts the HMAC handshake (using the same deterministic secret) and echoes any frame back as a no-op command result.
- `POST /v1/command` accepts dashboard-side command submissions and forwards over WSS (when connected) or no-ops (when not).
- No persistence. Process restart clears all state.
- No real FCM integration. The mock cannot wake a dormant daemon; tests that need wake-from-FCM behavior use Firebase's emulator suite instead.

This means:

1. **Phase 1 Definition of Done** becomes "device runs Layers 0–8 for 7 days continuous on a real C22 against the local mock server, with zero crashes." That is a meaningful, testable bar that doesn't depend on a real server existing.
2. **CI gets the mock server for free** — the same binary documented in `CI_CD_WORKFLOWS.md` §CI Test Secret Injection.
3. **Phase 1.5 is a swap, not a rewrite** — moving from mock to real means changing the device's `updateServerUrl` and rebuilding. No client code changes if the mock implements the same contract.
4. **The mock server becomes a permanent CI artifact** — even after Phase 1.5 ships the real server, the mock remains in CI so test flakiness from Render cold-starts doesn't impact PRs.

## Alternatives Considered

### Build the real server first

Considered. Rejected because the operator's focus is on getting the audio path working on the C22 (the load-bearing risk of the project, see ADR-0002). Server-side work is lower-risk and benefits from waiting until the device's needs are concrete.

### In-process test fixtures instead of a separate Go binary

Considered: use Kotlin coroutines to stub out the WSS server inside the device test process. Rejected because:
- Doesn't validate the cross-process / cross-network code paths (serialization, HTTP semantics, WSS handshake).
- Can't be used by the manual development testing the operator does on the C22.
- The mock binary is small enough (~200 lines of Go) that the cost of writing it once is less than the test-fidelity cost of fixtures forever.

### Make Phase 1 not include Layer 8

Considered: stop Phase 1 at Layer 7 (update system) and defer all C2 work to Phase 2. Rejected because Layer 7 (OTA updater) itself needs the server contract defined (it pulls manifests + APKs from the same server), so the contract has to be in place before Layer 7 either. Adding the mock satisfies both Layer 7 and Layer 8 with one stroke.

## Consequences

**Locked in:**
- A `cmd/mockserver/` binary in `vyzorix-update-server` is a Phase 1 deliverable.
- The mock binary must stay in sync with the contract documented in `DEVICE_REGISTRATION.md`. When the contract changes, both implementations (real + mock) must be updated.
- The Phase 1 acceptance gate ("device runs 7 days against mock") is testable on the operator's own hardware without requiring server infrastructure to be ready.

**Closed off:**
- A pure "device-only" Phase 1 with no Go code at all. The mock is small but real Go.

**Opened up:**
- Decoupled velocity: the device side and server side can develop in parallel after Phase 1, because both implementations share a stable contract documented in `DEVICE_REGISTRATION.md`.
- Fast CI: instrumented tests run against the in-process mock, not over the network.
- Lower-stress operator testing: the operator can run Phase 1 acceptance tests on the C22 without first deploying anything to Render.

## References

- `doc/BUILD_ORDER.md` — Layer 8 description updated to reference the mock server target.
- `doc/DEVICE_REGISTRATION.md` — contract that both real and mock implementations must satisfy.
- `doc/CI_CD_WORKFLOWS.md` §CI Test Secret Injection — CI configuration that uses the mock.
- `doc/README.md` — phase descriptions updated.
