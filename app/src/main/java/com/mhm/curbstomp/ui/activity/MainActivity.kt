package com.mhm.curbstomp.ui.activity

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.mhm.curbstomp.R
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.data.models.InstantFocus
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.databinding.ActivityMainBinding
import com.mhm.curbstomp.databinding.ItemAppStatBinding
import com.mhm.curbstomp.databinding.ItemRuleBinding
import com.mhm.curbstomp.receivers.CurbstompDeviceAdminReceiver
import com.mhm.curbstomp.services.CurbstompService
import com.mhm.curbstomp.utils.DataStoreManager
import com.mhm.curbstomp.utils.PasswordDialog
import com.mhm.curbstomp.utils.PermissionUtils
import com.mhm.curbstomp.utils.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dataStoreManager: DataStoreManager
    private val ruleAdapter = RuleAdapter()

    private val appInfoCache = LruCache<String, Pair<String, Drawable?>>(48)
    private var lastRenderedFocus: InstantFocus? = null
    private var hasRenderedFocusState: Boolean = false

    private val editFocusAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    ?: return@registerForActivityResult
                saveEditedFocusApps(apps)
            }
        }

    private var activeFocusJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Curbstomp)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dataStoreManager = DataStoreManager(this)

        binding.rvRules.layoutManager = LinearLayoutManager(this)
        binding.rvRules.adapter = ruleAdapter

        setupListeners()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStoreManager.settings.collectLatest { settings ->
                    val currentTime = System.currentTimeMillis()
                    val activeFocus = RuleEngine.findDisplayInstantFocus(
                        settings.instantFocusSessions,
                        currentTime
                    )

                    val calendar = Calendar.getInstance()
                    calendar.timeInMillis = currentTime
                    val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
                    val minuteOfDay =
                        calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
                    val todayKey = RuleEngine.formatDateKey(calendar)
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                    val yesterdayKey = RuleEngine.formatDateKey(calendar)

                    val activeRules = ArrayList<Rule>()
                    val inactiveRules = ArrayList<Rule>()

                    for (rule in settings.rules) {
                        val activeKey = RuleEngine.activeRuleDateKey(
                            rule,
                            currentDay,
                            minuteOfDay,
                            todayKey,
                            yesterdayKey
                        )
                        if (activeKey != null) {
                            activeRules.add(rule)
                        } else {
                            inactiveRules.add(rule)
                        }
                    }

                    val items = ArrayList<Any>(settings.rules.size + 2)
                    if (activeRules.isNotEmpty()) {
                        items.add("Active")
                        items.addAll(activeRules)
                    }
                    if (inactiveRules.isNotEmpty()) {
                        items.add("Inactive")
                        items.addAll(inactiveRules)
                    }

                    ruleAdapter.submitList(
                        items,
                        settings.appUsageMillis,
                        minuteOfDay,
                        todayKey,
                        yesterdayKey
                    )
                    binding.btnSetPassword.text =
                        if (!settings.passwordHash.isNullOrEmpty()) "UPDATE PASSWORD" else "SET PASSWORD"

                    updateActiveFocusUI(activeFocus)
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
        hasRenderedFocusState = false
    }

    override fun onDestroy() {
        activeFocusJob?.cancel()
        appInfoCache.evictAll()
        super.onDestroy()
    }

    private fun updateStatusCards() {
        val isAccActive = PermissionUtils.isAccessibilityServiceEnabled(this, CurbstompService::class.java)
        val isAdminActive = PermissionUtils.isDeviceAdminActive(this, CurbstompDeviceAdminReceiver::class.java)

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
            binding.llActiveFocusApps.removeAllViews()
            val displayLimit = minOf(focus.appLimits.size, 8)
            for (i in 0 until displayLimit) {
                val limit = focus.appLimits[i]
                val icon = getAppInfoCached(limit.packageName).second
                if (icon != null) {
                    val iv = ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(96, 96).apply {
                            marginEnd = 16
                        }
                        setImageDrawable(icon)
                    }
                    binding.llActiveFocusApps.addView(iv)
                }
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

        binding.btnPauseResumeFocus.text = if (focus.isPaused) "RESUME" else "PAUSE"

        binding.btnPauseResumeFocus.setOnClickListener {
            lifecycleScope.launch {
                val currentSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                val index = currentSessions.indexOfFirst { it.id == focus.id }
                if (index != -1) {
                    if (focus.isPaused) {
                        val newEndTime = System.currentTimeMillis() + focus.remainingMillis
                        currentSessions[index] = focus.copy(isPaused = false, endTimeMillis = newEndTime)
                        dataStoreManager.updateInstantFocusSessions(currentSessions)
                    } else {
                        PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                            lifecycleScope.launch {
                                val freshSessions =
                                    dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                                val freshIndex = freshSessions.indexOfFirst { it.id == focus.id }
                                if (freshIndex != -1) {
                                    val remaining =
                                        (freshSessions[freshIndex].endTimeMillis - System.currentTimeMillis())
                                            .coerceAtLeast(0L)
                                    freshSessions[freshIndex] = freshSessions[freshIndex].copy(
                                        isPaused = true,
                                        remainingMillis = remaining
                                    )
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
                val sessions = dataStoreManager.settings.first().instantFocusSessions
                if (sessions.any { it.id == focus.id }) {
                    PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                        lifecycleScope.launch {
                            val freshSessions =
                                dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                            val freshIndex = freshSessions.indexOfFirst { it.id == focus.id }
                            if (freshIndex != -1) {
                                freshSessions[freshIndex] = freshSessions[freshIndex].copy(
                                    isActive = false,
                                    isPaused = false,
                                    endTimeMillis = System.currentTimeMillis()
                                )
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
            if (focus.isPaused) {
                val remaining = focus.remainingMillis.coerceAtLeast(0L)
                val h = TimeUnit.MILLISECONDS.toHours(remaining)
                val m = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
                val s = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
                binding.tvActiveFocusCountdown.text = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
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
                val h = TimeUnit.MILLISECONDS.toHours(remaining)
                val m = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
                val s = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
                binding.tvActiveFocusCountdown.text = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                delay(1000L)
            }
        }
    }

    private fun getAppInfoCached(packageName: String): Pair<String, Drawable?> {
        appInfoCache.get(packageName)?.let { return it }
        val resolved = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            Pair(packageManager.getApplicationLabel(appInfo).toString(), packageManager.getApplicationIcon(appInfo))
        } catch (_: Exception) {
            Pair(packageName, null)
        }
        appInfoCache.put(packageName, resolved)
        return resolved
    }

    private fun setupListeners() {
        binding.rowAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.rowDeviceAdmin.setOnClickListener {
            val isActive = binding.switchDeviceAdmin.isChecked
            if (!isActive) {
                AlertDialog.Builder(this)
                    .setTitle("Enable Uninstall Invincibility?")
                    .setMessage("This will prevent the app from being uninstalled or force-closed. You will need to use your Curbstomp password to disable this feature later. Are you absolutely sure?")
                    .setPositiveButton("I UNDERSTAND & ENABLE") { _, _ ->
                        lifecycleScope.launch {
                            dataStoreManager.updateDeviceAdminActivationRequestedAt(System.currentTimeMillis())
                            PermissionUtils.requestDeviceAdmin(
                                this@MainActivity,
                                CurbstompDeviceAdminReceiver::class.java
                            )
                        }
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            } else {
                lifecycleScope.launch {
                    PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) {
                        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val component = ComponentName(this@MainActivity, CurbstompDeviceAdminReceiver::class.java)
                        dpm.removeActiveAdmin(component)
                        binding.switchDeviceAdmin.isChecked = false
                    }
                }
            }
        }

        binding.llExtraHarden.setOnClickListener {
            val isCurrentlyEnabled = binding.switchExtraHarden.isChecked
            if (!isCurrentlyEnabled) {
                AlertDialog.Builder(this)
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
        private var currentMinuteOfDay: Int = 0
        private var todayDateKey: String = ""
        private var yesterdayDateKey: String = ""
        private val expandedStates = mutableSetOf<String>()

        fun submitList(
            newItems: List<Any>,
            usageMap: Map<String, Long>,
            minuteOfDay: Int,
            todayKey: String,
            yesterdayKey: String
        ) {
            val oldItems = items
            val oldUsageMap = appUsageMap
            val oldToday = todayDateKey
            val oldYesterday = yesterdayDateKey

            if (oldItems == newItems &&
                oldUsageMap == usageMap &&
                oldToday == todayKey &&
                oldYesterday == yesterdayKey
            ) {
                currentMinuteOfDay = minuteOfDay
                return
            }

            items = newItems
            appUsageMap = usageMap
            currentMinuteOfDay = minuteOfDay
            todayDateKey = todayKey
            yesterdayDateKey = yesterdayKey

            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val o = oldItems[oldItemPosition]
                    val n = newItems[newItemPosition]
                    return when {
                        o is String && n is String -> o == n
                        o is Rule && n is Rule -> o.id == n.id
                        else -> false
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val o = oldItems[oldItemPosition]
                    val n = newItems[newItemPosition]
                    if (o != n) return false
                    if (o is Rule && expandedStates.contains(o.id)) {
                        return oldUsageMap == usageMap && oldToday == todayKey && oldYesterday == yesterdayKey
                    }
                    return true
                }
            })
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is String) 0 else 1
        }

        inner class RuleViewHolder(val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root)
        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                RuleViewHolder(binding)
            }
        }

        override fun onBindViewHolder(baseHolder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]

            if (baseHolder is HeaderViewHolder && item is String) {
                val tvHeader = baseHolder.itemView.findViewById<TextView>(R.id.tvHeader)
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
                val colHeaders = holder.binding.root.findViewById<View>(R.id.llStatColHeaders)
                holder.binding.llAppStatsContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
                colHeaders?.visibility = if (isExpanded) View.VISIBLE else View.GONE
                holder.binding.btnExpandRule.rotation = if (isExpanded) 0f else -90f

                holder.binding.btnExpandRule.setOnClickListener {
                    if (expandedStates.contains(item.id)) {
                        expandedStates.remove(item.id)
                    } else {
                        expandedStates.add(item.id)
                    }
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos)
                    }
                }

                if (isExpanded) {
                    val dateStr = RuleEngine.statsDateKeyForRule(
                        item,
                        currentMinuteOfDay,
                        todayDateKey,
                        yesterdayDateKey
                    )
                    for (limit in item.appLimits) {
                        val statBinding = ItemAppStatBinding.inflate(inflater, holder.binding.llAppStatsContainer, true)
                        val (appName, icon) = getAppInfoCached(limit.packageName)
                        statBinding.tvStatAppName.text = appName
                        statBinding.ivStatAppIcon.setImageDrawable(icon)

                        val key = "${item.id}_${dateStr}_${limit.packageName}"
                        val usedMillis = appUsageMap[key] ?: 0L
                        val usedMins = usedMillis / 60000
                        val maxMins = limit.maxUsageMillis / 60000

                        if (limit.maxUsageMillis == 0L) {
                            statBinding.pbUsage.progress = 100
                            statBinding.pbUsage.progressTintList = ColorStateList.valueOf(Color.RED)
                            statBinding.tvStatUsage.text = "0m"
                            statBinding.tvStatRemaining.text = "Blocked"
                        } else {
                            val progress = if (maxMins > 0) ((usedMins.toFloat() / maxMins) * 100).toInt() else 0
                            statBinding.pbUsage.progress = progress.coerceAtMost(100)
                            val left = (maxMins - usedMins).coerceAtLeast(0)
                            statBinding.tvStatUsage.text = "${usedMins}m"
                            statBinding.tvStatRemaining.text = "${left}m"
                            statBinding.pbUsage.progressTintList = ColorStateList.valueOf(
                                if (left == 0L) Color.RED else getColor(android.R.color.holo_green_dark)
                            )
                        }
                    }
                }

                holder.binding.btnEditRule.setOnClickListener {
                    val intent = Intent(this@MainActivity, CreateRuleActivity::class.java).apply {
                        putExtra("EDIT_RULE_ID", item.id)
                    }
                    startActivity(intent)
                }

                val btnDelete = holder.binding.root.findViewById<View>(R.id.btnDeleteRule)
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
                    if (!isChecked) {
                        holder.binding.switchRuleActive.isChecked = true
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
                    } else {
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
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun showAddFocusTimeDialog(focus: InstantFocus) {
        val options = arrayOf("+10 Minutes", "+30 Minutes", "+1 Hour", "+2 Hours", "Custom...")
        AlertDialog.Builder(this)
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

    private fun addFocusTime(focus: InstantFocus, minutes: Long) {
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

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Added $minutes minutes to focus session", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCustomAddFocusTimePicker(focus: InstantFocus) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_time_picker, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        val tvPickerTitle = dialogView.findViewById<TextView>(R.id.tvPickerTitle)
        val npHours = dialogView.findViewById<NumberPicker>(R.id.npHours)
        val npMinutes = dialogView.findViewById<NumberPicker>(R.id.npMinutes)
        val btnFullyBlock = dialogView.findViewById<View>(R.id.btnFullyBlock)
        val btnSaveLimit = dialogView.findViewById<MaterialButton>(R.id.btnSaveLimit)

        tvPickerTitle.text = "ADD TIME"
        btnFullyBlock.visibility = View.GONE
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
            val currentSessions = dataStoreManager.settings.first().instantFocusSessions
            val currentTime = System.currentTimeMillis()
            val oldSession = RuleEngine.findDisplayInstantFocus(currentSessions, currentTime) ?: return@launch

            val isWhitelist = oldSession.isWhitelist
            val oldApps = oldSession.appLimits.map { it.packageName }.toSet()

            val addedApps = newApps.filter { it !in oldApps }
            val removedApps = oldApps.filter { it !in newApps }

            val requiresPassword = if (isWhitelist) {
                addedApps.isNotEmpty()
            } else {
                removedApps.isNotEmpty()
            }

            val doSave = {
                lifecycleScope.launch {
                    val freshSessions = dataStoreManager.settings.first().instantFocusSessions.toMutableList()
                    val index = freshSessions.indexOfFirst { it.id == oldSession.id }
                    if (index != -1) {
                        val newLimits = newApps.map { AppLimit(it, 0L) }
                        freshSessions[index] = freshSessions[index].copy(appLimits = newLimits)
                        dataStoreManager.updateInstantFocusSessions(freshSessions)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Focus apps updated", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (requiresPassword) {
                    PasswordDialog.verifyPassword(this@MainActivity, dataStoreManager) { doSave() }
                } else {
                    doSave()
                }
            }
        }
    }
}
