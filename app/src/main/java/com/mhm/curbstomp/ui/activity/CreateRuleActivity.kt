package com.mhm.curbstomp.ui.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mhm.curbstomp.R
import com.mhm.curbstomp.appContainer
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.databinding.ActivityCreateRuleBinding
import com.mhm.curbstomp.domain.RuleEngine
import com.mhm.curbstomp.domain.StrictModePolicy
import com.mhm.curbstomp.ui.adapter.AppLimitAdapter
import com.mhm.curbstomp.ui.common.PasswordDialog
import com.mhm.curbstomp.ui.common.TimeDialogs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

class CreateRuleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDIT_RULE_ID = "EDIT_RULE_ID"
        private const val STATE_START_TIME = "state_start_time"
        private const val STATE_END_TIME = "state_end_time"
        private const val STATE_IS_WHITELIST = "state_is_whitelist"
        private const val STATE_APP_PACKAGES = "state_app_packages"
        private const val STATE_APP_LIMITS = "state_app_limits"
    }

    private lateinit var binding: ActivityCreateRuleBinding
    private lateinit var appLimitAdapter: AppLimitAdapter

    private var editingRuleId: String? = null
    private var startTimeStr: String = "00:00"
    private var endTimeStr: String = "23:59"
    private var isWhitelist: Boolean = false
    private val selectedAppsMap = linkedMapOf<String, Long>()

    private val selectAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val apps = result.data?.getStringArrayListExtra(SelectAppsActivity.EXTRA_SELECTED_APPS)
                    ?: return@registerForActivityResult
                val updated = linkedMapOf<String, Long>()
                for (app in apps) {
                    updated[app] = selectedAppsMap[app] ?: 0L
                }
                selectedAppsMap.clear()
                selectedAppsMap.putAll(updated)
                refreshAppsList()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateRuleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingRuleId = intent.getStringExtra(EXTRA_EDIT_RULE_ID)

        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        if (editingRuleId != null) {
            binding.toolbar.setTitle(R.string.title_edit_rule)
        }

        appLimitAdapter = AppLimitAdapter(
            scope = lifecycleScope,
            iconLoader = appContainer.appIconLoader,
            showLimits = true,
            onRemove = { packageName ->
                selectedAppsMap.remove(packageName)
                refreshAppsList()
            },
            onSetLimitClick = { packageName, currentLimitMillis ->
                TimeDialogs.showDurationOrLimitPicker(
                    context = this,
                    initialMinutes = currentLimitMillis / 60_000L,
                    showFullyBlock = true
                ) { totalMinutes ->
                    selectedAppsMap[packageName] = totalMinutes * 60_000L
                    refreshAppsList()
                }
            }
        )
        binding.rvSelectedApps.layoutManager = LinearLayoutManager(this)
        binding.rvSelectedApps.adapter = appLimitAdapter

        setupListeners()
        syncFormUi()

        if (savedInstanceState == null && editingRuleId != null) {
            loadExistingRule(editingRuleId!!)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_START_TIME, startTimeStr)
        outState.putString(STATE_END_TIME, endTimeStr)
        outState.putBoolean(STATE_IS_WHITELIST, isWhitelist)
        outState.putStringArrayList(STATE_APP_PACKAGES, ArrayList(selectedAppsMap.keys))
        outState.putLongArray(STATE_APP_LIMITS, selectedAppsMap.values.toLongArray())
    }

    private fun restoreState(state: Bundle) {
        startTimeStr = state.getString(STATE_START_TIME, "00:00")
        endTimeStr = state.getString(STATE_END_TIME, "23:59")
        isWhitelist = state.getBoolean(STATE_IS_WHITELIST, false)
        val packages = state.getStringArrayList(STATE_APP_PACKAGES).orEmpty()
        val limits = state.getLongArray(STATE_APP_LIMITS) ?: LongArray(0)
        selectedAppsMap.clear()
        for (i in packages.indices) {
            selectedAppsMap[packages[i]] = limits.getOrElse(i) { 0L }
        }
    }

    private fun setupListeners() {
        binding.btnStartTime.setOnClickListener {
            TimeDialogs.showClockPicker(this, startTimeStr) { time ->
                startTimeStr = time
                syncTimeButtons()
            }
        }

        binding.btnEndTime.setOnClickListener {
            TimeDialogs.showClockPicker(this, endTimeStr) { time ->
                endTimeStr = time
                syncTimeButtons()
            }
        }

        binding.cardAllowOnly.setOnClickListener {
            isWhitelist = true
            syncModeRadios()
        }

        binding.cardBlockThese.setOnClickListener {
            isWhitelist = false
            syncModeRadios()
        }

        binding.btnAddApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java).apply {
                putStringArrayListExtra(
                    SelectAppsActivity.EXTRA_PRE_SELECTED_APPS,
                    ArrayList(selectedAppsMap.keys)
                )
            }
            selectAppsLauncher.launch(intent)
        }

        binding.btnSave.setOnClickListener {
            saveRule()
        }
    }

    private fun syncFormUi() {
        syncTimeButtons()
        syncModeRadios()
        refreshAppsList()
    }

    private fun syncTimeButtons() {
        binding.btnStartTime.text = getString(
            R.string.rule_start_time,
            RuleEngine.formatTime12Hour(startTimeStr)
        )
        binding.btnEndTime.text = getString(
            R.string.rule_end_time,
            RuleEngine.formatTime12Hour(endTimeStr)
        )
    }

    private fun syncModeRadios() {
        binding.rbAllowOnly.isChecked = isWhitelist
        binding.rbBlockThese.isChecked = !isWhitelist
    }

    private fun refreshAppsList() {
        appLimitAdapter.submitList(selectedAppsMap.map { AppLimit(it.key, it.value) })
    }

    private fun loadExistingRule(ruleId: String) {
        lifecycleScope.launch {
            val rule = appContainer.settingsRepository.settings.first().rules
                .find { it.id == ruleId } ?: return@launch

            binding.etRuleName.setText(rule.name)
            startTimeStr = rule.startTime
            endTimeStr = rule.endTime
            isWhitelist = rule.isWhitelist

            binding.chipSun.isChecked = rule.daysOfWeek.contains(Calendar.SUNDAY)
            binding.chipMon.isChecked = rule.daysOfWeek.contains(Calendar.MONDAY)
            binding.chipTue.isChecked = rule.daysOfWeek.contains(Calendar.TUESDAY)
            binding.chipWed.isChecked = rule.daysOfWeek.contains(Calendar.WEDNESDAY)
            binding.chipThu.isChecked = rule.daysOfWeek.contains(Calendar.THURSDAY)
            binding.chipFri.isChecked = rule.daysOfWeek.contains(Calendar.FRIDAY)
            binding.chipSat.isChecked = rule.daysOfWeek.contains(Calendar.SATURDAY)

            selectedAppsMap.clear()
            for (limit in rule.appLimits) {
                selectedAppsMap[limit.packageName] = limit.maxUsageMillis
            }
            syncFormUi()
        }
    }

    private fun getSelectedDays(): List<Int> {
        val days = mutableListOf<Int>()
        if (binding.chipSun.isChecked) days.add(Calendar.SUNDAY)
        if (binding.chipMon.isChecked) days.add(Calendar.MONDAY)
        if (binding.chipTue.isChecked) days.add(Calendar.TUESDAY)
        if (binding.chipWed.isChecked) days.add(Calendar.WEDNESDAY)
        if (binding.chipThu.isChecked) days.add(Calendar.THURSDAY)
        if (binding.chipFri.isChecked) days.add(Calendar.FRIDAY)
        if (binding.chipSat.isChecked) days.add(Calendar.SATURDAY)
        return days
    }

    private fun saveRule() {
        val name = binding.etRuleName.text?.toString().orEmpty()
        if (name.isEmpty()) {
            binding.etRuleName.error = getString(R.string.error_rule_name_required)
            return
        }

        val days = getSelectedDays()
        if (days.isEmpty()) {
            Toast.makeText(this, R.string.toast_select_one_day, Toast.LENGTH_SHORT).show()
            return
        }

        val newRule = Rule(
            id = editingRuleId ?: UUID.randomUUID().toString(),
            name = name,
            startTime = startTimeStr,
            endTime = endTimeStr,
            daysOfWeek = days,
            appLimits = selectedAppsMap.map { AppLimit(it.key, it.value) },
            isWhitelist = isWhitelist,
            isActive = true
        )

        val repo = appContainer.settingsRepository
        lifecycleScope.launch {
            val oldRule = editingRuleId?.let { id ->
                repo.settings.first().rules.find { it.id == id }
            }
            val requiresPassword = oldRule != null &&
                StrictModePolicy.requiresPasswordForRuleEdit(oldRule, newRule)

            val persist: () -> Unit = {
                lifecycleScope.launch {
                    repo.upsertRule(newRule)
                    Toast.makeText(this@CreateRuleActivity, R.string.toast_rule_saved, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

            if (requiresPassword) {
                PasswordDialog.verifyPassword(this@CreateRuleActivity, repo) { persist() }
            } else {
                persist()
            }
        }
    }
}
