package com.vyzorix.audiorouter.common.concurrency

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Long-lived coroutine scope for services. Uses a SupervisorJob so a single
 * child failure does NOT cancel the whole scope — child watchers are expected
 * to recover their own failures via RecoveryCoordinator (Layer 4+).
 */
public class ServiceScope(
    dispatchers: AppDispatchers = DefaultAppDispatchers,
) : CoroutineScope by CoroutineScope(SupervisorJob() + dispatchers.default)
