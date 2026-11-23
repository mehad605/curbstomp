package com.mhm.curbstomp.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mhm.curbstomp.appContainer
import com.mhm.curbstomp.data.models.AppInfo
import com.mhm.curbstomp.databinding.ActivitySelectAppsBinding
import com.mhm.curbstomp.ui.adapter.SelectAppsAdapter
import kotlinx.coroutines.launch
import java.util.Locale

class SelectAppsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "TITLE"
        const val EXTRA_PRE_SELECTED_APPS = "PRE_SELECTED_APPS"
        const val EXTRA_SELECTED_APPS = "SELECTED_APPS"
        private const val STATE_SELECTED_APPS = "state_selected_apps"
    }

    private lateinit var binding: ActivitySelectAppsBinding
    private lateinit var appAdapter: SelectAppsAdapter
    private val selectedApps = linkedSetOf<String>()
    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "Select Apps"

        val initialSelection = savedInstanceState?.getStringArrayList(STATE_SELECTED_APPS)
            ?: intent.getStringArrayListExtra(EXTRA_PRE_SELECTED_APPS)
            ?: emptyList()
        selectedApps.addAll(initialSelection)

        appAdapter = SelectAppsAdapter(
            scope = lifecycleScope,
            iconLoader = appContainer.appIconLoader,
            isSelected = { pkg -> selectedApps.contains(pkg) },
            onToggleSelection = { pkg ->
                if (!selectedApps.add(pkg)) {
                    selectedApps.remove(pkg)
                }
            }
        )
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = appAdapter

        lifecycleScope.launch {
            allApps = appContainer.installedAppsRepository.getLaunchableApps()
            filterList(binding.searchView.query?.toString())
            setupSearch()
        }

        binding.btnConfirm.setOnClickListener {
            val resultIntent = Intent().apply {
                putStringArrayListExtra(EXTRA_SELECTED_APPS, ArrayList(selectedApps))
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED_APPS, ArrayList(selectedApps))
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
}
