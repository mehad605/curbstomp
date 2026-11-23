package com.mhm.curbstomp.data.local

import android.util.AtomicFile
import com.mhm.curbstomp.data.models.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Low-level persistence seam allowing [SettingsStore] and [com.mhm.curbstomp.data.repository.SettingsRepository]
 * to be unit-tested on the JVM without Robolectric.
 */
interface SettingsStorage {
    fun read(): Settings
    fun write(settings: Settings)
}

/**
 * Crash-safe [SettingsStorage] backed by Android's [AtomicFile].
 */
class AtomicFileSettingsStorage(private val file: File) : SettingsStorage {

    private val atomicFile = AtomicFile(file)

    override fun read(): Settings {
        return try {
            val bytes = atomicFile.readFully()
            SettingsJson.fromJson(bytes.toString(Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            Settings()
        } catch (_: Exception) {
            Settings()
        }
    }

    override fun write(settings: Settings) {
        file.parentFile?.mkdirs()
        val stream = atomicFile.startWrite()
        try {
            stream.write(SettingsJson.toJson(settings).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            atomicFile.failWrite(stream)
            throw e
        }
    }
}

/**
 * Coroutine-serialized local store for [Settings].
 * Uses a [Mutex] to guarantee ordered read-modify-write transitions and skips disk I/O
 * when a mutation produces no change.
 */
class SettingsStore(
    private val storage: SettingsStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    constructor(file: File) : this(AtomicFileSettingsStorage(file))

    private val mutex = Mutex()
    private val state = MutableStateFlow(Settings())

    @Volatile
    private var loaded = false

    val data: Flow<Settings> = flow {
        ensureLoaded()
        emitAll(state)
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            ensureLoadedLocked()
        }
    }

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val diskSettings = withContext(ioDispatcher) { storage.read() }
        state.value = diskSettings
        loaded = true
    }

    suspend fun updateData(transform: (Settings) -> Settings): Settings {
        return mutex.withLock {
            ensureLoadedLocked()
            val current = state.value
            val updated = transform(current)
            if (updated == current) return@withLock current

            withContext(ioDispatcher) {
                storage.write(updated)
            }
            state.value = updated
            updated
        }
    }
}
