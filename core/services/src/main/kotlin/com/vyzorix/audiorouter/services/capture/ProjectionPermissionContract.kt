// ProjectionPermissionContract — constants shared between the daemon
// service (in :core:services) and the ProjectionPermissionActivity (in
// :app).
//
// We pull these out of the activity class so :core:services doesn't
// have to depend on the :app module (it can't — the dependency direction
// only goes :app -> :core:services).

package com.vyzorix.audiorouter.services.capture

/**
 * Action / extra keys for the local broadcast that
 * `ProjectionPermissionActivity` (`:app`) emits once a projection token
 * is available.
 */
public object ProjectionPermissionContract {

    /** Broadcast action sent by the trampoline. */
    public const val ACTION_PROJECTION_RESULT: String =
        "com.vyzorix.audiorouter.action.PROJECTION_RESULT"

    public const val EXTRA_RESULT_CODE: String =
        "com.vyzorix.audiorouter.extra.PROJECTION_RESULT_CODE"
    public const val EXTRA_RESULT_DATA: String =
        "com.vyzorix.audiorouter.extra.PROJECTION_RESULT_DATA"
    public const val EXTRA_RESULT_ERROR: String =
        "com.vyzorix.audiorouter.extra.PROJECTION_RESULT_ERROR"

    /** Caller-supplied label for forensics. */
    public const val EXTRA_TRIGGER_ORIGIN: String =
        "com.vyzorix.audiorouter.extra.PROJECTION_TRIGGER_ORIGIN"

    public const val ORIGIN_UNKNOWN: String = "unknown"
    public const val ORIGIN_BOOTSTRAP: String = "bootstrap"
    public const val ORIGIN_DEATH_RECOVERY: String = "death_recovery"
    public const val ORIGIN_MANUAL: String = "manual"

    /** Standard request code used by the trampoline activity. */
    public const val REQUEST_CODE_PROJECTION: Int = 1001
}
