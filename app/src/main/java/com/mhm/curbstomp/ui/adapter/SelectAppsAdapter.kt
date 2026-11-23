package com.mhm.curbstomp.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mhm.curbstomp.data.models.AppInfo
import com.mhm.curbstomp.databinding.SelectAppsItemBinding
import com.mhm.curbstomp.ui.common.AppIconLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Multi-select adapter for choosing installed apps, with async off-thread icon loading.
 */
class SelectAppsAdapter(
    private val scope: CoroutineScope,
    private val iconLoader: AppIconLoader,
    private val isSelected: (packageName: String) -> Boolean,
    private val onToggleSelection: (packageName: String) -> Unit
) : RecyclerView.Adapter<SelectAppsAdapter.ViewHolder>() {

    private var items: List<AppInfo> = emptyList()

    fun submitList(newItems: List<AppInfo>) {
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

    class ViewHolder(val binding: SelectAppsItemBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = SelectAppsItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.iconJob?.cancel()
        holder.iconJob = null
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        holder.binding.tvAppName.text = app.label

        holder.iconJob?.cancel()
        holder.iconJob = iconLoader.bind(
            scope = scope,
            packageName = app.packageName,
            iconView = holder.binding.ivAppIcon
        )

        holder.binding.cbSelect.isChecked = isSelected(app.packageName)

        holder.binding.root.setOnClickListener {
            onToggleSelection(app.packageName)
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos)
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
