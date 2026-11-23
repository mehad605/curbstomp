package com.mhm.curbstomp.ui.common

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared, bounded, lifecycle-safe asynchronous loader for installed app icons and labels.
 * Eliminates synchronous `PackageManager` IPCs on the main thread and prevents duplicate
 * per-Activity caches. Cleared automatically via [com.mhm.curbstomp.Curbstomp.onTrimMemory].
 */
class AppIconLoader(context: Context) {

    private val packageManager = context.applicationContext.packageManager
    private val iconCache = LruCache<String, Drawable>(MAX_CACHED_ICONS)
    private val labelCache = LruCache<String, String>(MAX_CACHED_LABELS)

    companion object {
        private const val MAX_CACHED_ICONS = 48
        private const val MAX_CACHED_LABELS = 128
    }

    fun getCachedIcon(packageName: String): Drawable? = iconCache.get(packageName)

    fun getCachedLabel(packageName: String): String? = labelCache.get(packageName)

    /**
     * Loads the icon (and optionally the label) for [packageName] into [iconView] and [labelView].
     * Returns immediately without spawning a coroutine when cached; otherwise loads on
     * [Dispatchers.Default] and applies on [Dispatchers.Main] if [iconView] is still bound to
     * [packageName].
     */
    fun bind(
        scope: CoroutineScope,
        packageName: String,
        iconView: ImageView,
        labelView: TextView? = null
    ): Job? {
        iconView.tag = packageName

        val cachedIcon = iconCache.get(packageName)
        val cachedLabel = labelCache.get(packageName)

        if (labelView != null) {
            labelView.text = cachedLabel ?: packageName
        }

        if (cachedIcon != null && (labelView == null || cachedLabel != null)) {
            iconView.setImageDrawable(cachedIcon)
            return null
        }

        iconView.setImageDrawable(cachedIcon)
        return scope.launch(Dispatchers.Default) {
            val resolved = loadFromPackageManager(packageName)
            withContext(Dispatchers.Main) {
                if (iconView.tag == packageName) {
                    if (resolved.icon != null) {
                        iconView.setImageDrawable(resolved.icon)
                    }
                    if (labelView != null) {
                        labelView.text = resolved.label
                    }
                }
            }
        }
    }

    private data class ResolvedAppMeta(val label: String, val icon: Drawable?)

    private fun loadFromPackageManager(packageName: String): ResolvedAppMeta {
        val existingLabel = labelCache.get(packageName)
        val existingIcon = iconCache.get(packageName)
        if (existingLabel != null && existingIcon != null) {
            return ResolvedAppMeta(existingLabel, existingIcon)
        }
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val label = existingLabel ?: packageManager.getApplicationLabel(appInfo).toString().also {
                labelCache.put(packageName, it)
            }
            val icon = existingIcon ?: packageManager.getApplicationIcon(appInfo).also {
                iconCache.put(packageName, it)
            }
            ResolvedAppMeta(label, icon)
        } catch (_: Exception) {
            val fallbackLabel = existingLabel ?: packageName
            ResolvedAppMeta(fallbackLabel, existingIcon)
        }
    }

    fun clear() {
        iconCache.evictAll()
        labelCache.evictAll()
    }
}
