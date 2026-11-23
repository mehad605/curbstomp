package com.mhm.curbstomp.ui.activity

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.mhm.curbstomp.R
import com.mhm.curbstomp.appContainer
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import com.mhm.curbstomp.databinding.ActivityMainBinding
import com.mhm.curbstomp.domain.RuleEngine
import com.mhm.curbstomp.domain.StrictModePolicy
import com.mhm.curbstomp.receivers.CurbstompDeviceAdminReceiver
import com.mhm.curbstomp.services.CurbstompService
import com.mhm.curbstomp.ui.adapter.RuleAdapter
import com.mhm.curbstomp.ui.adapter.RuleListItem
import com.mhm.curbstomp.ui.common.PasswordDialog
import com.mhm.curbstomp.ui.common.PermissionUtils
import com.mhm.curbstomp.ui.common.TimeDialogs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ruleAdapter: RuleAdapter

    private var lastRenderedFocus: InstantFocus? = null
    private var hasRenderedFocusState: Boolean = false
    private var activeFocusJob: Job? = null
    private val focusIconJobs = mutableListOf<Job>()

    private val editFocusAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val apps = result.data?.getStringArrayListExtra(SelectAppsActivity.EXTRA_SELECTED_APPS)
                    ?: return@registerForActivityResult
                saveEditedFocusApps(apps)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Curbstomp)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = appContainer.settingsRepository
        ruleAdapter = RuleAdapter(
            scope = lifecycleScope,
            iconLoader = appContainer.appIconLoader,
            onEditRule = { rule ->
                startActivity(
                    Intent(this, CreateRuleActivity::class.java).apply {
                        putExtra(CreateRuleActivity.EXTRA_EDIT_RULE_ID, rule.id)
                    }
                )
            },
            onDeleteRule = { rule ->
                lifecycleScope.launch {
                    PasswordDialog.verifyPassword(this@MainActivity, repo) {
                        lifecycleScope.launch { repo.deleteRule(rule.id) }
                    }
                }
            },
            onToggleRule = { rule, targetActive ->
                lifecycleScope.launch {
                    if (targetActive) {
                        repo.setRuleActive(rule.id, true)
                    } else {
                        PasswordDialog.verifyPassword(this@MainActivity, repo) {
                            lifecycleScope.launch { repo.setRuleActive(rule.id, false) }
                        }
                    }
                }
            }
        )

        binding.rvRules.layoutManager = LinearLayoutManager(this)
        binding.rvRules.adapter = ruleAdapter

        setupListeners()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.settings.collectLatest { settings ->
                    renderSettings(settings)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusCards()
    }

    override fun onStop() {
        super.onStop()
        activeFocusJob?.cancel()
        activeFocusJob = null
        cancelFocusIconJobs()
        hasRenderedFocusState = false
    }

    override fun onDestroy() {
        activeFocusJob?.cancel()
        cancelFocusIconJobs()
        super.onDestroy()
    }

    private fun cancelFocusIconJobs() {
        for (job in focusIconJobs) {
            job.cancel()
        }
        focusIconJobs.clear()
    }

    private fun renderSettings(settings: Settings) {
        val currentTime = System.currentTimeMillis()
        val activeFocus = RuleEngine.findDisplayInstantFocus(
            settings.instantFocusSessions,
            currentTime
        )

        val calendar = Calendar.getInstance().apply { timeInMillis = currentTime }
        val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val todayKey = RuleEngine.formatDateKey(calendar)
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayKey = RuleEngine.formatDateKey(calendar)

        val activeRules = ArrayList<Rule>()
        val inactiveRules = ArrayList<Rule>()
        for (rule in settings.rules) {
            val activeKey = RuleEngine.activeRuleDateKey(
                rule = rule,
                dayOfWeek = currentDay,
                minuteOfDay = minuteOfDay,
                todayKey = todayKey,
                yesterdayKey = yesterdayKey
            )
            if (activeKey != null) activeRules.add(rule) else inactiveRules.add(rule)
        }

        val listItems = ArrayList<RuleListItem>(settings.rules.size + 2)
        if (activeRules.isNotEmpty()) {
            listItems.add(RuleListItem.Header("Active"))
            for (rule in activeRules) listItems.add(RuleListItem.RuleItem(rule))
        }
        if (inactiveRules.isNotEmpty()) {
            listItems.add(RuleListItem.Header("Inactive"))
            for (rule in inactiveRules) listItems.add(RuleListItem.RuleItem(rule))
        }

        ruleAdapter.submitList(
            newItems = listItems,
            usageMap = settings.appUsageMillis,
            minuteOfDay = minuteOfDay,
            todayKey = todayKey,
            yesterdayKey = yesterdayKey
        )

        binding.btnSetPassword.text =
            if (!settings.passwordHash.isNullOrEmpty()) "UPDATE PASSWORD" else "SET PASSWORD"

        updateActiveFocusUI(activeFocus)
    }

    private fun updateStatusCards() {
        val isAccActive = PermissionUtils.isAccessibilityServiceEnabled(this, CurbstompService::class.java)
        val isAdminActive = PermissionUtils.isDeviceAdminActive(this, CurbstompDeviceAdminReceiver::class.java)

        binding.switchAccessibility.isChecked = isAccActive
        binding.switchDeviceAdmin.isChecked = isAdminActive

        val repo = appContainer.settingsRepository
        lifecycleScope.launch {
            val settings = repo.settings.first()
            binding.switchExtraHarden.isChecked = settings.extraHardenEnabled
            if (settings.antiUninstallEnabled != isAdminActive) {
                repo.setAntiUninstallEnabled(isAdminActive)
            }
        }
    }

    private fun updateActiveFocusUI(focus: InstantFocus?) {
        if (hasRenderedFocusState && focus == lastRenderedFocus) {
            if (focus == null || focus.isPaused || activeFocusJob?.isActive == true) {
                return
            }
        }
        val appsUnchanged = hasRenderedFocusState &&
            focus != null &&
            lastRenderedFocus != null &&
            focus.appLimits == lastRenderedFocus?.appLimits

        hasRenderedFocusState = true
        lastRenderedFocus = focus
        activeFocusJob?.cancel()

        if (focus == null) {
            cancelFocusIconJobs()
            binding.llActiveFocus.visibility = View.GONE
            binding.llQuickStart.visibility = View.VISIBLE
            return
        }

        binding.llActiveFocus.visibility = View.VISIBLE
        binding.llQuickStart.visibility = View.GONE

        binding.tvActiveFocusMode.text =
            if (focus.isWhitelist) "ONLY THESE APPS ALLOWED" else "THESE APPS BLOCKED"
        binding.tvActiveFocusMode.setTextColor(
            if (focus.isWhitelist) {
                getColor(android.R.color.holo_green_dark)
            } else {
                getColor(com.google.android.material.R.color.design_default_color_error)
            }
        )

        if (!appsUnchanged) {
            renderActiveFocusIcons(focus)
        }

        val repo = appContainer.settingsRepository
        binding.btnPauseResumeFocus.text = if (focus.isPaused) "RESUME" else "PAUSE"

        binding.btnPauseResumeFocus.setOnClickListener {
            lifecycleScope.launch {
                if (focus.isPaused) {
                    repo.resumeInstantFocus(focus.id)
                } else {
                    PasswordDialog.verifyPassword(this@MainActivity, repo) {
                        lifecycleScope.launch { repo.pauseInstantFocus(focus.id) }
                    }
                }
            }
        }

        binding.btnStopFocus.setOnClickListener {
            lifecycleScope.launch {
                PasswordDialog.verifyPassword(this@MainActivity, repo) {
                    lifecycleScope.launch { repo.stopInstantFocus(focus.id) }
                }
            }
        }

        binding.btnEditFocusApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java).apply {
                putStringArrayListExtra(
                    SelectAppsActivity.EXTRA_PRE_SELECTED_APPS,
                    ArrayList(focus.appLimits.map { it.packageName })
                )
                putExtra(SelectAppsActivity.EXTRA_TITLE, "Edit Focus Apps")
            }
            editFocusAppsLauncher.launch(intent)
        }

        binding.btnAddFocusTime.setOnClickListener {
            showAddFocusTimeDialog(focus)
        }

        startFocusCountdown(focus)
    }

    private fun renderActiveFocusIcons(focus: InstantFocus) {
        cancelFocusIconJobs()
        binding.llActiveFocusApps.removeAllViews()
        val displayLimit = minOf(focus.appLimits.size, 8)
        for (i in 0 until displayLimit) {
            val pkg = focus.appLimits[i].packageName
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(96, 96).apply { marginEnd = 16 }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            binding.llActiveFocusApps.addView(iv)
            appContainer.appIconLoader.bind(lifecycleScope, pkg, iv)?.let { focusIconJobs.add(it) }
        }
        if (focus.appLimits.size > 8) {
            val tv = TextView(this).apply {
                text = "+${focus.appLimits.size - 8}"
                setTextColor(getColor(android.R.color.black))
                textSize = 14f
            }
            binding.llActiveFocusApps.addView(tv)
        }
    }

    private fun startFocusCountdown(focus: InstantFocus) {
        activeFocusJob = lifecycleScope.launch {
            if (focus.isPaused) {
                binding.tvActiveFocusCountdown.text = formatCountdown(focus.remainingMillis.coerceAtLeast(0L))
                binding.tvActiveFocusCountdown.alpha = 0.5f
                return@launch
            }

            binding.tvActiveFocusCountdown.alpha = 1.0f
            while (isActive) {
                val remaining = focus.endTimeMillis - System.currentTimeMillis()
                if (remaining <= 0L) {
                    updateActiveFocusUI(null)
                    break
                }
                binding.tvActiveFocusCountdown.text = formatCountdown(remaining)
                delay(1_000L)
            }
        }
    }

    private fun formatCountdown(millis: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(millis)
        val m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun setupListeners() {
        val repo = appContainer.settingsRepository

        binding.rowAccessibility.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.rowDeviceAdmin.setOnClickListener {
            if (!binding.switchDeviceAdmin.isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("Enable Uninstall Invincibility?")
                    .setMessage("This will prevent the app from being uninstalled or force-closed. You will need to use your Curbstomp password to disable this feature later. Are you absolutely sure?")
                    .setPositiveButton("I UNDERSTAND & ENABLE") { _, _ ->
                        lifecycleScope.launch {
                            repo.markDeviceAdminActivationRequested()
                            PermissionUtils.requestDeviceAdmin(
                                context = this@MainActivity,
                                adminClass = CurbstompDeviceAdminReceiver::class.java,
                                explanation = "Required to prevent the app from being uninstalled or force-stopped."
                            )
                        }
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            } else {
                lifecycleScope.launch {
                    PasswordDialog.verifyPassword(this@MainActivity, repo) {
                        PermissionUtils.removeDeviceAdmin(
                            this@MainActivity,
                            CurbstompDeviceAdminReceiver::class.java
                        )
                        binding.switchDeviceAdmin.isChecked = false
                    }
                }
            }
        }

        binding.llExtraHarden.setOnClickListener {
            if (!binding.switchExtraHarden.isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("Enable Extra Harden?")
                    .setMessage("This will make it VERY difficult to uninstall the app and you will be completely unable to open the Settings app. You may need to use adb commands to disable this if you forget your password or get locked out. Are you absolutely sure?")
                    .setPositiveButton("I UNDERSTAND & ENABLE") { _, _ ->
                        lifecycleScope.launch {
                            repo.setExtraHardenEnabled(true)
                            binding.switchExtraHarden.isChecked = true
                        }
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            } else {
                lifecycleScope.launch {
                    PasswordDialog.verifyPassword(this@MainActivity, repo) {
                        lifecycleScope.launch {
                            repo.setExtraHardenEnabled(false)
                            binding.switchExtraHarden.isChecked = false
                        }
                    }
                }
            }
        }

        binding.btnCreateRule.setOnClickListener {
            startActivity(Intent(this, CreateRuleActivity::class.java))
        }

        binding.btnCreateInstantFocus.setOnClickListener {
            startActivity(Intent(this, InstantFocusActivity::class.java))
        }

        binding.btnInstantFocus30m.setOnClickListener {
            launchQuickFocus(30L)
        }

        binding.btnInstantFocus1h.setOnClickListener {
            launchQuickFocus(60L)
        }

        binding.btnSetPassword.setOnClickListener {
            lifecycleScope.launch {
                PasswordDialog.showSetPasswordDialog(this@MainActivity, repo)
            }
        }
    }

    private fun launchQuickFocus(minutes: Long) {
        val intent = Intent(this, InstantFocusActivity::class.java).apply {
            putExtra(InstantFocusActivity.EXTRA_PREFILL_DURATION_MINS, minutes)
        }
        startActivity(intent)
    }

    private fun showAddFocusTimeDialog(focus: InstantFocus) {
        val options = arrayOf("+10 Minutes", "+30 Minutes", "+1 Hour", "+2 Hours", "Custom...")
        AlertDialog.Builder(this)
            .setTitle("Add Time to Focus")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addFocusTime(focus, 10L)
                    1 -> addFocusTime(focus, 30L)
                    2 -> addFocusTime(focus, 60L)
                    3 -> addFocusTime(focus, 120L)
                    4 -> TimeDialogs.showDurationOrLimitPicker(
                        context = this,
                        initialMinutes = 15L,
                        title = "ADD TIME",
                        confirmLabel = "ADD TIME",
                        showFullyBlock = false
                    ) { totalMins ->
                        if (totalMins > 0L) addFocusTime(focus, totalMins)
                    }
                }
            }
            .show()
    }

    private fun addFocusTime(focus: InstantFocus, minutes: Long) {
        lifecycleScope.launch {
            appContainer.settingsRepository.addInstantFocusTime(focus.id, minutes)
            Toast.makeText(
                this@MainActivity,
                "Added $minutes minutes to focus session",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveEditedFocusApps(newApps: List<String>) {
        val repo = appContainer.settingsRepository
        lifecycleScope.launch {
            val currentSessions = repo.settings.first().instantFocusSessions
            val oldSession = RuleEngine.findDisplayInstantFocus(
                currentSessions,
                System.currentTimeMillis()
            ) ?: return@launch

            val requiresPassword = StrictModePolicy.requiresPasswordForFocusAppsEdit(
                oldFocus = oldSession,
                newPackages = newApps.toSet()
            )

            val persist: () -> Unit = {
                lifecycleScope.launch {
                    repo.updateInstantFocusApps(oldSession.id, newApps)
                    Toast.makeText(this@MainActivity, "Focus apps updated", Toast.LENGTH_SHORT).show()
                }
            }

            if (requiresPassword) {
                PasswordDialog.verifyPassword(this@MainActivity, repo) { persist() }
            } else {
                persist()
            }
        }
    }
}
