# Architecture Decision Records (ADRs)

This folder holds the WHY behind major architectural choices. The rest of `doc/` describes WHAT the system does; an ADR describes why this option was chosen over alternatives and what trade-offs were accepted.

## Format

Each ADR is a short markdown file with these sections:

```
# ADR-NNNN: <decision title>

## Status
Accepted | Superseded by ADR-NNNN | Deprecated

## Context
What is the situation that demanded a decision?

## Decision
What did we decide?

## Alternatives Considered
What other options were on the table, and why were they rejected?

## Consequences
What does this decision lock us into? What does it close off? What does it open up?

## References
Links to related docs / code / issues.
```

Keep each ADR under ~150 lines. If it grows past that, the decision is probably actually two decisions.

## Process

1. New significant decisions get a new ADR.
2. ADRs are append-only — never edit an old ADR's decision. If a decision changes, write a new ADR with "Status: Supersedes ADR-NNNN" and update the old one's status to "Superseded by ADR-MMMM".
3. ADR numbers are monotonically increasing — never reuse a number even if an ADR is rejected.

## Index

| # | Title | Status |
|---|-------|--------|
| [0001](./0001-c2-stack-rationale.md) | Why a custom WebSocket + FCM C2 stack instead of off-the-shelf | Accepted |
| [0002](./0002-observer-fleet-as-measurement-instrument.md) | The observer fleet is a measurement instrument, not over-engineering | Accepted |
| [0003](./0003-go-server-vs-firebase-functions.md) | Go server on Render instead of Firebase Functions | Accepted |
| [0004](./0004-sqlcipher-full-db-vs-encrypted-columns.md) | SQLCipher full-database encryption vs per-column encryption | Accepted |
| [0005](./0005-websocket-plus-fcm-dual-channel.md) | WebSocket + FCM dual-channel instead of either alone | Accepted |
| [0006](./0006-projection-death-handler-separate-from-token-manager.md) | `ProjectionDeathHandler` separate from `ProjectionTokenManager` | Accepted |
| [0007](./0007-three-layer-health-monitoring.md) | Three-layer health monitoring (Signal → Aggregator → Coordinator) | Accepted |
| [0008](./0008-device-quirk-profile-system.md) | `DeviceQuirkProfile` runtime abstraction over hardcoded Nokia C22 logic | Accepted |
| [0009](./0009-phase-1-mock-first.md) | Phase 1 ships against a mock server, not vyzorix-update-server | Accepted |
