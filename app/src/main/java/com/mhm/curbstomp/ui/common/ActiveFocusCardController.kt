package com.mhm.curbstomp.ui.common

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.mhm.curbstomp.R
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.repository.SettingsRepository
import com.mhm.curbstomp.databinding.ActivityMainBinding
import com.mhm.curbstomp.domain.RuleEngine
import com.mhm.curbstomp.domain.StrictModePolicy
import com.mhm.curbstomp.ui.activity.SelectAppsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Manages the active Instant Focus card on the main dashboard:
 * live countdown, density-scaled app icons, pause/resume/stop, add time, and edit apps.
 */
class ActiveFocusCardController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val scope: LifecycleCoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val iconLoader: AppIconLoader,
    private val editFocusAppsLauncher: ActivityResultLauncher<Intent>
) {
    private var lastRenderedFocus: InstantFocus? = null
    private var hasRenderedFocusState: Boolean = false
    private var countdownJob: Job? = null
    private val focusIconJobs = mutableListOf<Job>()

    fun render(focus: InstantFocus?) {
        if (hasRenderedFocusState && focus == lastRenderedFocus) {
            if (focus == null || focus.isPaused || countdownJob?.isActive == true) {
                return
            }
        }
        val appsUnchanged = hasRenderedFocusState &&
            focus != null &&
            lastRenderedFocus != null &&
            focus.appLimits == lastRenderedFocus?.appLimits

        hasRenderedFocusState = true
        lastRenderedFocus = focus
        countdownJob?.cancel()

        if (focus == null) {
            cancelFocusIconJobs()
            binding.llActiveFocus.visibility = View.GONE
            binding.llQuickStart.visibility = View.VISIBLE
            return
        }

        binding.llActiveFocus.visibility = View.VISIBLE
        binding.llQuickStart.visibility = View.GONE

        binding.tvActiveFocusMode.setText(
            if (focus.isWhitelist) R.string.focus_mode_allowed_only else R.string.focus_mode_blocked
        )
        binding.tvActiveFocusMode.setTextColor(
            if (focus.isWhitelist) {
                context.getColor(android.R.color.holo_green_dark)
            } else {
                context.getColor(com.google.android.material.R.color.design_default_color_error)
            }
        )

        if (!appsUnchanged) {
            renderActiveFocusIcons(focus)
        }

        binding.btnPauseResumeFocus.setText(
            if (focus.isPaused) R.string.action_resume else R.string.action_pause
        )

        binding.btnPauseResumeFocus.setOnClickListener {
            scope.launch {
                if (focus.isPaused) {
                    settingsRepository.resumeInstantFocus(focus.id)
                } else {
                    PasswordDialog.verifyPassword(context, settingsRepository) {
                        scope.launch { settingsRepository.pauseInstantFocus(focus.id) }
                    }
                }
            }
        }

        binding.btnStopFocus.setOnClickListener {
            scope.launch {
                PasswordDialog.verifyPassword(context, settingsRepository) {
                    scope.launch { settingsRepository.stopInstantFocus(focus.id) }
                }
            }
        }

        binding.btnEditFocusApps.setOnClickListener {
            val intent = Intent(context, SelectAppsActivity::class.java).apply {
                putStringArrayListExtra(
                    SelectAppsActivity.EXTRA_PRE_SELECTED_APPS,
                    ArrayList(focus.appLimits.map { it.packageName })
                )
                putExtra(SelectAppsActivity.EXTRA_TITLE, context.getString(R.string.title_edit_focus_apps))
            }
            editFocusAppsLauncher.launch(intent)
        }

        binding.btnAddFocusTime.setOnClickListener {
            showAddFocusTimeDialog(focus)
        }

        startFocusCountdown(focus)
    }

    fun onStop() {
        countdownJob?.cancel()
        countdownJob = null
        cancelFocusIconJobs()
        hasRenderedFocusState = false
    }

    fun onDestroy() {
        countdownJob?.cancel()
        cancelFocusIconJobs()
    }

    fun saveEditedFocusApps(newApps: List<String>) {
        scope.launch {
            val currentSessions = settingsRepository.settings.first().instantFocusSessions
            val oldSession = RuleEngine.findDisplayInstantFocus(
                currentSessions,
                System.currentTimeMillis()
            ) ?: return@launch

            val requiresPassword = StrictModePolicy.requiresPasswordForFocusAppsEdit(
                oldFocus = oldSession,
                newPackages = newApps.toSet()
            )

            val persist: () -> Unit = {
                scope.launch {
                    settingsRepository.updateInstantFocusApps(oldSession.id, newApps)
                    Toast.makeText(
                        context,
                        R.string.toast_focus_apps_updated,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            if (requiresPassword) {
                PasswordDialog.verifyPassword(context, settingsRepository) { persist() }
            } else {
                persist()
            }
        }
    }

    private fun cancelFocusIconJobs() {
        for (job in focusIconJobs) {
            job.cancel()
        }
        focusIconJobs.clear()
    }

    private fun renderActiveFocusIcons(focus: InstantFocus) {
        cancelFocusIconJobs()
        binding.llActiveFocusApps.removeAllViews()

        val density = context.resources.displayMetrics.density
        val iconSizePx = (ICON_SIZE_DP * density).toInt()
        val iconMarginPx = (ICON_MARGIN_DP * density).toInt()

        val displayLimit = minOf(focus.appLimits.size, MAX_VISIBLE_ICONS)
        for (i in 0 until displayLimit) {
            val pkg = focus.appLimits[i].packageName
            val iv = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                    marginEnd = iconMarginPx
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            binding.llActiveFocusApps.addView(iv)
            iconLoader.bind(scope, pkg, iv)?.let { focusIconJobs.add(it) }
        }
        if (focus.appLimits.size > MAX_VISIBLE_ICONS) {
            val tv = TextView(context).apply {
                text = context.getString(R.string.focus_extra_apps_count, focus.appLimits.size - MAX_VISIBLE_ICONS)
                setTextColor(context.getColor(android.R.color.black))
                textSize = 14f
            }
            binding.llActiveFocusApps.addView(tv)
        }
    }

    private fun startFocusCountdown(focus: InstantFocus) {
        countdownJob = scope.launch {
            if (focus.isPaused) {
                binding.tvActiveFocusCountdown.text = formatCountdown(focus.remainingMillis.coerceAtLeast(0L))
                binding.tvActiveFocusCountdown.alpha = 0.5f
                return@launch
            }

            binding.tvActiveFocusCountdown.alpha = 1.0f
            while (isActive) {
                val remaining = focus.endTimeMillis - System.currentTimeMillis()
                if (remaining <= 0L) {
                    render(null)
                    break
                }
                binding.tvActiveFocusCountdown.text = formatCountdown(remaining)
                delay(1_000L)
            }
        }
    }

    private fun showAddFocusTimeDialog(focus: InstantFocus) {
        val options = arrayOf(
            context.getString(R.string.option_plus_10_min),
            context.getString(R.string.option_plus_30_min),
            context.getString(R.string.option_plus_1_hour),
            context.getString(R.string.option_plus_2_hours),
            context.getString(R.string.option_custom)
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_add_focus_time_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addFocusTime(focus, 10L)
                    1 -> addFocusTime(focus, 30L)
                    2 -> addFocusTime(focus, 60L)
                    3 -> addFocusTime(focus, 120L)
                    4 -> TimeDialogs.showDurationOrLimitPicker(
                        context = context,
                        initialMinutes = 15L,
                        title = context.getString(R.string.action_add_time),
                        confirmLabel = context.getString(R.string.action_add_time),
                        showFullyBlock = false
                    ) { totalMins ->
                        if (totalMins > 0L) addFocusTime(focus, totalMins)
                    }
                }
            }
            .show()
    }

    private fun addFocusTime(focus: InstantFocus, minutes: Long) {
        scope.launch {
            settingsRepository.addInstantFocusTime(focus.id, minutes)
            Toast.makeText(
                context,
                context.resources.getQuantityString(R.plurals.toast_added_focus_time, minutes.toInt(), minutes),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        private const val MAX_VISIBLE_ICONS = 8
        private const val ICON_SIZE_DP = 32
        private const val ICON_MARGIN_DP = 6

        fun formatCountdown(millis: Long): String {
            val h = TimeUnit.MILLISECONDS.toHours(millis)
            val m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
            return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        }
    }
}
