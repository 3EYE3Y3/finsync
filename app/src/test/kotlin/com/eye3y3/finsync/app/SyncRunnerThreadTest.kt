package com.eye3y3.finsync.app

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** An ANR in 0.1.0 (1): card and server work ran on the main thread. It must never again. */
class SyncRunnerThreadTest {
    @Test fun workNeverRunsOnTheCallersThread() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            val thread = runBlocking(main.asCoroutineDispatcher()) {
                SyncRunner.exclusive("test") { Thread.currentThread().name }
            }
            assertTrue(thread != null)
            assertNotEquals("fake-main", thread)
        } finally {
            main.shutdown()
        }
    }
}
