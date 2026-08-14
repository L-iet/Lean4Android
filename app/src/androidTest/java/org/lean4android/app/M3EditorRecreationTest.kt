package org.lean4android.app

import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M3EditorRecreationTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun mainActivityResumesAfterRecreation() {
        assertEquals(Lifecycle.State.RESUMED, activity.scenario.state)
        activity.scenario.recreate()
        assertEquals(Lifecycle.State.RESUMED, activity.scenario.state)
    }
}
