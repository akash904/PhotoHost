package dev.gpicalter.desktop

import dev.gpicalter.desktop.Firewall.PRIVATE
import dev.gpicalter.desktop.Firewall.PUBLIC
import dev.gpicalter.desktop.Firewall.DOMAIN
import dev.gpicalter.desktop.Firewall.State
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

class FirewallTest {

    private val exe = File("C:\\Users\\someone\\AppData\\Local\\Programs\\PhotoHost\\PhotoHost.exe")
    private val all = DOMAIN or PRIVATE or PUBLIC

    private fun rule(allow: Boolean, profiles: Int, protocol: Int = 6, app: String = exe.path.lowercase(), enabled: Boolean = true) =
        Firewall.Rule(app, inbound = true, allow = allow, enabled = enabled, profiles = profiles, protocol = protocol)

    private fun policy(vararg rules: Firewall.Rule, current: Int = PRIVATE, enabled: Int = all, blockAll: Int = 0) =
        Firewall.Policy(current, enabled, blockAll, rules.toList())

    @Test
    fun `what clicking Allow on Windows' prompt leaves behind is open`() {
        // As on this PC: one TCP and one UDP rule, private and public, path in lower case.
        val p = policy(rule(true, PRIVATE or PUBLIC, 6), rule(true, PRIVATE or PUBLIC, 17))
        assertEquals(State.Open, Firewall.evaluate(p, exe))
    }

    @Test
    fun `Cancel on Windows' prompt blocks, and says a rule is the reason`() {
        // Windows allows nothing and writes block rules for the program.
        val p = policy(rule(false, PRIVATE or PUBLIC, 6), rule(false, PRIVATE or PUBLIC, 17))
        assertEquals(State.NotAllowed(PRIVATE or PUBLIC, blockedByRule = true), Firewall.evaluate(p, exe))
    }

    @Test
    fun `allowed at home only is not enough, because Tailscale is a public network`() {
        val p = policy(rule(true, PRIVATE))
        assertEquals(State.NotAllowed(PUBLIC, blockedByRule = false), Firewall.evaluate(p, exe))
    }

    @Test
    fun `a block rule beats an allow rule`() {
        val p = policy(rule(true, all), rule(false, PUBLIC))
        assertEquals(State.NotAllowed(PUBLIC, blockedByRule = true), Firewall.evaluate(p, exe))
    }

    @Test
    fun `no rule at all is not allowed`() {
        assertEquals(State.NotAllowed(PRIVATE or PUBLIC, blockedByRule = false), Firewall.evaluate(policy(), exe))
    }

    @Test
    fun `rules for other programs, disabled rules and UDP-only rules do not count`() {
        val p = policy(
            rule(true, all, app = "C:\\Elsewhere\\PhotoHost.exe"),
            rule(true, all, enabled = false),
            rule(true, all, protocol = 17),
        )
        assertEquals(State.NotAllowed(PRIVATE or PUBLIC, blockedByRule = false), Firewall.evaluate(p, exe))
    }

    @Test
    fun `the rule the button adds is open`() {
        // profile=any protocol=tcp; Windows reports "any" as all bits set.
        val p = policy(rule(true, 0x7FFFFFFF, 6, app = exe.path))
        assertEquals(State.Open, Firewall.evaluate(p, exe))
    }

    @Test
    fun `a firewall switched off lets everything in`() {
        assertEquals(State.Open, Firewall.evaluate(policy(enabled = 0), exe))
    }

    @Test
    fun `block all incoming is reported separately, since a rule cannot fix it`() {
        val p = policy(rule(true, all), blockAll = PUBLIC)
        assertEquals(State.BlockAll(PUBLIC), Firewall.evaluate(p, exe))
    }

    @Test
    fun `the domain profile only matters while on a domain network`() {
        val allowedHomeAndPublic = rule(true, PRIVATE or PUBLIC)
        assertEquals(State.Open, Firewall.evaluate(policy(allowedHomeAndPublic, current = PRIVATE), exe))
        assertEquals(
            State.NotAllowed(DOMAIN, blockedByRule = false),
            Firewall.evaluate(policy(allowedHomeAndPublic, current = DOMAIN), exe),
        )
    }

    @Test
    fun `the query runs against this PC's firewall without administrator rights`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val p = Firewall.readPolicy()
        assert(p.current > 0) { "no connected network profile reported" }
        assert(p.rules.isNotEmpty()) { "Windows always has program rules; none were read" }
        val built = File("build/package/PhotoHost/PhotoHost.exe").absoluteFile
        println("firewall verdict for $built: ${Firewall.evaluate(p, built)}")
    }

    @Test
    fun `parses what the PowerShell query prints`() {
        val out = "\uFEFFP|6\r\nF|1|1|0\r\nF|2|1|0\r\nF|4|1|1\r\n" +
            "R|1|1|1|6|6|e:\\extracuric\\img\\photohostpc\\build\\package\\photohost\\photohost.exe\r\n" +
            "R|2|0|0|7|256|C:\\Program Files\\A|B\\x.exe\r\n"
        val p = Firewall.parse(out)
        assertEquals(6, p.current)
        assertEquals(all, p.enabled)
        assertEquals(PUBLIC, p.blockAll)
        assertEquals(2, p.rules.size)
        assertEquals(Firewall.Rule("e:\\extracuric\\img\\photohostpc\\build\\package\\photohost\\photohost.exe", true, true, true, 6, 6), p.rules[0])
        // Fields after the sixth separator are the path, even with a stray '|' in it.
        assertEquals(Firewall.Rule("C:\\Program Files\\A|B\\x.exe", false, false, false, 7, 256), p.rules[1])
    }
}
