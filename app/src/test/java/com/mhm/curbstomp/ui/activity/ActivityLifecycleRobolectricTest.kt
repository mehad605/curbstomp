package com.mhm.curbstomp.ui.activity

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mhm.curbstomp.Curbstomp
import com.mhm.curbstomp.R
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityLifecycleRobolectricTest {

    @Test
    fun mainActivityRendersDashboardRulesAndActiveFocusAndTrimsMemoryCleanly() {
        val app = ApplicationProvider.getApplicationContext<Curbstomp>()
        val now = System.currentTimeMillis()
        runBlocking {
            app.container.settingsRepository.upsertRule(
                Rule(
                    id = "r-smoke",
                    name = "Focus Hours",
                    startTime = "00:00",
                    endTime = "23:59",
                    daysOfWeek = listOf(
                        Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
                        Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
                    ),
                    appLimits = listOf(AppLimit("com.example.distraction", 0L)),
                    isWhitelist = false,
                    isActive = true
                )
            )
            app.container.settingsRepository.upsertInstantFocus(
                InstantFocus(
                    id = "f-smoke",
                    startTimeMillis = now - 5_000L,
                    endTimeMillis = now + 1_800_000L,
                    appLimits = listOf(AppLimit("com.example.distraction", 0L)),
                    isActive = true,
                    isWhitelist = false
                )
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.rowAccessibility))
                assertNotNull(activity.findViewById<View>(R.id.rowDeviceAdmin))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.llActiveFocus).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.llQuickStart).visibility)
            }
        }

        // Exercise Application.onTrimMemory cleanup path
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    }

    @Test
    fun createRuleActivityPreservesFormStateAcrossScreenRotation() {
        ActivityScenario.launch(CreateRuleActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.etRuleName).setText("Evening Study")
                activity.findViewById<View>(R.id.cardAllowOnly).performClick()
            }

            // Simulate configuration change / screen rotation
            scenario.recreate()

            scenario.onActivity { activity ->
                assertEquals(
                    "Evening Study",
                    activity.findViewById<EditText>(R.id.etRuleName).text.toString()
                )
                assertTrue(activity.findViewById<RadioButton>(R.id.rbAllowOnly).isChecked)
            }
        }
    }

    @Test
    fun instantFocusActivityPrefillsDurationAndSurvivesRotation() {
        val app = ApplicationProvider.getApplicationContext<Curbstomp>()
        val intent = Intent(app, InstantFocusActivity::class.java).apply {
            putExtra(InstantFocusActivity.EXTRA_PREFILL_DURATION_MINS, 90L)
        }

        ActivityScenario.launch<InstantFocusActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("1h 30m", activity.findViewById<TextView>(R.id.tvDurationDisplay).text.toString())
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                assertEquals("1h 30m", activity.findViewById<TextView>(R.id.tvDurationDisplay).text.toString())
            }
        }
    }

    @Test
    fun selectAppsActivityReturnsPreselectedAppsOnConfirm() {
        val app = ApplicationProvider.getApplicationContext<Curbstomp>()
        val intent = Intent(app, SelectAppsActivity::class.java).apply {
            putExtra(SelectAppsActivity.EXTRA_TITLE, "Choose Apps")
            putStringArrayListExtra(
                SelectAppsActivity.EXTRA_PRE_SELECTED_APPS,
                arrayListOf("com.example.one", "com.example.two")
            )
        }

        ActivityScenario.launch<SelectAppsActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Choose Apps", activity.findViewById<TextView>(R.id.toolbar_title).text.toString())
                activity.findViewById<Button>(R.id.btn_confirm).performClick()
                val shadowActivity = shadowOf(activity)
                assertEquals(Activity.RESULT_OK, shadowActivity.resultCode)
                val returned = shadowActivity.resultIntent.getStringArrayListExtra(SelectAppsActivity.EXTRA_SELECTED_APPS)
                assertEquals(listOf("com.example.one", "com.example.two"), returned)
            }
        }
    }
}
