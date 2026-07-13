package com.vyzorix.audiorouter.services.stability

import com.vyzorix.audiorouter.common.enums.RiskLevel

public class StabilityClassifier { public fun classify(crashesLastHour: Int, thermalWarning: Boolean, memoryCritical: Boolean): RiskLevel = when { crashesLastHour >= 3 || memoryCritical -> RiskLevel.CRITICAL; crashesLastHour > 0 || thermalWarning -> RiskLevel.HIGH; else -> RiskLevel.STABLE } }
