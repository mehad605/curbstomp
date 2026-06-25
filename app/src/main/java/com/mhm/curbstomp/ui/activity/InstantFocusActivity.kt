package com.mhm.curbstomp.ui.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mhm.curbstomp.R
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.databinding.ActivityInstantFocusBinding
import com.mhm.curbstomp.databinding.ItemAppLimitBinding
import com.mhm.curbstomp.utils.DataStoreManager
import com.mhm.curbstomp.utils.PasswordDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.UUID

class InstantFocusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstantFocusBinding
    private lateinit var dataStoreManager: DataStoreManager
    
    private val selectedAppsMap = mutableMapOf<String, Long>()
    private val appIconCache = mutableMapOf<String, Drawable>()
    private val appLabelCache = mutableMapOf<String, String>()

    private var durationMins = 0L
    private var isWhitelist = true
    private var editingFocusId: String? = null

    private val selectAppsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
            val newMap = mutableMapOf<String, Long>()
            for (app in apps) {
                newMap[app] = selectedAppsMap[app] ?: 0L
            }
            selectedAppsMap.clear()
            selectedAppsMap.putAll(newMap)
            binding.rvSelectedApps.adapter?.notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstantFocusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataStoreManager = DataStoreManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rvSelectedApps.layoutManager = LinearLayoutManager(this)
        binding.rvSelectedApps.adapter = AppLimitAdapter()

        binding.btnAddApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java).apply {
                putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(selectedAppsMap.keys))
            }
            selectAppsLauncher.launch(intent)
        }

        binding.cvDuration.setOnClickListener {
            showDurationPicker()
        }

        binding.btnStart.setOnClickListener {
            showModeSelectionDialog()
        }

        editingFocusId = intent.getStringExtra("EDIT_FOCUS_ID")
        val prefillMins = intent.getLongExtra("PREFILL_DURATION_MINS", 0L)
        
        if (editingFocusId != null) {
            binding.toolbar.title = "Edit Focus"
            binding.btnStart.text = "SAVE FOCUS"
            loadExistingFocus(editingFocusId!!)
        } else if (prefillMins > 0) {
            updateDurationDisplay(prefillMins)
        } else {
            // Default 0 for Custom. Open duration picker automatically.
            updateDurationDisplay(0)
            showDurationPicker()
        }
    }

    private fun updateDurationDisplay(mins: Long) {
        durationMins = mins
        val h = mins / 60
        val m = mins % 60
        binding.tvDurationDisplay.text = if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun showDurationPicker() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_time_picker, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        val npHours = dialogView.findViewById<NumberPicker>(R.id.npHours)
        val npMinutes = dialogView.findViewById<NumberPicker>(R.id.npMinutes)
        val btnFullyBlock = dialogView.findViewById<View>(R.id.btnFullyBlock)
        val btnSaveLimit = dialogView.findViewById<View>(R.id.btnSaveLimit)
        
        // Hide fully block button since it makes no sense here
        btnFullyBlock.visibility = View.GONE

        npHours.minValue = 0
        npHours.maxValue = 23
        npHours.value = (durationMins / 60).toInt()

        npMinutes.minValue = 0
        npMinutes.maxValue = 59
        npMinutes.value = (durationMins % 60).toInt()

        btnSaveLimit.setOnClickListener {
            val totalMins = (npHours.value * 60) + npMinutes.value
            updateDurationDisplay(totalMins.toLong())
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showModeSelectionDialog() {
        if (durationMins <= 0) {
            Toast.makeText(this, "Please set a valid duration", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedAppsMap.isEmpty()) {
            Toast.makeText(this, "Select at least one app", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("Allow Only These", "Block These")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("What should happen to these apps?")
            .setItems(options) { _, which ->
                isWhitelist = (which == 0)
                startFocus()
            }
            .show()
    }

    private fun loadExistingFocus(focusId: String) {
        lifecycleScope.launch {
            val sessions = dataStoreManager.settings.first().instantFocusSessions
            val focus = sessions.find { it.id == focusId } ?: return@launch
            
            withContext(Dispatchers.Main) {
                updateDurationDisplay((focus.endTimeMillis - focus.startTimeMillis) / 60000)
                isWhitelist = focus.isWhitelist
                
                selectedAppsMap.clear()
                for (limit in focus.appLimits) {
                    selectedAppsMap[limit.packageName] = 0L // Ignore old limits
                }
                binding.rvSelectedApps.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun startFocus() {

        val appLimits = selectedAppsMap.map { AppLimit(it.key, 0L) }

        val startTime = System.currentTimeMillis()
        val endTime = startTime + (durationMins * 60000)

        val focus = InstantFocus(
            id = editingFocusId ?: UUID.randomUUID().toString(),
            startTimeMillis = startTime,
            endTimeMillis = endTime,
            appLimits = appLimits,
            isActive = true,
            isWhitelist = isWhitelist
        )

        lifecycleScope.launch {
            val currentSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
            var requiresPassword = false
            
            if (editingFocusId != null) {
                val index = currentSessions.indexOfFirst { it.id == editingFocusId }
                if (index != -1) {
                    val oldSession = currentSessions[index]
                    if (oldSession.isActive) {
                        val oldDurationMins = (oldSession.endTimeMillis - oldSession.startTimeMillis) / 60000
                        if (durationMins < oldDurationMins) {
                            requiresPassword = true // Cheating by making focus session shorter
                        }
                        if (oldSession.isWhitelist != isWhitelist) {
                            requiresPassword = true // Changing mode might be cheating
                        } else {
                            val oldApps = oldSession.appLimits.map { it.packageName }.toSet()
                            val newApps = selectedAppsMap.keys
                            val addedApps = newApps.filter { it !in oldApps }
                            val removedApps = oldApps.filter { it !in newApps }

                            if (isWhitelist) {
                                // Allow list mode: adding apps to allowed list is less strict -> password needed
                                if (addedApps.isNotEmpty()) {
                                    requiresPassword = true
                                }
                            } else {
                                // Block list mode: removing apps from blocked list is less strict -> password needed
                                if (removedApps.isNotEmpty()) {
                                    requiresPassword = true
                                }
                            }
                        }
                    }
                }
            }

            val doSave = {
                lifecycleScope.launch {
                    val updatedSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                    if (editingFocusId != null) {
                        val index = updatedSessions.indexOfFirst { it.id == editingFocusId }
                        if (index != -1) {
                            updatedSessions[index] = focus
                        } else {
                            updatedSessions.add(focus)
                        }
                    } else {
                        updatedSessions.add(focus)
                    }
                    dataStoreManager.updateInstantFocusSessions(updatedSessions)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@InstantFocusActivity, "Instant Focus Saved", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (requiresPassword) {
                    PasswordDialog.verifyPassword(this@InstantFocusActivity, dataStoreManager) { doSave() }
                } else {
                    doSave()
                }
            }
        }
    }

    private fun getAppInfo(packageName: String): Pair<String, Drawable?> {
        if (appLabelCache.containsKey(packageName)) {
            return Pair(appLabelCache[packageName]!!, appIconCache[packageName])
        }
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(info).toString()
            val icon = pm.getApplicationIcon(info)
            appLabelCache[packageName] = label
            appIconCache[packageName] = icon
            Pair(label, icon)
        } catch (e: PackageManager.NameNotFoundException) {
            Pair(packageName, null)
        }
    }

    inner class AppLimitAdapter : RecyclerView.Adapter<AppLimitAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemAppLimitBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppLimitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val packageName = selectedAppsMap.keys.elementAt(position)
            
            val (label, icon) = getAppInfo(packageName)
            holder.binding.tvAppName.text = label
            holder.binding.ivAppIcon.setImageDrawable(icon)
            
            holder.binding.tvAppLimit.visibility = View.GONE
            holder.binding.btnSetLimit.visibility = View.GONE // Removed individual limits here

            holder.binding.btnRemove.setOnClickListener {
                selectedAppsMap.remove(packageName)
                notifyDataSetChanged()
            }
        }

        override fun getItemCount(): Int = selectedAppsMap.size
    }
}
