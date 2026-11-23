package com.mhm.curbstomp.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mhm.curbstomp.data.models.AppLimit
import com.mhm.curbstomp.databinding.ItemAppLimitBinding
import com.mhm.curbstomp.ui.common.AppIconLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Reusable adapter for displaying selected apps with optional per-app time limits.
 * Shared by [com.mhm.curbstomp.ui.activity.CreateRuleActivity] and
 * [com.mhm.curbstomp.ui.activity.InstantFocusActivity].
 */
class AppLimitAdapter(
    private val scope: CoroutineScope,
    private val iconLoader: AppIconLoader,
    private val showLimits: Boolean,
    private val onRemove: (packageName: String) -> Unit,
    private val onSetLimitClick: ((packageName: String, currentLimitMillis: Long) -> Unit)? = null
) : RecyclerView.Adapter<AppLimitAdapter.ViewHolder>() {

    private var items: List<AppLimit> = emptyList()

    fun submitList(newItems: List<AppLimit>) {
        val oldItems = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].packageName == newItems[newItemPosition].packageName

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == newItems[newItemPosition]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    class ViewHolder(val binding: ItemAppLimitBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppLimitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.iconJob?.cancel()
        holder.iconJob = null
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        val packageName = entry.packageName
        val limitMinutes = entry.maxUsageMillis / 60_000L

        holder.iconJob?.cancel()
        holder.iconJob = iconLoader.bind(
            scope = scope,
            packageName = packageName,
            iconView = holder.binding.ivAppIcon,
            labelView = holder.binding.tvAppName
        )

        if (showLimits) {
            holder.binding.tvAppLimit.visibility = View.VISIBLE
            holder.binding.btnSetLimit.visibility = View.VISIBLE
            holder.binding.tvAppLimit.text = if (limitMinutes == 0L) {
                "Blocked (0 min)"
            } else {
                "Limit: $limitMinutes min"
            }
            holder.binding.btnSetLimit.setOnClickListener {
                onSetLimitClick?.invoke(packageName, entry.maxUsageMillis)
            }
        } else {
            holder.binding.tvAppLimit.visibility = View.GONE
            holder.binding.btnSetLimit.visibility = View.GONE
            holder.binding.btnSetLimit.setOnClickListener(null)
        }

        holder.binding.btnRemove.setOnClickListener {
            onRemove(packageName)
        }
    }

    override fun getItemCount(): Int = items.size
}
