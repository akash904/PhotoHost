package io.github.akash904.photohost.desktop

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone's server and this one must create the same database: one day a library will be copied
 * between them, and until then the same client reads both. Room exports each schema with an
 * identity hash of its tables, so the check is that both apps' latest exports match.
 *
 * Possible as a test only since both apps share one repository; before that it was done by eye.
 */
class SchemaParityTest {

    private val schemaDir = "schemas/io.github.akash904.photohost.data.db.AppDatabase"

    @Test
    fun `the desktop and phone databases have the same schema`() {
        val desktop = File(schemaDir)
        val phone = File("../android/app/$schemaDir")
        assertTrue(phone.isDirectory, "phone schemas not found at ${phone.absolutePath}")

        val latest = { dir: File -> dir.listFiles { f -> f.name.endsWith(".json") }!!.maxBy { it.nameWithoutExtension.toInt() } }
        val d = latest(desktop)
        val p = latest(phone)
        assertEquals(p.name, d.name, "the two apps are on different schema versions")
        assertEquals(identity(p), identity(d), "same version, different tables: ${d.name}")
    }

    private fun identity(file: File): String =
        Regex("\"identityHash\"\\s*:\\s*\"([0-9a-f]+)\"").find(file.readText())!!.groupValues[1]
}
