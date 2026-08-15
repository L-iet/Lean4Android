package org.lean4android.app

internal enum class GoalsPanePosition(val preferenceValue: String) {
    Auto("auto"),
    Right("right"),
    Bottom("bottom");

    fun resolve(isPortrait: Boolean): GoalsPanePosition = when (this) {
        Auto -> if (isPortrait) Bottom else Right
        else -> this
    }

    companion object {
        fun fromPreference(value: String?): GoalsPanePosition = entries.firstOrNull { it.preferenceValue == value } ?: Auto
    }
}

internal fun clampPaneFraction(value: Float): Float = value.coerceIn(0.18f, 0.68f)

internal fun paneVisible(collapsed: Boolean, dockedImeVisible: Boolean = false): Boolean =
    !collapsed && !dockedImeVisible

internal fun diagnosticSetHasError(severities: List<Int?>): Boolean = severities.any { it == 1 }

internal fun useCompactLandscapeTreeFloor(widthDp: Float, heightDp: Float, expanded: Boolean): Boolean =
    expanded && heightDp * 2f < widthDp
