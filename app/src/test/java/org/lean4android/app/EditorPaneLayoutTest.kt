package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorPaneLayoutTest {
    @Test fun autoFollowsOrientationWhileExplicitPlacementDoesNot() {
        assertEquals(GoalsPanePosition.Bottom, GoalsPanePosition.Auto.resolve(isPortrait = true))
        assertEquals(GoalsPanePosition.Right, GoalsPanePosition.Auto.resolve(isPortrait = false))
        assertEquals(GoalsPanePosition.Right, GoalsPanePosition.Right.resolve(isPortrait = true))
        assertEquals(GoalsPanePosition.Bottom, GoalsPanePosition.Bottom.resolve(isPortrait = false))
    }

    @Test fun persistedFractionsAreClampedToUsableBounds() {
        assertEquals(0.18f, clampPaneFraction(-1f))
        assertEquals(0.42f, clampPaneFraction(0.42f))
        assertEquals(0.68f, clampPaneFraction(2f))
    }
}
