# ADR-0004: SQLCipher full-database encryption vs per-column encryption

## Status
Accepted

## Context

The daemon's Room database (`AppDatabase`, formerly `DaemonDatabase`) holds:

- Forensic event timeline (`RuntimeEventTimeline` entries).
- Routing log entries (`RoutingLogCollector` output).
- Crash trace records (`CrashTraceStore`).
- Last-known-state snapshots (`LastKnownStateDumper`).
- Soft-reboot tracker history (`SoftRebootTracker`).
- Diagnostic bundle metadata (filenames, sizes, timestamps).

Of these, only the **last-known-state snapshot** contains anything genuinely sensitive (it may include the projection token blob and pending HMAC nonces). The rest is operational telemetry that has no real confidentiality requirement on its own.

Two design options:

- **(A) SQLCipher full-database encryption** — wrap the entire SQLite file in AES-256 GCM via SQLCipher. All reads and writes go through the cipher layer.
- **(B) Per-column encryption** — leave the SQLite file plaintext; encrypt only the sensitive columns (e.g. the `last_known_state.blob` field) with `TokenEncryptor` at write time and decrypt at read time.

## Decision

**(A) SQLCipher full-database encryption.**

## Alternatives Considered

### Per-column encryption (option B)

Pros:
- Cheaper at runtime — most queries don't touch encrypted columns and avoid crypto overhead.
- Easier to debug — the SQLite file can be inspected with standard tools when you have the device.
- Smaller key surface — only the sensitive blob needs key management.

Cons (why rejected):
- **The sensitive blob is referenced by foreign keys from non-sensitive tables.** Once you start joining, the "is this column sensitive?" boundary leaks. The forensic timeline references state snapshots; correlating which snapshot was current at a soft-reboot tells you about the device's internal state at the time of the crash. The "non-sensitive" telemetry is sensitive when joined with the state.
- **Threat model drift.** Today the telemetry is "operational" and doesn't matter. If the project scales to multiple devices and someone forks the database off a device, that telemetry now identifies user behavior patterns. Future-proofing against this is cheap if done now.
- **Cognitive load.** "Which columns are encrypted?" is a per-table decision that has to be re-evaluated every time the schema changes. SQLCipher is a one-time decision.
- **Logcat / crash leakage.** With per-column encryption, the plaintext exists in memory after `getString()` and may end up in stack traces or logs. With SQLCipher, the entire file at rest is encrypted; in-memory leakage is unchanged but the at-rest surface is uniform.

### No encryption at all

Considered briefly. Rejected because the C22 ships with no full-disk encryption guarantee on the user-data partition (older Unisoc devices had degraded FDE implementations), and the project's defense-in-depth posture requires the application to provide its own at-rest protection.

## Consequences

**Locked in:**
- SQLCipher dependency in the device APK (~1.5 MB native code).
- All Room queries go through the cipher. Acceptable performance on the C22's eMMC for our query volume (~hundreds of writes/min sustained, occasional bulk reads).
- The DB passcode must be sealed by `KeystoreManager` (see DOC_7 §3.1). Loss of the passcode = data loss.

**Closed off:**
- Inspecting the SQLite file with `sqlite3` from the shell. Acceptable trade-off — the daemon exposes structured diagnostic exports via `DiagnosticCompression` which is the supported path for offline inspection.

**Opened up:**
- Uniform "encrypted at rest" guarantee for everything in `AppDatabase`. No per-column policy to audit.
- Forensic export bundles (`CrashTraceStore` → diagnostic ZIP) can be transmitted over plain HTTPS without worrying about leaking sensitive joined state, because the database content is already wrapped.

## References

- `doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md` §3 — KeystoreManager + SQLCipher integration.
- `doc/DOC_5_DIAGNOSTICS_CRASH_FORENSICS_AND_STORAGE.md` — diagnostic export pipeline.
