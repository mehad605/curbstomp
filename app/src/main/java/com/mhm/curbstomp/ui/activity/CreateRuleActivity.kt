package com.mhm.curbstomp.ui.activity

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.databinding.ActivityCreateRuleBinding
import com.mhm.curbstomp.databinding.ItemAppLimitBinding
import com.mhm.curbstomp.utils.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar
import java.util.UUID

class CreateRuleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateRuleBinding
    private lateinit var dataStoreManager: DataStoreManager
    
    private var startTimeStr = "00:00"
    private var endTimeStr = "23:59"
    private val selectedAppsMap = mutableMapOf<String, Long>()
    
    private val appIconCache = mutableMapOf<String, Drawable>()
    private val appLabelCache = mutableMapOf<String, String>()

    private val selectAppsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
            val newMap = mutableMapOf<String, Long>()
            for (app in apps) {
                newMap[app] = selectedAppsMap[app] ?: 0L
            }
            selectedAppsMap.clear()
            selectedAppsMap.putAll(newMap)
            (binding.rvSelectedApps.adapter as? AppLimitAdapter)?.notifyDataSetChanged()
        }
    }

    private var editingRuleId: String? = null
    private var isWhitelist: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateRuleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataStoreManager = DataStoreManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnStartTime.text = "Start: ${formatTime12Hour(startTimeStr)}"
        binding.btnEndTime.text = "End: ${formatTime12Hour(endTimeStr)}"

        binding.btnStartTime.setOnClickListener {
            showTimePicker(startTimeStr) { time ->
                startTimeStr = time
                binding.btnStartTime.text = "Start: ${formatTime12Hour(startTimeStr)}"
            }
        }

        binding.btnEndTime.setOnClickListener {
            showTimePicker(endTimeStr) { time ->
                endTimeStr = time
                binding.btnEndTime.text = "End: ${formatTime12Hour(endTimeStr)}"
            }
        }
        
        binding.rbBlockThese.isChecked = !isWhitelist
        binding.rbAllowOnly.isChecked = isWhitelist
        
        binding.cardAllowOnly.setOnClickListener {
            isWhitelist = true
            binding.rbAllowOnly.isChecked = true
            binding.rbBlockThese.isChecked = false
        }
        
        binding.cardBlockThese.setOnClickListener {
            isWhitelist = false
            binding.rbAllowOnly.isChecked = false
            binding.rbBlockThese.isChecked = true
        }

        binding.rvSelectedApps.layoutManager = LinearLayoutManager(this)
        binding.rvSelectedApps.adapter = AppLimitAdapter()

        binding.btnAddApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java).apply {
                putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(selectedAppsMap.keys))
            }
            selectAppsLauncher.launch(intent)
        }

        binding.btnSave.setOnClickListener {
            saveRule()
        }

        editingRuleId = intent.getStringExtra("EDIT_RULE_ID")
        if (editingRuleId != null) {
            binding.toolbar.title = "Edit Rule"
            loadExistingRule(editingRuleId!!)
        }
    }

    private fun loadExistingRule(ruleId: String) {
        lifecycleScope.launch {
            val rules = dataStoreManager.settings.first().rules
            val rule = rules.find { it.id == ruleId } ?: return@launch
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                binding.etRuleName.setText(rule.name)
                startTimeStr = rule.startTime
                endTimeStr = rule.endTime
                binding.btnStartTime.text = "Start: ${formatTime12Hour(startTimeStr)}"
                binding.btnEndTime.text = "End: ${formatTime12Hour(endTimeStr)}"
                
                isWhitelist = rule.isWhitelist
                binding.rbAllowOnly.isChecked = isWhitelist
                binding.rbBlockThese.isChecked = !isWhitelist
                
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
                binding.rvSelectedApps.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun showTimePicker(currentTime: String, onTimeSet: (String) -> Unit) {
        val parts = currentTime.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(com.mhm.curbstomp.R.layout.dialog_time_selection, null)
        dialog.setContentView(dialogView)

        val npHours = dialogView.findViewById<android.widget.NumberPicker>(com.mhm.curbstomp.R.id.npHours)
        val npMinutes = dialogView.findViewById<android.widget.NumberPicker>(com.mhm.curbstomp.R.id.npMinutes)
        val npAmPm = dialogView.findViewById<android.widget.NumberPicker>(com.mhm.curbstomp.R.id.npAmPm)
        val btnSaveTime = dialogView.findViewById<android.widget.Button>(com.mhm.curbstomp.R.id.btnSaveTime)

        npHours.minValue = 1
        npHours.maxValue = 12
        
        npMinutes.minValue = 0
        npMinutes.maxValue = 59
        npMinutes.setFormatter { String.format("%02d", it) }

        npAmPm.minValue = 0
        npAmPm.maxValue = 1
        npAmPm.displayedValues = arrayOf("AM", "PM")

        // Parse 24hr to 12hr for picker initialization
        val isPm = h >= 12
        npAmPm.value = if (isPm) 1 else 0
        
        var displayHour = h % 12
        if (displayHour == 0) displayHour = 12
        npHours.value = displayHour
        
        npMinutes.value = m

        btnSaveTime.setOnClickListener {
            val selectedIsPm = npAmPm.value == 1
            var selectedHour = npHours.value
            if (selectedIsPm && selectedHour != 12) {
                selectedHour += 12
            } else if (!selectedIsPm && selectedHour == 12) {
                selectedHour = 0
            }

            val formattedTime = String.format("%02d:%02d", selectedHour, npMinutes.value)
            onTimeSet(formattedTime)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun formatTime12Hour(time24: String): String {
        return try {
            val sdf24 = SimpleDateFormat("HH:mm", Locale.getDefault())
            val sdf12 = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val date = sdf24.parse(time24)
            if (date != null) sdf12.format(date) else time24
        } catch (e: Exception) {
            time24
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
        val name = binding.etRuleName.text.toString()
        if (name.isEmpty()) {
            binding.etRuleName.error = "Name required"
            return
        }

        val days = getSelectedDays()
        if (days.isEmpty()) {
            Toast.makeText(this, "Select at least one repeating day", Toast.LENGTH_SHORT).show()
            return
        }

        val appLimits = selectedAppsMap.map { AppLimit(it.key, it.value) }

        val rule = Rule(
            id = editingRuleId ?: UUID.randomUUID().toString(),
            name = name,
            startTime = startTimeStr,
            endTime = endTimeStr,
            daysOfWeek = days,
            appLimits = appLimits,
            isWhitelist = isWhitelist,
            isActive = true
        )

        lifecycleScope.launch {
            val currentRules = dataStoreManager.settings.first().rules.toMutableList()
            var requiresPassword = false
            
            if (editingRuleId != null) {
                val index = currentRules.indexOfFirst { it.id == editingRuleId }
                if (index != -1) {
                    val oldRule = currentRules[index]
                    if (oldRule.isActive) {
                        if (oldRule.isWhitelist != rule.isWhitelist) {
                            requiresPassword = true // changing protocol requires password
                        } else {
                            // Check if overall rule duration decreased
                            fun getMins(timeStr: String): Int {
                                val p = timeStr.split(":")
                                return p[0].toInt() * 60 + p[1].toInt()
                            }
                            fun getDur(start: String, end: String): Int {
                                val s = getMins(start)
                                val e = getMins(end)
                                return if (e >= s) e - s else (e + 24 * 60) - s
                            }
                            val oldDur = getDur(oldRule.startTime, oldRule.endTime)
                            val newDur = getDur(rule.startTime, rule.endTime)
                            if (newDur < oldDur) {
                                requiresPassword = true // Rule window shortened (less strict)
                            }
    
                            if (rule.isWhitelist) {
                                // Whitelist: Adding an app makes it less strict. Removing makes it more strict.
                                for (newLimit in appLimits) {
                                    val oldLimit = oldRule.appLimits.find { it.packageName == newLimit.packageName }
                                    if (oldLimit == null) {
                                        requiresPassword = true // App added to whitelist (allowed)
                                        break
                                    } else if (newLimit.maxUsageMillis > oldLimit.maxUsageMillis) {
                                        requiresPassword = true // Time limit increased
                                        break
                                    }
                                }
                            } else {
                                // Blacklist: Removing an app makes it less strict. Adding makes it more strict.
                                for (oldLimit in oldRule.appLimits) {
                                    val newLimit = selectedAppsMap[oldLimit.packageName]
                                    if (newLimit == null) {
                                        requiresPassword = true // App removed from blacklist (allowed)
                                        break
                                    } else if (newLimit > oldLimit.maxUsageMillis) {
                                        requiresPassword = true // Time limit increased
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val doSave = {
                lifecycleScope.launch {
                    val updatedRules = dataStoreManager.settings.first().rules.toMutableList()
                    if (editingRuleId != null) {
                        val index = updatedRules.indexOfFirst { it.id == editingRuleId }
                        if (index != -1) {
                            updatedRules[index] = rule
                        } else {
                            updatedRules.add(rule)
                        }
                    } else {
                        updatedRules.add(rule)
                    }
                    dataStoreManager.updateRules(updatedRules)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@CreateRuleActivity, "Rule Saved", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (requiresPassword) {
                    com.mhm.curbstomp.utils.PasswordDialog.verifyPassword(this@CreateRuleActivity, dataStoreManager) { doSave() }
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
            val limitMillis = selectedAppsMap[packageName] ?: 0L
            val limitMinutes = limitMillis / 60000

            val (label, icon) = getAppInfo(packageName)
            holder.binding.tvAppName.text = label
            holder.binding.ivAppIcon.setImageDrawable(icon)
            
            if (limitMinutes == 0L) {
                holder.binding.tvAppLimit.text = "Blocked (0 min)"
            } else {
                holder.binding.tvAppLimit.text = "Limit: $limitMinutes min"
            }

            holder.binding.btnRemove.setOnClickListener {
                selectedAppsMap.remove(packageName)
                notifyDataSetChanged()
            }

            holder.binding.btnSetLimit.setOnClickListener {
                val dialogView = LayoutInflater.from(this@CreateRuleActivity).inflate(com.mhm.curbstomp.R.layout.dialog_time_picker, null)
                val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this@CreateRuleActivity)
                dialog.setContentView(dialogView)

                val npHours = dialogView.findViewById<android.widget.NumberPicker>(com.mhm.curbstomp.R.id.npHours)
                val npMinutes = dialogView.findViewById<android.widget.NumberPicker>(com.mhm.curbstomp.R.id.npMinutes)
                val btnFullyBlock = dialogView.findViewById<android.view.View>(com.mhm.curbstomp.R.id.btnFullyBlock)
                val btnSaveLimit = dialogView.findViewById<android.view.View>(com.mhm.curbstomp.R.id.btnSaveLimit)

                npHours.minValue = 0
                npHours.maxValue = 23
                npHours.value = (limitMinutes / 60).toInt()

                npMinutes.minValue = 0
                npMinutes.maxValue = 59
                npMinutes.value = (limitMinutes % 60).toInt()

                btnFullyBlock.setOnClickListener {
                    selectedAppsMap[packageName] = 0L
                    notifyItemChanged(position)
                    dialog.dismiss()
                }

                btnSaveLimit.setOnClickListener {
                    val totalMins = (npHours.value * 60) + npMinutes.value
                    selectedAppsMap[packageName] = totalMins * 60000L
                    notifyItemChanged(position)
                    dialog.dismiss()
                }

                dialog.show()
            }
        }

        override fun getItemCount(): Int = selectedAppsMap.size
    }
}
