package com.mhm.curbstomp.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.util.LruCache
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
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
    private var filteredApps: List<AppItem> = emptyList()
    private val iconCache = LruCache<String, Drawable>(48)

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

        binding.appList.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            loadApps()
            setupSearch()
            setupAdapter()
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

    private suspend fun loadApps() = withContext(Dispatchers.Default) {
        val uniqueList = com.mhm.curbstomp.utils.AppCache.getApps(this@SelectAppsActivity)
        allApps = uniqueList
        filteredApps = uniqueList
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
        if (query.isNullOrBlank()) {
            filteredApps = allApps
        } else {
            val lowerQuery = query.lowercase(Locale.ROOT)
            filteredApps = allApps.filter {
                it.label.lowercase(Locale.ROOT).contains(lowerQuery) ||
                    it.packageName.lowercase(Locale.ROOT).contains(lowerQuery)
            }
        }
        (binding.appList.adapter as? AppAdapter)?.notifyDataSetChanged()
    }

    private fun setupAdapter() {
        binding.appList.adapter = AppAdapter()
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

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
            val app = filteredApps[position]
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

        override fun getItemCount(): Int = filteredApps.size
    }
}
