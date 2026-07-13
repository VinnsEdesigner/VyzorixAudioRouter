package com.vyzorix.audiorouter.services.diagnostics

public class SoftRebootDetector { public fun detect(previousBootEpochMs: Long?, currentBootEpochMs: Long): Boolean = previousBootEpochMs != null && currentBootEpochMs > previousBootEpochMs }
