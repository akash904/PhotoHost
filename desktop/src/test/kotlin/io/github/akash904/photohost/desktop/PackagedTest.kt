package io.github.akash904.photohost.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PackagedTest {

    /**
     * A test run is never inside a package. Asserting Windows' own error code, not just "false",
     * proves the call reached kernel32: a broken binding would also read as "not packaged".
     */
    @Test
    fun `outside a package Windows says so`() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        assertEquals(15700, Packaged.probe())
        assertFalse(Packaged.isPackaged)
    }
}
