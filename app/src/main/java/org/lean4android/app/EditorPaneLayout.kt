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
