package com.mhm.curbstomp.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mhm.curbstomp.data.models.Rule
import com.mhm.curbstomp.databinding.ItemAppStatBinding
import com.mhm.curbstomp.databinding.ItemHeaderBinding
import com.mhm.curbstomp.databinding.ItemRuleBinding
import com.mhm.curbstomp.domain.RuleEngine
import com.mhm.curbstomp.ui.common.AppIconLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

sealed class RuleListItem {
    data class Header(val title: String) : RuleListItem()
    data class RuleItem(val rule: Rule) : RuleListItem()
}

/**
 * Adapter rendering grouped Active/Inactive rules with expandable per-app usage stats
 * and asynchronous app icon loading.
 */
class RuleAdapter(
    private val scope: CoroutineScope,
    private val iconLoader: AppIconLoader,
    private val onEditRule: (Rule) -> Unit,
    private val onDeleteRule: (Rule) -> Unit,
    private val onToggleRule: (Rule, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<RuleListItem> = emptyList()
    private var appUsageMap: Map<String, Long> = emptyMap()
    private var currentMinuteOfDay: Int = 0
    private var todayDateKey: String = ""
    private var yesterdayDateKey: String = ""
    private val expandedRuleIds = mutableSetOf<String>()

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_RULE = 1
    }

    fun submitList(
        newItems: List<RuleListItem>,
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
                val oldItem = oldItems[oldItemPosition]
                val newItem = newItems[newItemPosition]
                return when {
                    oldItem is RuleListItem.Header && newItem is RuleListItem.Header ->
                        oldItem.title == newItem.title
                    oldItem is RuleListItem.RuleItem && newItem is RuleListItem.RuleItem ->
                        oldItem.rule.id == newItem.rule.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = newItems[newItemPosition]
                if (oldItem != newItem) return false
                if (oldItem is RuleListItem.RuleItem && expandedRuleIds.contains(oldItem.rule.id)) {
                    return oldUsageMap == usageMap && oldToday == todayKey && oldYesterday == yesterdayKey
                }
                return true
            }
        })
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is RuleListItem.Header -> VIEW_TYPE_HEADER
        is RuleListItem.RuleItem -> VIEW_TYPE_RULE
    }

    class HeaderViewHolder(val binding: ItemHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    class RuleViewHolder(val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root) {
        val iconJobs = mutableListOf<Job>()

        fun cancelIconJobs() {
            for (job in iconJobs) {
                job.cancel()
            }
            iconJobs.clear()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(ItemHeaderBinding.inflate(inflater, parent, false))
        } else {
            RuleViewHolder(ItemRuleBinding.inflate(inflater, parent, false))
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is RuleViewHolder) {
            holder.cancelIconJobs()
        }
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(baseHolder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RuleListItem.Header -> {
                (baseHolder as HeaderViewHolder).binding.tvHeader.text = item.title
            }
            is RuleListItem.RuleItem -> {
                bindRule(baseHolder as RuleViewHolder, item.rule)
            }
        }
    }

    private fun bindRule(holder: RuleViewHolder, rule: Rule) {
        holder.cancelIconJobs()
        holder.binding.llAppStatsContainer.removeAllViews()

        val context = holder.itemView.context
        val inflater = LayoutInflater.from(context)

        holder.binding.tvRuleName.text = rule.name
        holder.binding.tvRuleTime.text = "${rule.startTime} - ${rule.endTime}"
        holder.binding.tvRuleApps.text = "${rule.appLimits.size} apps selected"
        holder.binding.switchRuleActive.isChecked = rule.isActive

        val isExpanded = expandedRuleIds.contains(rule.id)
        holder.binding.llAppStatsContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.binding.llStatColHeaders.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.binding.btnExpandRule.rotation = if (isExpanded) 0f else -90f

        holder.binding.btnExpandRule.setOnClickListener {
            if (!expandedRuleIds.add(rule.id)) {
                expandedRuleIds.remove(rule.id)
            }
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos)
            }
        }

        if (isExpanded) {
            val dateKey = RuleEngine.statsDateKeyForRule(
                rule = rule,
                minuteOfDay = currentMinuteOfDay,
                todayKey = todayDateKey,
                yesterdayKey = yesterdayDateKey
            )
            val greenColor = context.getColor(android.R.color.holo_green_dark)

            for (limit in rule.appLimits) {
                val statBinding = ItemAppStatBinding.inflate(
                    inflater,
                    holder.binding.llAppStatsContainer,
                    true
                )
                iconLoader.bind(
                    scope = scope,
                    packageName = limit.packageName,
                    iconView = statBinding.ivStatAppIcon,
                    labelView = statBinding.tvStatAppName
                )?.let { holder.iconJobs.add(it) }

                val key = "${rule.id}_${dateKey}_${limit.packageName}"
                val usedMillis = appUsageMap[key] ?: 0L
                val usedMins = usedMillis / 60_000L
                val maxMins = limit.maxUsageMillis / 60_000L

                if (limit.maxUsageMillis == 0L) {
                    statBinding.pbUsage.progress = 100
                    statBinding.pbUsage.progressTintList = ColorStateList.valueOf(Color.RED)
                    statBinding.tvStatUsage.text = "0m"
                    statBinding.tvStatRemaining.text = "Blocked"
                } else {
                    val progress = if (maxMins > 0L) ((usedMins.toFloat() / maxMins) * 100f).toInt() else 0
                    statBinding.pbUsage.progress = progress.coerceAtMost(100)
                    val remainingMins = (maxMins - usedMins).coerceAtLeast(0L)
                    statBinding.tvStatUsage.text = "${usedMins}m"
                    statBinding.tvStatRemaining.text = "${remainingMins}m"
                    statBinding.pbUsage.progressTintList = ColorStateList.valueOf(
                        if (remainingMins == 0L) Color.RED else greenColor
                    )
                }
            }
        }

        holder.binding.btnEditRule.setOnClickListener {
            onEditRule(rule)
        }

        holder.binding.btnDeleteRule.setOnClickListener {
            onDeleteRule(rule)
        }

        holder.binding.switchRuleActive.setOnClickListener {
            val targetState = holder.binding.switchRuleActive.isChecked
            if (!targetState) {
                // Keep visually ON until password verification succeeds and Flow emits new state
                holder.binding.switchRuleActive.isChecked = true
            }
            onToggleRule(rule, targetState)
        }
    }

    override fun getItemCount(): Int = items.size
}
