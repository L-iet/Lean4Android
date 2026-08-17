package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPresentationTest {
    @Test fun preferenceRoundTripAndFallbackAreDeterministic() {
        OutputPresentation.entries.forEach { presentation ->
            assertEquals(presentation, OutputPresentation.fromPreference(presentation.preferenceValue))
        }
        assertEquals(OutputPresentation.Docked, OutputPresentation.fromPreference(null))
        assertEquals(OutputPresentation.Docked, OutputPresentation.fromPreference("unknown"))
    }

    @Test fun revealTargetsOnlyTheSelectedPresentation() {
        assertEquals(OutputRevealState(dockedCollapsed = false, popupVisible = false), revealOutput(OutputPresentation.Docked, true))
        assertEquals(OutputRevealState(dockedCollapsed = true, popupVisible = true), revealOutput(OutputPresentation.Popup, true))
    }
}
