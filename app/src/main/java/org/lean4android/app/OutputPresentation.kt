package org.lean4android.app

internal enum class OutputPresentation(val preferenceValue: String) {
    Docked("docked"),
    Popup("popup");

    companion object {
        fun fromPreference(value: String?): OutputPresentation =
            entries.firstOrNull { it.preferenceValue == value } ?: Docked
    }
}

internal data class OutputRevealState(
    val dockedCollapsed: Boolean,
    val popupVisible: Boolean,
)

internal fun revealOutput(presentation: OutputPresentation, dockedCollapsed: Boolean): OutputRevealState =
    if (presentation == OutputPresentation.Docked) OutputRevealState(dockedCollapsed = false, popupVisible = false)
    else OutputRevealState(dockedCollapsed = dockedCollapsed, popupVisible = true)
