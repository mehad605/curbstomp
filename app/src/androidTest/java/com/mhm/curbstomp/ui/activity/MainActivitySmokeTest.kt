package com.mhm.curbstomp.ui.activity

import android.graphics.Rect
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mhm.curbstomp.R
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun launchesDashboardAndDisplaysCoreSections() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.rowAccessibility)).check(matches(isDisplayed()))
            onView(withId(R.id.rowDeviceAdmin)).check(matches(isDisplayed()))
            onView(withId(R.id.btnCreateRule)).perform(nestedScrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.btnInstantFocus30m)).perform(nestedScrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.btnSetPassword)).perform(nestedScrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun opensCreateRuleAndInstantFocusScreens() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnCreateRule)).perform(nestedScrollTo(), click())
            onView(withId(R.id.etRuleName)).check(matches(isDisplayed()))
            pressBack()

            onView(withId(R.id.btnCreateInstantFocus)).perform(nestedScrollTo(), click())
            onView(withId(R.id.npMinutes)).check(matches(isDisplayed()))
            onView(withId(R.id.btnSaveLimit)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.tvDurationDisplay)).check(matches(isDisplayed()))
            pressBack()

            onView(withId(R.id.rowAccessibility)).perform(nestedScrollTo()).check(matches(isDisplayed()))
        }
    }

    private fun nestedScrollTo(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = allOf(
            withEffectiveVisibility(Visibility.VISIBLE),
            isDescendantOfA(isAssignableFrom(NestedScrollView::class.java))
        )

        override fun getDescription(): String = "scroll to view inside NestedScrollView"

        override fun perform(uiController: UiController, view: View) {
            val rect = Rect(0, 0, view.width, view.height)
            view.requestRectangleOnScreen(rect, true)
            uiController.loopMainThreadUntilIdle()
        }
    }
}
