# ADR-0006: `ProjectionDeathHandler` separate from `ProjectionTokenManager`

## Status
Accepted

## Context

The `MediaProjection` API exposes two distinct concerns:

1. **Token lifecycle** — acquiring the token via the system permission dialog, persisting it across daemon restarts, refreshing it when it expires, and tearing it down when the user explicitly revokes access.
2. **Involuntary death** — handling `MediaProjection.Callback.onStop()` when the OS itself tears down the session (memory pressure, Doze enforcement, app process kill, system update).

These could be implemented in a single class, or split into two. The current design splits them: `ProjectionTokenManager` owns concern (1); `ProjectionDeathHandler` owns concern (2).

## Decision

**Keep them separate.**

## Alternatives Considered

### Merge into a single `ProjectionTokenManager`

Pros:
- Fewer classes.
- All projection state lives in one place; easier to reason about transitions.

Cons (why rejected):
- **Failure isolation.** The recovery path from involuntary death is the complex one: it must log forensic data, pause `IdleCaptureController`, invoke `UiRecoveryDaemon`, drive `AccessibilityGestureQueue`, retry up to three times, and fall back to `CommunicationModeFallback` if all retries fail. If a bug in this code corrupts state, we don't want it to also corrupt the normal token-lifecycle bookkeeping (acquire / persist / refresh) which is much simpler.
- **Reasoning load.** `ProjectionTokenManager`'s public API has a small number of methods, all about token state. `ProjectionDeathHandler` is callback-driven and reacts to OS events. Mixing the two would force every reader to keep both mental models active at once.
- **Test surface.** Recovery tests need to simulate `onStop()` callbacks; token-lifecycle tests need to simulate user grant / revoke. The two test fixtures look nothing alike; the two classes would be tested separately even if merged.

### Merge plus a strategy pattern

Considered: one `ProjectionManager` that delegates to a `ProjectionDeathStrategy` for the recovery concern. Rejected because the strategy pattern's main benefit (swapping the strategy at runtime) does not apply here — we always want exactly one death-handling behavior. The split into two classes captures the same boundary without the strategy-pattern indirection.

### Eliminate `ProjectionDeathHandler` and rely on `CaptureRecoveryEngine`

Considered: have the general capture recovery engine watch for projection death as one of many failure modes. Rejected because projection death has unique requirements — it needs the `AccessibilityGestureQueue` to auto-click the permission dialog, which is a UI-level concern foreign to the capture pipeline. Keeping projection death in its own class keeps that UI-recovery dependency contained.

## Consequences

**Locked in:**
- Two-class boundary for projection state. New projection-related concerns must be assigned to one of them, not split.
- `ProjectionTokenManager` is the only writer of canonical token state; `ProjectionDeathHandler` reads it but does not mutate token bookkeeping directly. After a successful re-grant, the new token flows through `ProjectionTokenManager.acceptNewToken()`.

**Closed off:**
- A single God-class for everything projection-related.

**Opened up:**
- Future projection-related concerns (e.g. `ProjectionTokenRotationScheduler` if Android ever introduces token expiry) slot in as their own classes following the same boundary discipline.

## References

- `doc/MEDIA_PROJECTION_FLOW.md` §Mitigation 3 — full death-recovery sequence.
- `doc/SYSTEM_MAP.md` §3 — service interaction matrix for the capture lifecycle.
