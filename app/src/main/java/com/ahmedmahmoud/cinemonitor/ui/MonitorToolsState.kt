package com.ahmedmahmoud.cinemonitor.ui

enum class ActiveControlDrawer {
    NONE, SHUTTER, APERTURE, ISO, LUT_SETTINGS
}

data class MonitorToolsState(
    val isLutEnabled: Boolean = false,
    val activeLutName: String = "C-Log3 to Rec.709",
    val lutIntensity: Float = 1.0f,
    val isFalseColorEnabled: Boolean = false,
    val isPeakingEnabled: Boolean = false,
    val isZebraEnabled: Boolean = false,
    val isScreenLocked: Boolean = false,
    val isHudHidden: Boolean = false,
    val activeDrawer: ActiveControlDrawer = ActiveControlDrawer.NONE
)
