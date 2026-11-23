package com.mhm.curbstomp.ui.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.databinding.ActivityInstantFocusBinding
import com.mhm.curbstomp.databinding.DialogTimePickerBinding
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
    private lateinit var appLimitAdapter: AppLimitAdapter
    
    private val selectedAppsMap = linkedMapOf<String, Long>()
    private val appIconCache = android.util.LruCache<String, Drawable>(48)
    private val appLabelCache = mutableMapOf<String, String>()

    private var durationMins = 0L
    private var isWhitelist = true
    private var editingFocusId: String? = null

    private val selectAppsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
            val newMap = linkedMapOf<String, Long>()
            for (app in apps) {
                newMap[app] = selectedAppsMap[app] ?: 0L
            }
            selectedAppsMap.clear()
            selectedAppsMap.putAll(newMap)
            appLimitAdapter.submitPackages(selectedAppsMap.keys.toList())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstantFocusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataStoreManager = DataStoreManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        appLimitAdapter = AppLimitAdapter()
        binding.rvSelectedApps.layoutManager = LinearLayoutManager(this)
        binding.rvSelectedApps.adapter = appLimitAdapter

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
        val pickerBinding = DialogTimePickerBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(pickerBinding.root)
        
        // Hide fully block button since it makes no sense here
        pickerBinding.btnFullyBlock.visibility = View.GONE

        pickerBinding.npHours.minValue = 0
        pickerBinding.npHours.maxValue = 23
        pickerBinding.npHours.value = (durationMins / 60).toInt()

        pickerBinding.npMinutes.minValue = 0
        pickerBinding.npMinutes.maxValue = 59
        pickerBinding.npMinutes.value = (durationMins % 60).toInt()

        pickerBinding.btnSaveLimit.setOnClickListener {
            val totalMins = (pickerBinding.npHours.value * 60) + pickerBinding.npMinutes.value
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
                appLimitAdapter.submitPackages(selectedAppsMap.keys.toList())
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

    override fun onDestroy() {
        appIconCache.evictAll()
        appLabelCache.clear()
        super.onDestroy()
    }

    private fun getAppInfo(packageName: String): Pair<String, Drawable?> {
        val cachedLabel = appLabelCache[packageName]
        val cachedIcon = appIconCache.get(packageName)
        if (cachedLabel != null && cachedIcon != null) {
            return Pair(cachedLabel, cachedIcon)
        }
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(info).toString()
            val icon = pm.getApplicationIcon(info)
            appLabelCache[packageName] = label
            appIconCache.put(packageName, icon)
            Pair(label, icon)
        } catch (_: PackageManager.NameNotFoundException) {
            Pair(packageName, null)
        }
    }

    inner class AppLimitAdapter : RecyclerView.Adapter<AppLimitAdapter.ViewHolder>() {

        private var items: List<String> = emptyList()

        fun submitPackages(newItems: List<String>) {
            val oldItems = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldItems[oldItemPosition] == newItems[newItemPosition]
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldItems[oldItemPosition] == newItems[newItemPosition]
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        inner class ViewHolder(val binding: ItemAppLimitBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppLimitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val packageName = items[position]

            val (label, icon) = getAppInfo(packageName)
            holder.binding.tvAppName.text = label
            holder.binding.ivAppIcon.setImageDrawable(icon)

            holder.binding.tvAppLimit.visibility = View.GONE
            holder.binding.btnSetLimit.visibility = View.GONE

            holder.binding.btnRemove.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    selectedAppsMap.remove(packageName)
                    items = items.toMutableList().apply { removeAt(pos) }
                    notifyItemRemoved(pos)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
