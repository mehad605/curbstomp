package com.mhm.curbstomp.ui.activity

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.mhm.curbstomp.R
import com.mhm.curbstomp.appContainer
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.data.models.Settings
import com.mhm.curbstomp.databinding.ActivityMainBinding
import com.mhm.curbstomp.domain.RuleEngine
import com.mhm.curbstomp.receivers.CurbstompDeviceAdminReceiver
import com.mhm.curbstomp.services.CurbstompService
import com.mhm.curbstomp.ui.adapter.RuleAdapter
import com.mhm.curbstomp.ui.adapter.RuleListItem
import com.mhm.curbstomp.ui.common.ActiveFocusCardController
import com.mhm.curbstomp.ui.common.PasswordDialog
import com.mhm.curbstomp.ui.common.PermissionUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ruleAdapter: RuleAdapter
    private lateinit var focusCardController: ActiveFocusCardController

    private val editFocusAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val apps = result.data?.getStringArrayListExtra(SelectAppsActivity.EXTRA_SELECTED_APPS)
                    ?: return@registerForActivityResult
                focusCardController.saveEditedFocusApps(apps)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Curbstomp)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = appContainer.settingsRepository
        focusCardController = ActiveFocusCardController(
            context = this,
            binding = binding,
            scope = lifecycleScope,
            settingsRepository = repo,
            iconLoader = appContainer.appIconLoader,
            editFocusAppsLauncher = editFocusAppsLauncher
        )

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
        focusCardController.onStop()
    }

    override fun onDestroy() {
        focusCardController.onDestroy()
        super.onDestroy()
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
            listItems.add(RuleListItem.Header(getString(R.string.rules_header_active)))
            for (rule in activeRules) listItems.add(RuleListItem.RuleItem(rule))
        }
        if (inactiveRules.isNotEmpty()) {
            listItems.add(RuleListItem.Header(getString(R.string.rules_header_inactive)))
            for (rule in inactiveRules) listItems.add(RuleListItem.RuleItem(rule))
        }

        ruleAdapter.submitList(
            newItems = listItems,
            usageMap = settings.appUsageMillis,
            minuteOfDay = minuteOfDay,
            todayKey = todayKey,
            yesterdayKey = yesterdayKey
        )

        binding.btnSetPassword.setText(
            if (!settings.passwordHash.isNullOrEmpty()) {
                R.string.action_update_password
            } else {
                R.string.action_set_password
            }
        )

        focusCardController.render(activeFocus)
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

    private fun setupListeners() {
        val repo = appContainer.settingsRepository

        binding.rowAccessibility.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.rowDeviceAdmin.setOnClickListener {
            if (!binding.switchDeviceAdmin.isChecked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_enable_admin_title)
                    .setMessage(R.string.dialog_enable_admin_message)
                    .setPositiveButton(R.string.action_understand_and_enable) { _, _ ->
                        lifecycleScope.launch {
                            repo.markDeviceAdminActivationRequested()
                            PermissionUtils.requestDeviceAdmin(
                                context = this@MainActivity,
                                adminClass = CurbstompDeviceAdminReceiver::class.java,
                                explanation = getString(R.string.admin_permission_explanation)
                            )
                        }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            } else {
                lifecycleScope.launch {
                    PasswordDialog.verifyPassword(this@MainActivity, repo) {
                        PermissionUtils.removeDeviceAdmin(
                            this@MainActivity,
                            CurbstompDeviceAdminReceiver::class.java
                        )
                        binding.switchDeviceAdmin.isChecked = false
                        lifecycleScope.launch { repo.setAntiUninstallEnabled(false) }
                    }
                }
            }
        }

        binding.llExtraHarden.setOnClickListener {
            if (!binding.switchExtraHarden.isChecked) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_enable_extra_harden_title)
                    .setMessage(R.string.dialog_enable_extra_harden_message)
                    .setPositiveButton(R.string.action_understand_and_enable) { _, _ ->
                        lifecycleScope.launch {
                            repo.setExtraHardenEnabled(true)
                            binding.switchExtraHarden.isChecked = true
                        }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
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
}
