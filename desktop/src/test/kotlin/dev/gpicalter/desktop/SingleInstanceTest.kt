package dev.gpicalter.desktop

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {

    @Test
    fun `a second launch is refused the lock and brings the first one forward`() {
        val dir = Files.createTempDirectory("photohost-instance").toFile()
        val first = SingleInstance(dir)
        try {
            assertTrue(first.acquire(), "the first instance must get the lock")
            val shown = CountDownLatch(1)
            first.listen { shown.countDown() }

            val second = SingleInstance(dir)
            assertFalse(second.acquire(), "a second instance on the same data folder must not start")
            assertTrue(second.signalRunning(), "the running instance should answer")
            assertTrue(shown.await(5, TimeUnit.SECONDS), "the running instance should have been asked to show")
        } finally {
            first.release()
        }

        // Released: the next launch is the one PhotoHost again.
        val third = SingleInstance(dir)
        try {
            assertTrue(third.acquire())
        } finally {
            third.release()
            dir.deleteRecursively()
        }
    }
}
