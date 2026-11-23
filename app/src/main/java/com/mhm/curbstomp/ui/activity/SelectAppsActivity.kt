package com.mhm.curbstomp.ui.activity

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mhm.curbstomp.databinding.ActivitySelectAppsBinding
import com.mhm.curbstomp.databinding.SelectAppsItemBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SelectAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectAppsBinding
    private lateinit var selectedApps: HashSet<String>
    private var allApps: List<AppItem> = emptyList()
    private val iconCache = LruCache<String, Drawable>(48)
    private lateinit var appAdapter: AppAdapter

    data class AppItem(
        val label: String,
        val packageName: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra("TITLE") ?: "Select Apps"
        binding.toolbarTitle.text = title

        val preSelected = intent.getStringArrayListExtra("PRE_SELECTED_APPS") ?: emptyList()
        selectedApps = HashSet(preSelected)

        appAdapter = AppAdapter()
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = appAdapter

        lifecycleScope.launch {
            loadApps()
            setupSearch()
        }

        binding.btnConfirm.setOnClickListener {
            val resultIntent = Intent().apply {
                putStringArrayListExtra("SELECTED_APPS", ArrayList(selectedApps))
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onDestroy() {
        iconCache.evictAll()
        super.onDestroy()
    }

    private suspend fun loadApps() {
        val uniqueList = withContext(Dispatchers.Default) {
            com.mhm.curbstomp.utils.AppCache.getApps(this@SelectAppsActivity)
        }
        allApps = uniqueList
        appAdapter.submitList(uniqueList)
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterList(newText)
                return true
            }
        })
    }

    private fun filterList(query: String?) {
        val nextList = if (query.isNullOrBlank()) {
            allApps
        } else {
            val lowerQuery = query.lowercase(Locale.ROOT)
            allApps.filter {
                it.label.lowercase(Locale.ROOT).contains(lowerQuery) ||
                    it.packageName.lowercase(Locale.ROOT).contains(lowerQuery)
            }
        }
        appAdapter.submitList(nextList)
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        private var items: List<AppItem> = emptyList()

        fun submitList(newItems: List<AppItem>) {
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

        inner class ViewHolder(val itemBinding: SelectAppsItemBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {
            var iconJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = SelectAppsItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            holder.iconJob?.cancel()
            holder.iconJob = null
            super.onViewRecycled(holder)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = items[position]
            holder.itemBinding.tvAppName.text = app.label

            holder.iconJob?.cancel()
            holder.iconJob = null
            holder.itemBinding.ivAppIcon.tag = app.packageName

            val cached = iconCache.get(app.packageName)
            if (cached != null) {
                holder.itemBinding.ivAppIcon.setImageDrawable(cached)
            } else {
                holder.itemBinding.ivAppIcon.setImageDrawable(null)
                holder.iconJob = lifecycleScope.launch(Dispatchers.Default) {
                    val icon = try {
                        packageManager.getApplicationIcon(app.packageName)
                    } catch (_: Exception) {
                        null
                    }
                    if (icon != null) {
                        iconCache.put(app.packageName, icon)
                        withContext(Dispatchers.Main) {
                            if (holder.itemBinding.ivAppIcon.tag == app.packageName) {
                                holder.itemBinding.ivAppIcon.setImageDrawable(icon)
                            }
                        }
                    }
                }
            }

            holder.itemBinding.cbSelect.isChecked = selectedApps.contains(app.packageName)

            holder.itemBinding.root.setOnClickListener {
                if (selectedApps.contains(app.packageName)) {
                    selectedApps.remove(app.packageName)
                } else {
                    selectedApps.add(app.packageName)
                }
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
