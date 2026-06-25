package com.mhm.curbstomp.ui.activity

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.databinding.ActivityMainBinding
import com.mhm.curbstomp.databinding.ItemRuleBinding
import com.mhm.curbstomp.utils.DataStoreManager
import com.mhm.curbstomp.utils.PasswordDialog
import com.mhm.curbstomp.utils.PermissionUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dataStoreManager: DataStoreManager
    private val ruleAdapter = RuleAdapter()

    private val editFocusAppsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
            saveEditedFocusApps(apps)
        }
    }
    
    private var activeFocusJob: kotlinx.coroutines.Job? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataStoreManager = DataStoreManager(this)

        binding.rvRules.layoutManager = LinearLayoutManager(this)
        binding.rvRules.adapter = ruleAdapter

        setupListeners()
        
        lifecycleScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                val items = mutableListOf<Any>()
                
                val currentTime = System.currentTimeMillis()
                val activeFocus = settings.instantFocusSessions.firstOrNull { it.isActive && currentTime < it.endTimeMillis }
                
                val calendar = java.util.Calendar.getInstance()
                val currentDay = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                val prevDay = if (currentDay == java.util.Calendar.SUNDAY) java.util.Calendar.SATURDAY else currentDay - 1
                val currentTimeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                
                val activeRules = mutableListOf<com.mhm.curbstomp.data.models.Rule>()
                val inactiveRules = mutableListOf<com.mhm.curbstomp.data.models.Rule>()
                
                for (rule in settings.rules) {
                    if (!rule.isActive) {
                        inactiveRules.add(rule)
                        continue
                    }
                    
                    val crossesMidnight = rule.startTime > rule.endTime
                    var ruleActiveNow = false
                    
                    if (crossesMidnight) {
                        if (currentTimeStr >= rule.startTime && rule.daysOfWeek.contains(currentDay)) {
                            ruleActiveNow = true
                        } else if (currentTimeStr <= rule.endTime && rule.daysOfWeek.contains(prevDay)) {
                            ruleActiveNow = true
                        }
                    } else {
                        if (rule.startTime == rule.endTime) {
                            if (rule.daysOfWeek.contains(currentDay)) ruleActiveNow = true
                        } else if (currentTimeStr in rule.startTime..rule.endTime && rule.daysOfWeek.contains(currentDay)) {
                            ruleActiveNow = true
                        }
                    }
                    
                    if (ruleActiveNow) {
                        activeRules.add(rule)
                    } else {
                        inactiveRules.add(rule)
                    }
                }
                
                if (activeRules.isNotEmpty()) {
                    items.add("Active")
                    items.addAll(activeRules)
                }
                if (inactiveRules.isNotEmpty()) {
                    items.add("Inactive")
                    items.addAll(inactiveRules)
                }
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    ruleAdapter.submitList(items, settings.appUsageMillis)
                    binding.btnSetPassword.text = if (!settings.passwordHash.isNullOrEmpty()) "UPDATE PASSWORD" else "SET PASSWORD"
                    
                    updateActiveFocusUI(activeFocus)
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        updateStatusCards()
    }

    private fun updateStatusCards() {
        val isAccActive = PermissionUtils.isAccessibilityServiceEnabled(this, com.mhm.curbstomp.services.CurbstompService::class.java)
        val isAdminActive = PermissionUtils.isDeviceAdminActive(this, com.mhm.curbstomp.receivers.CurbstompDeviceAdminReceiver::class.java)
        
        binding.switchAccessibility.isChecked = isAccActive
        binding.switchDeviceAdmin.isChecked = isAdminActive
        
        lifecycleScope.launch {
            val settings = dataStoreManager.settings.first()
            binding.switchExtraHarden.isChecked = settings.extraHardenEnabled
            
            if (settings.antiUninstallEnabled != isAdminActive) {
                dataStoreManager.updateSettings(settings.copy(antiUninstallEnabled = isAdminActive))
            }
        }
    }

    private fun updateActiveFocusUI(focus: com.mhm.curbstomp.data.models.InstantFocus?) {
        activeFocusJob?.cancel()
        
        if (focus == null) {
            binding.llActiveFocus.visibility = android.view.View.GONE
            binding.llQuickStart.visibility = android.view.View.VISIBLE
            return
        }
        
        binding.llActiveFocus.visibility = android.view.View.VISIBLE
        binding.llQuickStart.visibility = android.view.View.GONE
        
        binding.tvActiveFocusMode.text = if (focus.isWhitelist) "ONLY THESE APPS ALLOWED" else "THESE APPS BLOCKED"
        binding.tvActiveFocusMode.setTextColor(if (focus.isWhitelist) getColor(android.R.color.holo_green_dark) else getColor(com.google.android.material.R.color.design_default_color_error))
        
        binding.llActiveFocusApps.removeAllViews()
        val displayLimit = minOf(focus.appLimits.size, 8)
        for (i in 0 until displayLimit) {
            val limit = focus.appLimits[i]
            val icon = getAppInfoFromPackage(limit.packageName).second
            if (icon != null) {
                val iv = android.widget.ImageView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(96, 96).apply {
                        marginEnd = 16
                    }
                    setImageDrawable(icon)
                }
                binding.llActiveFocusApps.addView(iv)
            }
        }
        if (focus.appLimits.size > 8) {
            val tv = android.widget.TextView(this).apply {
                text = "+${focus.appLimits.size - 8}"
                setTextColor(getColor(android.R.color.black)) // fallback
                textSize = 14f
            }
            binding.llActiveFocusApps.addView(tv)
        }
        
        binding.btnPauseResumeFocus.text = if (focus.isPaused) "RESUME" else "PAUSE"
        
        binding.btnPauseResumeFocus.setOnClickListener {
            lifecycleScope.launch {
                val currentSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                val index = currentSessions.indexOfFirst { it.id == focus.id }
                if (index != -1) {
                    if (focus.isPaused) {
                        // Resume: No password needed
                        val newEndTime = System.currentTimeMillis() + focus.remainingMillis
                        currentSessions[index] = focus.copy(isPaused = false, endTimeMillis = newEndTime)
                        dataStoreManager.updateInstantFocusSessions(currentSessions)
                    } else {
                        // Pause: Password needed
                        PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                            lifecycleScope.launch {
                                val freshSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                                val freshIndex = freshSessions.indexOfFirst { it.id == focus.id }
                                if (freshIndex != -1) {
                                    val remaining = freshSessions[freshIndex].endTimeMillis - System.currentTimeMillis()
                                    freshSessions[freshIndex] = freshSessions[freshIndex].copy(isPaused = true, remainingMillis = remaining)
                                    dataStoreManager.updateInstantFocusSessions(freshSessions)
                                }
                            }
                        }
                    }
                }
            }
        }

        binding.btnStopFocus.setOnClickListener {
            lifecycleScope.launch {
                val sessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                val index = sessions.indexOfFirst { it.id == focus.id }
                if (index != -1) {
                    PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                        lifecycleScope.launch {
                            val freshSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                            val freshIndex = freshSessions.indexOfFirst { it.id == focus.id }
                            if (freshIndex != -1) {
                                freshSessions[freshIndex] = freshSessions[freshIndex].copy(isActive = false, endTimeMillis = System.currentTimeMillis())
                                dataStoreManager.updateInstantFocusSessions(freshSessions)
                            }
                        }
                    }
                }
            }
        }

        binding.btnEditFocusApps.setOnClickListener {
            val intent = Intent(this@MainActivity, SelectAppsActivity::class.java).apply {
                putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(focus.appLimits.map { it.packageName }))
                putExtra("TITLE", "Edit Focus Apps")
            }
            editFocusAppsLauncher.launch(intent)
        }

        binding.btnAddFocusTime.setOnClickListener {
            showAddFocusTimeDialog(focus)
        }
        
        activeFocusJob = lifecycleScope.launch {
            while (isActive) {
                if (focus.isPaused) {
                    val h = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(focus.remainingMillis)
                    val m = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(focus.remainingMillis) % 60
                    val s = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(focus.remainingMillis) % 60
                    binding.tvActiveFocusCountdown.text = String.format("%02d:%02d:%02d", h, m, s)
                    binding.tvActiveFocusCountdown.alpha = 0.5f // Dim when paused
                    break // Don't loop countdown if paused, UI updates will trigger on change
                } else {
                    binding.tvActiveFocusCountdown.alpha = 1.0f
                    val remaining = focus.endTimeMillis - System.currentTimeMillis()
                    if (remaining <= 0) {
                        updateActiveFocusUI(null)
                        break
                    }
                    val h = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(remaining)
                    val m = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
                    val s = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
                    
                    binding.tvActiveFocusCountdown.text = String.format("%02d:%02d:%02d", h, m, s)
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    private fun getAppInfoFromPackage(packageName: String): Pair<String, android.graphics.drawable.Drawable?> {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            Pair(packageManager.getApplicationLabel(appInfo).toString(), packageManager.getApplicationIcon(appInfo))
        } catch (e: Exception) {
            Pair(packageName, null)
        }
    }


    private fun setupListeners() {
        binding.rowAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.rowDeviceAdmin.setOnClickListener {
            val isActive = binding.switchDeviceAdmin.isChecked
            if (!isActive) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Enable Uninstall Invincibility?")
                    .setMessage("This will prevent the app from being uninstalled or force-closed. You will need to use your Curbstomp password to disable this feature later. Are you absolutely sure?")
                    .setPositiveButton("I UNDERSTAND & ENABLE") { _, _ ->
                        PermissionUtils.requestDeviceAdmin(this, com.mhm.curbstomp.receivers.CurbstompDeviceAdminReceiver::class.java)
                        // Note: The actual setting is flipped to true when onResume detects it's active.
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            } else {
                lifecycleScope.launch {
                    PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                        val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                        val component = android.content.ComponentName(this@MainActivity, com.mhm.curbstomp.receivers.CurbstompDeviceAdminReceiver::class.java)
                        dpm.removeActiveAdmin(component)
                        binding.switchDeviceAdmin.isChecked = false
                    }
                }
            }
        }
        
        binding.llExtraHarden.setOnClickListener {
            val isCurrentlyEnabled = binding.switchExtraHarden.isChecked
            if (!isCurrentlyEnabled) {
                // Warning dialog before enabling
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Enable Extra Harden?")
                    .setMessage("This will make it VERY difficult to uninstall the app and you will be completely unable to open the Settings app. You may need to use adb commands to disable this if you forget your password or get locked out. Are you absolutely sure?")
                    .setPositiveButton("I UNDERSTAND & ENABLE") { _, _ ->
                        lifecycleScope.launch {
                            val settings = dataStoreManager.settings.first()
                            dataStoreManager.updateSettings(settings.copy(extraHardenEnabled = true))
                            binding.switchExtraHarden.isChecked = true
                        }
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            } else {
                // Turning off requires password
                lifecycleScope.launch {
                    PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                        lifecycleScope.launch {
                            val settings = dataStoreManager.settings.first()
                            dataStoreManager.updateSettings(settings.copy(extraHardenEnabled = false))
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
            val intent = Intent(this, InstantFocusActivity::class.java).apply {
                putExtra("PREFILL_DURATION_MINS", 30L)
            }
            startActivity(intent)
        }

        binding.btnInstantFocus1h.setOnClickListener {
            val intent = Intent(this, InstantFocusActivity::class.java).apply {
                putExtra("PREFILL_DURATION_MINS", 60L)
            }
            startActivity(intent)
        }
        
        binding.btnSetPassword.setOnClickListener {
            lifecycleScope.launch {
                PasswordDialog.showSetPasswordDialog(this@MainActivity, dataStoreManager)
            }
        }
    }

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items = listOf<Any>()
        private var appUsageMap = mapOf<String, Long>()
        private val expandedStates = mutableSetOf<String>()

        fun submitList(newItems: List<Any>, usageMap: Map<String, Long>) {
            items = newItems
            appUsageMap = usageMap
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is String) 0 else 1
        }

        inner class RuleViewHolder(val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root)
        inner class HeaderViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val view = LayoutInflater.from(parent.context).inflate(com.mhm.curbstomp.R.layout.item_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                RuleViewHolder(binding)
            }
        }

        private fun getAppInfo(packageName: String): Pair<String, android.graphics.drawable.Drawable?> {
            return try {
                val pm = packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                Pair(pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
            } catch (e: Exception) {
                Pair(packageName, null)
            }
        }

        override fun onBindViewHolder(baseHolder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            
            if (baseHolder is HeaderViewHolder && item is String) {
                val tvHeader = baseHolder.itemView.findViewById<android.widget.TextView>(com.mhm.curbstomp.R.id.tvHeader)
                tvHeader.text = item
                return
            }

            val holder = baseHolder as RuleViewHolder
            val inflater = LayoutInflater.from(holder.itemView.context)

            holder.binding.llAppStatsContainer.removeAllViews()

            if (item is Rule) {
                holder.binding.tvRuleName.text = item.name
                holder.binding.tvRuleTime.text = "${item.startTime} - ${item.endTime}"
                holder.binding.tvRuleApps.text = "${item.appLimits.size} apps selected"
                holder.binding.switchRuleActive.isChecked = item.isActive

                val isExpanded = expandedStates.contains(item.id)
                val colHeaders = holder.binding.root.findViewById<android.view.View>(com.mhm.curbstomp.R.id.llStatColHeaders)
                holder.binding.llAppStatsContainer.visibility = if (isExpanded) android.view.View.VISIBLE else android.view.View.GONE
                colHeaders?.visibility = if (isExpanded) android.view.View.VISIBLE else android.view.View.GONE
                // right (-90°) = collapsed, down (0°) = expanded (base icon faces down)
                holder.binding.btnExpandRule.rotation = if (isExpanded) 0f else -90f

                holder.binding.btnExpandRule.setOnClickListener {
                    if (isExpanded) expandedStates.remove(item.id) else expandedStates.add(item.id)
                    notifyItemChanged(position)
                }

                // Compute exact dateStr matching CurbstompService
                val calendar = java.util.Calendar.getInstance()
                val currentDay = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                val currentTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val crossesMidnight = item.startTime > item.endTime
                val dateStr = if (crossesMidnight && currentTimeStr <= item.endTime) {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(cal.time)
                } else {
                    SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                }

                if (isExpanded) {
                    for (limit in item.appLimits) {
                        val statBinding = com.mhm.curbstomp.databinding.ItemAppStatBinding.inflate(inflater, holder.binding.llAppStatsContainer, true)
                        val (appName, icon) = getAppInfo(limit.packageName)
                        statBinding.tvStatAppName.text = appName
                        statBinding.ivStatAppIcon.setImageDrawable(icon)

                        val key = "${item.id}_${dateStr}_${limit.packageName}"
                        val usedMillis = appUsageMap[key] ?: 0L
                        val usedMins = usedMillis / 60000
                        val maxMins = limit.maxUsageMillis / 60000

                        if (limit.maxUsageMillis == 0L) {
                            statBinding.pbUsage.progress = 100
                            statBinding.pbUsage.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                            statBinding.tvStatUsage.text = "0m"
                            statBinding.tvStatRemaining.text = "Blocked"
                        } else {
                            val progress = if (maxMins > 0) ((usedMins.toFloat() / maxMins) * 100).toInt() else 0
                            statBinding.pbUsage.progress = progress.coerceAtMost(100)
                            val left = (maxMins - usedMins).coerceAtLeast(0)
                            statBinding.tvStatUsage.text = "${usedMins}m"
                            statBinding.tvStatRemaining.text = "${left}m"
                            if (left == 0L) {
                                statBinding.pbUsage.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                            } else {
                                statBinding.pbUsage.progressTintList = android.content.res.ColorStateList.valueOf(getColor(android.R.color.holo_green_dark))
                            }
                        }
                    }
                }

                holder.binding.btnEditRule.setOnClickListener {
                    val intent = Intent(this@MainActivity, CreateRuleActivity::class.java).apply {
                        putExtra("EDIT_RULE_ID", item.id)
                    }
                    startActivity(intent)
                }

                val btnDelete = holder.binding.root.findViewById<android.view.View>(com.mhm.curbstomp.R.id.btnDeleteRule)
                btnDelete?.setOnClickListener {
                    lifecycleScope.launch {
                        PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                            lifecycleScope.launch {
                                val currentRules = dataStoreManager.settings.first().rules.toMutableList()
                                currentRules.removeAll { it.id == item.id }
                                dataStoreManager.updateRules(currentRules)
                            }
                        }
                    }
                }

                holder.binding.switchRuleActive.setOnClickListener { 
                    val isChecked = holder.binding.switchRuleActive.isChecked
                    if (!isChecked) { // Turning OFF requires password
                        holder.binding.switchRuleActive.isChecked = true // revert until password
                        lifecycleScope.launch {
                            PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                                holder.binding.switchRuleActive.isChecked = false
                                lifecycleScope.launch {
                                    val currentRules = dataStoreManager.settings.first().rules.toMutableList()
                                    val index = currentRules.indexOfFirst { it.id == item.id }
                                    if (index != -1) {
                                        currentRules[index] = item.copy(isActive = false)
                                        dataStoreManager.updateRules(currentRules)
                                    }
                                }
                            }
                        }
                    } else { // Turning ON doesn't require password
                        lifecycleScope.launch {
                            val currentRules = dataStoreManager.settings.first().rules.toMutableList()
                            val index = currentRules.indexOfFirst { it.id == item.id }
                            if (index != -1) {
                                currentRules[index] = item.copy(isActive = true)
                                dataStoreManager.updateRules(currentRules)
                            }
                        }
                    }
                }

            } else if (item is InstantFocus) {
                holder.binding.tvRuleName.text = "Instant Focus (Running)"
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                val startStr = sdf.format(Date(item.startTimeMillis))
                val endStr = sdf.format(Date(item.endTimeMillis))
                holder.binding.tvRuleTime.text = "$startStr - $endStr"
                holder.binding.tvRuleApps.text = "${item.appLimits.size} apps selected"
                holder.binding.switchRuleActive.isChecked = item.isActive

                val isExpanded = expandedStates.contains(item.id)
                val colHeaders2 = holder.binding.root.findViewById<android.view.View>(com.mhm.curbstomp.R.id.llStatColHeaders)
                holder.binding.llAppStatsContainer.visibility = if (isExpanded) android.view.View.VISIBLE else android.view.View.GONE
                colHeaders2?.visibility = if (isExpanded) android.view.View.VISIBLE else android.view.View.GONE
                holder.binding.btnExpandRule.rotation = if (isExpanded) 0f else -90f

                holder.binding.btnExpandRule.setOnClickListener {
                    if (isExpanded) expandedStates.remove(item.id) else expandedStates.add(item.id)
                    notifyItemChanged(position)
                }

                if (isExpanded) {
                    for (limit in item.appLimits) {
                        val statBinding = com.mhm.curbstomp.databinding.ItemAppStatBinding.inflate(inflater, holder.binding.llAppStatsContainer, true)
                        val (appName, icon) = getAppInfo(limit.packageName)
                        statBinding.tvStatAppName.text = appName
                        statBinding.ivStatAppIcon.setImageDrawable(icon)

                        val key = "${item.id}_${limit.packageName}"
                        val usedMillis = appUsageMap[key] ?: 0L
                        val usedMins = usedMillis / 60000
                        val maxMins = limit.maxUsageMillis / 60000

                        if (limit.maxUsageMillis == 0L) {
                            statBinding.pbUsage.progress = 100
                            statBinding.pbUsage.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                            statBinding.tvStatUsage.text = "0m"
                            statBinding.tvStatRemaining.text = "Blocked"
                        } else {
                            val progress = if (maxMins > 0) ((usedMins.toFloat() / maxMins) * 100).toInt() else 0
                            statBinding.pbUsage.progress = progress.coerceAtMost(100)
                            val left = (maxMins - usedMins).coerceAtLeast(0)
                            statBinding.tvStatUsage.text = "${usedMins}m"
                            statBinding.tvStatRemaining.text = "${left}m"
                            if (left == 0L) {
                                statBinding.pbUsage.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                            } else {
                                statBinding.pbUsage.progressTintList = android.content.res.ColorStateList.valueOf(getColor(android.R.color.holo_green_dark))
                            }
                        }
                    }
                }

                holder.binding.btnEditRule.setOnClickListener {
                    val intent = Intent(this@MainActivity, InstantFocusActivity::class.java).apply {
                        putExtra("EDIT_FOCUS_ID", item.id)
                    }
                    startActivity(intent)
                }

                val btnDeleteFocus = holder.binding.root.findViewById<android.view.View>(com.mhm.curbstomp.R.id.btnDeleteRule)
                btnDeleteFocus?.setOnClickListener {
                    lifecycleScope.launch {
                        PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                            lifecycleScope.launch {
                                val currentFocuses = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                                currentFocuses.removeAll { it.id == item.id }
                                dataStoreManager.updateInstantFocusSessions(currentFocuses)
                            }
                        }
                    }
                }

                holder.binding.switchRuleActive.setOnClickListener {
                    val isChecked = holder.binding.switchRuleActive.isChecked
                    if (!isChecked) { // Turning OFF requires password
                        holder.binding.switchRuleActive.isChecked = true // revert
                        lifecycleScope.launch {
                            PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                                holder.binding.switchRuleActive.isChecked = false
                                lifecycleScope.launch {
                                    val currentFocuses = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                                    val index = currentFocuses.indexOfFirst { it.id == item.id }
                                    if (index != -1) {
                                        currentFocuses[index] = item.copy(isActive = false)
                                        dataStoreManager.updateInstantFocusSessions(currentFocuses)
                                    }
                                }
                            }
                        }
                    } else { // Turning ON doesn't require password
                        lifecycleScope.launch {
                            val currentFocuses = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                            val index = currentFocuses.indexOfFirst { it.id == item.id }
                            if (index != -1) {
                                currentFocuses[index] = item.copy(isActive = true)
                                dataStoreManager.updateInstantFocusSessions(currentFocuses)
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun showAddFocusTimeDialog(focus: com.mhm.curbstomp.data.models.InstantFocus) {
        val options = arrayOf("+10 Minutes", "+30 Minutes", "+1 Hour", "+2 Hours", "Custom...")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add Time to Focus")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addFocusTime(focus, 10)
                    1 -> addFocusTime(focus, 30)
                    2 -> addFocusTime(focus, 60)
                    3 -> addFocusTime(focus, 120)
                    4 -> showCustomAddFocusTimePicker(focus)
                }
            }
            .show()
    }

    private fun addFocusTime(focus: com.mhm.curbstomp.data.models.InstantFocus, minutes: Long) {
        lifecycleScope.launch {
            val currentSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
            val index = currentSessions.indexOfFirst { it.id == focus.id }
            if (index != -1) {
                val oldSession = currentSessions[index]
                val updatedSession = if (oldSession.isPaused) {
                    oldSession.copy(remainingMillis = oldSession.remainingMillis + (minutes * 60000))
                } else {
                    oldSession.copy(endTimeMillis = oldSession.endTimeMillis + (minutes * 60000))
                }
                currentSessions[index] = updatedSession
                dataStoreManager.updateInstantFocusSessions(currentSessions)
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Added $minutes minutes to focus session", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCustomAddFocusTimePicker(focus: com.mhm.curbstomp.data.models.InstantFocus) {
        val dialogView = LayoutInflater.from(this).inflate(com.mhm.curbstomp.R.layout.dialog_time_picker, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        val tvPickerTitle = dialogView.findViewById<android.widget.TextView>(com.mhm.curbstomp.R.id.tvPickerTitle)
        val npHours = dialogView.findViewById<android.widget.NumberPicker>(com.mhm.curbstomp.R.id.npHours)
        val npMinutes = dialogView.findViewById<android.widget.NumberPicker>(com.mhm.curbstomp.R.id.npMinutes)
        val btnFullyBlock = dialogView.findViewById<android.view.View>(com.mhm.curbstomp.R.id.btnFullyBlock)
        val btnSaveLimit = dialogView.findViewById<com.google.android.material.button.MaterialButton>(com.mhm.curbstomp.R.id.btnSaveLimit)

        tvPickerTitle.text = "ADD TIME"
        btnFullyBlock.visibility = android.view.View.GONE
        btnSaveLimit.text = "ADD TIME"

        npHours.minValue = 0
        npHours.maxValue = 23
        npHours.value = 0

        npMinutes.minValue = 0
        npMinutes.maxValue = 59
        npMinutes.value = 15

        btnSaveLimit.setOnClickListener {
            val totalMins = (npHours.value * 60) + npMinutes.value
            if (totalMins > 0) {
                addFocusTime(focus, totalMins.toLong())
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveEditedFocusApps(newApps: List<String>) {
        lifecycleScope.launch {
            val currentSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
            val currentTime = System.currentTimeMillis()
            val oldSession = currentSessions.firstOrNull { it.isActive && currentTime < it.endTimeMillis } ?: return@launch
            
            val isWhitelist = oldSession.isWhitelist
            val oldApps = oldSession.appLimits.map { it.packageName }.toSet()
            
            val addedApps = newApps.filter { it !in oldApps }
            val removedApps = oldApps.filter { it !in newApps }
            
            var requiresPassword = false
            if (isWhitelist) {
                // Allow Only These: adding allowed apps is less strict -> password needed
                if (addedApps.isNotEmpty()) {
                    requiresPassword = true
                }
            } else {
                // Block These: removing blocked apps is less strict -> password needed
                if (removedApps.isNotEmpty()) {
                    requiresPassword = true
                }
            }
            
            val doSave = {
                lifecycleScope.launch {
                    val freshSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                    val index = freshSessions.indexOfFirst { it.id == oldSession.id }
                    if (index != -1) {
                        val newLimits = newApps.map { com.mhm.curbstomp.data.models.AppLimit(it, 0L) }
                        freshSessions[index] = freshSessions[index].copy(appLimits = newLimits)
                        dataStoreManager.updateInstantFocusSessions(freshSessions)
                        
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Focus apps updated", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (requiresPassword) {
                    PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) { doSave() }
                } else {
                    doSave()
                }
            }
        }
    }
}
