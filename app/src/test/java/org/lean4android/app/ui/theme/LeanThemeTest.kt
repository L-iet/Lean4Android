package org.lean4android.app.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanThemeTest {
    @Test
    fun defaultDimensionsPreserveM46VisualBaseline() {
        val dimensions = LeanDimensions()

        assertEquals(300.dp, dimensions.drawerWidth)
        assertEquals(120.dp, dimensions.compactProjectTreeHeight)
        assertEquals(320.dp, dimensions.projectActionsMaxHeight)
        assertEquals(160.dp, dimensions.messagesMaxHeight)
        assertEquals(34.dp, dimensions.symbolButtonHeight)
        assertEquals(36.dp, dimensions.symbolButtonMinWidth)
        assertEquals(16.dp, dimensions.splitterThickness)
        assertEquals(100.dp, dimensions.outputMinHeight)
        assertEquals(200.dp, dimensions.outputMaxHeight)
    }

    @Test
    fun defaultDimensionRangesAreOrderedAndPositive() {
        val dimensions = LeanDimensions()

        assertTrue(dimensions.workspacePadding > 0.dp)
        assertTrue(dimensions.drawerWidth > 0.dp)
        assertTrue(dimensions.messagesCollapseButtonSize > 0.dp)
        assertTrue(dimensions.outputMinHeight > 0.dp)
        assertTrue(dimensions.outputMaxHeight >= dimensions.outputMinHeight)
    }
}
