# ADR-0003: Go server on Render instead of Firebase Functions

## Status
Accepted

## Context

The C2 / update server needs to:

1. Accept device registrations (`POST /v1/device/register`) and issue per-device `command_secret`.
2. Maintain persistent WebSocket connections to online devices.
3. Forward commands from the dashboard to either WSS (if connected) or FCM (if offline).
4. Serve update artifacts (APK + manifest) for the OTA mechanism.
5. Persist device state, command history, telemetry buffers.

Options:

- **Firebase Cloud Functions + Firestore** — Fully managed. Pay-per-invocation. Built-in FCM integration. No long-running connections (functions are stateless / short-lived).
- **Go server on Render / Fly.io / Railway** — Run a real process. Long-running WSS connections trivial. Pay-per-instance.
- **Node.js / Python server on the same providers** — Similar to Go, different runtime characteristics.

## Decision

**Go server on Render (free tier for v1).**

## Alternatives Considered

### Firebase Functions + Firestore

Rejected for these reasons:

- **WebSocket support is impossible** — Firebase Functions are stateless and have a 60-min max execution time (paid tier; 9 min free). Long-running WSS is fundamentally incompatible with the Functions runtime. We would have to fall back to FCM-only delivery, which trades real-time responsiveness for unbounded latency. ADR-0005 documents why we need both channels.
- **Cold-start latency on first invocation** — Functions cold-start can be 1-3s. For interactive command delivery this is noticeable; for a status poll it's deal-breaking.
- **Cost shape doesn't match the workload** — A few WSS connections kept alive 24/7 + occasional commands. Functions bill per invocation, which makes long-lived WSS connections nonsensical even if they were technically possible.
- **Firestore for telemetry is wrong shape** — Telemetry is append-mostly, sometimes-queried-by-recent. SQLite + a thin Go server is a better fit than a document store with per-document billing.
- **Vendor lock-in** — Moving away from Firebase later requires rewriting the client SDK + the auth model. Moving away from a Go HTTP server later is "deploy to a different provider."

### Node.js / Python equivalent

Acceptable but Go was chosen for:

- Single-binary deployment (no runtime to manage on the server).
- Strong concurrency model for WSS connection handling (goroutines + channels map directly onto our `hub.Hub` design).
- Lower memory footprint per concurrent connection (relevant for Render's small instance sizes).
- Type safety on a server we expect to run unattended.

### Self-hosted on a VPS

Considered. Rejected because:

- We do not currently have a VPS, and the operator does not have a local laptop to administer one.
- Render's free tier covers the v1 deployment, and the upgrade path to a paid plan is one click.
- A managed provider relieves us from securing the host OS, certificate renewal, monitoring, etc.

## Consequences

**Locked in:**
- Render-specific behaviors (free tier sleep, dyno restart on deploys). UptimeRobot is wired to ping `/health` every 5 minutes to keep the dyno warm — documented in `UPDATE_SERVER.md`.
- Go ecosystem for the server. Cross-language tooling between Kotlin (device) and Go (server) is acceptable.

**Closed off:**
- Firebase-native ecosystem benefits (Auth, Hosting, Crashlytics). We do not use these and we won't.

**Opened up:**
- Easy migration path off Render to any container host (Fly.io, Railway, a VPS we don't yet have) because the artifact is a single Go binary in a Docker image.
- Mock server flavor for CI: a thin Go binary at `vyzorix-update-server/cmd/mockserver/` that implements just enough of the registration + command surface to support Phase 1 testing. See ADR-0009.

## References

- `doc/UPDATE_SERVER.md` — server architecture.
- `doc/UPDATE_SERVER_ARCHITECTURE_SPEC.md` — connection hub design.
- `doc/DEVICE_REGISTRATION.md` — REST + WSS contract.
- ADR-0005 — dual-channel rationale.
- ADR-0009 — Phase 1 mock-first.
