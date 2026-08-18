package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPaneLayoutTest {
    @Test fun autoFollowsOrientationWhileExplicitPlacementDoesNot() {
        assertEquals(GoalsPanePosition.Bottom, GoalsPanePosition.Auto.resolve(isPortrait = true))
        assertEquals(GoalsPanePosition.Right, GoalsPanePosition.Auto.resolve(isPortrait = false))
        assertEquals(GoalsPanePosition.Right, GoalsPanePosition.Right.resolve(isPortrait = true))
        assertEquals(GoalsPanePosition.Bottom, GoalsPanePosition.Bottom.resolve(isPortrait = false))
    }

    @Test fun autoPlacementDoesNotUseImeReducedWorkspaceShape() {
        // A 1600x2560 portrait window can expose an approximately 1600x1200 workspace
        // after a docked IME opens. The stable window orientation remains portrait.
        val stableWindowIsPortrait = true
        val imeReducedWorkspaceAppearsLandscape = 1600 > 1200
        assertTrue(imeReducedWorkspaceAppearsLandscape)
        assertEquals(GoalsPanePosition.Bottom, GoalsPanePosition.Auto.resolve(stableWindowIsPortrait))
    }

    @Test fun persistedFractionsAreClampedToUsableBounds() {
        assertEquals(0.18f, clampPaneFraction(-1f))
        assertEquals(0.42f, clampPaneFraction(0.42f))
        assertEquals(0.68f, clampPaneFraction(2f))
    }

    @Test fun collapsedAndDockedImeVisibilityPreserveIndependentState() {
        assertTrue(paneVisible(collapsed = false))
        assertFalse(paneVisible(collapsed = true))
        assertFalse(paneVisible(collapsed = false, dockedImeVisible = true))
        assertFalse(paneVisible(collapsed = true, dockedImeVisible = true))
        assertTrue(paneVisible(collapsed = false, dockedImeVisible = false))
    }

    @Test fun messagesUseErrorTreatmentOnlyForErrorSeverity() {
        assertFalse(diagnosticSetHasError(emptyList()))
        assertFalse(diagnosticSetHasError(listOf(null, 2, 3, 4)))
        assertTrue(diagnosticSetHasError(listOf(3, 1)))
    }

    @Test fun treeFloorAppliesOnlyToExpandedExtremeLandscape() {
        assertTrue(useCompactLandscapeTreeFloor(widthDp = 1000f, heightDp = 400f, expanded = true))
        assertFalse(useCompactLandscapeTreeFloor(widthDp = 800f, heightDp = 400f, expanded = true))
        assertFalse(useCompactLandscapeTreeFloor(widthDp = 1000f, heightDp = 400f, expanded = false))
    }
}
