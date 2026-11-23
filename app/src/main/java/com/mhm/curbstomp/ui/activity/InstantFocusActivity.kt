package com.mhm.curbstomp.ui.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mhm.curbstomp.R
import com.mhm.curbstomp.appContainer
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.databinding.ActivityInstantFocusBinding
import com.mhm.curbstomp.domain.StrictModePolicy
import com.mhm.curbstomp.ui.adapter.AppLimitAdapter
import com.mhm.curbstomp.ui.common.PasswordDialog
import com.mhm.curbstomp.ui.common.TimeDialogs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class InstantFocusActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDIT_FOCUS_ID = "EDIT_FOCUS_ID"
        const val EXTRA_PREFILL_DURATION_MINS = "PREFILL_DURATION_MINS"
        private const val STATE_DURATION_MINS = "state_duration_mins"
        private const val STATE_IS_WHITELIST = "state_is_whitelist"
        private const val STATE_SELECTED_APPS = "state_selected_apps"
    }

    private lateinit var binding: ActivityInstantFocusBinding
    private lateinit var appLimitAdapter: AppLimitAdapter

    private val selectedPackages = linkedSetOf<String>()
    private var durationMins: Long = 0L
    private var isWhitelist: Boolean = true
    private var editingFocusId: String? = null

    private val selectAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val apps = result.data?.getStringArrayListExtra(SelectAppsActivity.EXTRA_SELECTED_APPS)
                    ?: return@registerForActivityResult
                selectedPackages.clear()
                selectedPackages.addAll(apps)
                refreshAppsList()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstantFocusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingFocusId = intent.getStringExtra(EXTRA_EDIT_FOCUS_ID)

        binding.toolbar.setNavigationOnClickListener { finish() }

        appLimitAdapter = AppLimitAdapter(
            scope = lifecycleScope,
            iconLoader = appContainer.appIconLoader,
            showLimits = false,
            onRemove = { packageName ->
                selectedPackages.remove(packageName)
                refreshAppsList()
            }
        )
        binding.rvSelectedApps.layoutManager = LinearLayoutManager(this)
        binding.rvSelectedApps.adapter = appLimitAdapter

        binding.btnAddApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java).apply {
                putStringArrayListExtra(
                    SelectAppsActivity.EXTRA_PRE_SELECTED_APPS,
                    ArrayList(selectedPackages)
                )
            }
            selectAppsLauncher.launch(intent)
        }

        binding.cvDuration.setOnClickListener {
            showDurationPicker()
        }

        binding.btnStart.setOnClickListener {
            showModeSelectionDialog()
        }

        if (editingFocusId != null) {
            binding.toolbar.setTitle(R.string.title_edit_focus)
            binding.btnStart.setText(R.string.action_save_focus)
        }

        if (savedInstanceState != null) {
            durationMins = savedInstanceState.getLong(STATE_DURATION_MINS, 0L)
            isWhitelist = savedInstanceState.getBoolean(STATE_IS_WHITELIST, true)
            selectedPackages.clear()
            selectedPackages.addAll(savedInstanceState.getStringArrayList(STATE_SELECTED_APPS).orEmpty())
            updateDurationDisplay(durationMins)
            refreshAppsList()
        } else {
            val prefillMins = intent.getLongExtra(EXTRA_PREFILL_DURATION_MINS, 0L)
            when {
                editingFocusId != null -> loadExistingFocus(editingFocusId!!)
                prefillMins > 0L -> updateDurationDisplay(prefillMins)
                else -> {
                    updateDurationDisplay(0L)
                    showDurationPicker()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_DURATION_MINS, durationMins)
        outState.putBoolean(STATE_IS_WHITELIST, isWhitelist)
        outState.putStringArrayList(STATE_SELECTED_APPS, ArrayList(selectedPackages))
    }

    private fun refreshAppsList() {
        appLimitAdapter.submitList(selectedPackages.map { AppLimit(it, 0L) })
    }

    private fun updateDurationDisplay(mins: Long) {
        durationMins = mins
        val h = mins / 60L
        val m = mins % 60L
        binding.tvDurationDisplay.text = if (h > 0L) {
            getString(R.string.duration_hours_minutes, h, m)
        } else {
            getString(R.string.duration_minutes_only, m)
        }
    }

    private fun showDurationPicker() {
        TimeDialogs.showDurationOrLimitPicker(
            context = this,
            initialMinutes = durationMins,
            showFullyBlock = false
        ) { totalMins ->
            updateDurationDisplay(totalMins)
        }
    }

    private fun showModeSelectionDialog() {
        if (durationMins <= 0L) {
            Toast.makeText(this, R.string.toast_valid_duration, Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedPackages.isEmpty()) {
            Toast.makeText(this, R.string.toast_select_one_app, Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            getString(R.string.protocol_allow_only_title),
            getString(R.string.protocol_block_these_title)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_focus_mode_title)
            .setItems(options) { _, which ->
                isWhitelist = (which == 0)
                saveFocusSession()
            }
            .show()
    }

    private fun loadExistingFocus(focusId: String) {
        lifecycleScope.launch {
            val focus = appContainer.settingsRepository.settings.first().instantFocusSessions
                .find { it.id == focusId } ?: return@launch

            updateDurationDisplay((focus.endTimeMillis - focus.startTimeMillis) / 60_000L)
            isWhitelist = focus.isWhitelist
            selectedPackages.clear()
            for (limit in focus.appLimits) {
                selectedPackages.add(limit.packageName)
            }
            refreshAppsList()
        }
    }

    private fun saveFocusSession() {
        val now = System.currentTimeMillis()
        val newFocus = InstantFocus(
            id = editingFocusId ?: UUID.randomUUID().toString(),
            startTimeMillis = now,
            endTimeMillis = now + (durationMins * 60_000L),
            appLimits = selectedPackages.map { AppLimit(it, 0L) },
            isActive = true,
            isWhitelist = isWhitelist
        )

        val repo = appContainer.settingsRepository
        lifecycleScope.launch {
            val oldFocus = editingFocusId?.let { id ->
                repo.settings.first().instantFocusSessions.find { it.id == id }
            }
            val requiresPassword = oldFocus != null && StrictModePolicy.requiresPasswordForFocusEdit(
                oldFocus = oldFocus,
                newDurationMins = durationMins,
                newIsWhitelist = isWhitelist,
                newPackages = selectedPackages
            )

            val persist: () -> Unit = {
                lifecycleScope.launch {
                    repo.upsertInstantFocus(newFocus)
                    Toast.makeText(
                        this@InstantFocusActivity,
                        R.string.toast_instant_focus_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }

            if (requiresPassword) {
                PasswordDialog.verifyPassword(this@InstantFocusActivity, repo) { persist() }
            } else {
                persist()
            }
        }
    }
}
