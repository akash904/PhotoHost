package io.github.akash904.photohost.server

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class NetInterfacesTest {

    @Test
    fun `only Wi-Fi, Ethernet and Tailscale are advertised, while the certificate still sees everything`() {
        val all = NetInterfaces.endpoints()
        val shown = NetInterfaces.displayEndpoints()
        println("all endpoints:      $all")
        println("advertised:         $shown")
        assertTrue(shown.all { it.label in setOf("Wi-Fi", "Ethernet", "Tailscale") }, shown.toString())
        assertTrue(all.containsAll(shown))
    }
}
