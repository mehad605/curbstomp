package com.mhm.curbstomp.data.local

import com.mhm.curbstomp.data.models.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SettingsStoreTest {

    private class CountingStorage(initial: Settings = Settings()) : SettingsStorage {
        @Volatile
        var current: Settings = initial
        val reads = AtomicInteger(0)
        val writes = AtomicInteger(0)

        override fun read(): Settings {
            reads.incrementAndGet()
            return current
        }

        override fun write(settings: Settings) {
            writes.incrementAndGet()
            current = settings
        }
    }

    @Test
    fun lazyLoadReadsOnceAndSkipsNoOpWrites() = runBlocking {
        val storage = CountingStorage(Settings(extraHardenEnabled = true))
        val store = SettingsStore(storage, Dispatchers.Unconfined)

        assertEquals(0, storage.reads.get())
        val loaded = store.data.first()
        assertEquals(true, loaded.extraHardenEnabled)
        assertEquals(1, storage.reads.get())

        // Second read uses in-memory StateFlow without re-reading disk
        store.data.first()
        assertEquals(1, storage.reads.get())

        // No-op mutation must not touch disk
        store.updateData { it }
        assertEquals(0, storage.writes.get())

        // Actual mutation writes once and updates StateFlow
        store.updateData { it.copy(antiUninstallEnabled = true) }
        assertEquals(1, storage.writes.get())
        assertEquals(true, store.data.first().antiUninstallEnabled)
    }

    @Test
    fun concurrentUpdatesAreSerializedWithoutLostWrites() = runBlocking {
        val storage = CountingStorage(Settings())
        val store = SettingsStore(storage, Dispatchers.Default)

        val jobs = (1..25).map { idx ->
            async(Dispatchers.Default) {
                store.updateData { current ->
                    val nextMap = current.appUsageMillis.toMutableMap()
                    nextMap["k_$idx"] = idx.toLong()
                    current.copy(appUsageMillis = nextMap)
                }
            }
        }
        jobs.awaitAll()

        val finalSettings = store.data.first()
        assertEquals(25, finalSettings.appUsageMillis.size)
        assertEquals(25, storage.current.appUsageMillis.size)
        assertEquals(1, storage.reads.get())
        assertEquals(25, storage.writes.get())
    }
}
