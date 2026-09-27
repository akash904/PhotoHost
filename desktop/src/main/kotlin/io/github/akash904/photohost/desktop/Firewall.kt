package io.github.akash904.photohost.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.W32Errors
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.IntByReference
import io.github.akash904.photohost.core.Log
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "gpic"

/**
 * Whether Windows Firewall lets phones reach PhotoHost, and one button to make it so.
 *
 * The installer cannot add the rule: it installs per user without an administrator prompt, which is
 * what the Microsoft Store expects of an .exe installer, and firewall rules need an administrator.
 * Until now PhotoHost relied on the prompt Windows shows the first time a program listens. That
 * works when the user clicks Allow, and fails silently for good when they click Cancel: Windows then
 * writes a BLOCK rule for the program, the prompt never comes back, and phones simply cannot connect.
 * Nothing in PhotoHost said why.
 *
 * So the window reports the state, and offers to fix it with a single elevated step. Reading needs no
 * administrator; only the fix does, and it asks Windows for that itself (UAC) at the moment the user
 * clicks.
 *
 * Private and public networks are both checked whatever is connected now, because Tailscale's adapter
 * counts as public: checking only today's networks would report "fine" at home and break the moment
 * Tailscale came up. The pairing token still guards every request, whichever network it arrives on.
 *
 * Only Windows' own firewall is visible here. A third-party firewall can still block PhotoHost, and
 * a rule that opens a port rather than naming the program is not counted.
 */
object Firewall {

    const val DOMAIN = 1
    const val PRIVATE = 2
    const val PUBLIC = 4

    private const val DIR_IN = 1
    private const val ACTION_ALLOW = 1
    private const val PROTO_TCP = 6
    private const val PROTO_ANY = 256

    /** The rule this button creates; the name is what Windows Defender Firewall's list shows. */
    const val RULE_NAME = "PhotoHost"

    /** One firewall rule that names a program, as Windows reports it. */
    data class Rule(
        val app: String,
        val inbound: Boolean,
        val allow: Boolean,
        val enabled: Boolean,
        val profiles: Int,
        val protocol: Int,
    )

    /** Everything the verdict depends on. Masks use [DOMAIN], [PRIVATE] and [PUBLIC]. */
    data class Policy(
        /** Profiles of the networks connected right now. */
        val current: Int,
        /** Profiles on which the firewall is switched on at all. */
        val enabled: Int,
        /** Profiles set to "block all incoming connections", which no rule can override. */
        val blockAll: Int,
        val rules: List<Rule>,
    )

    sealed interface State {
        /** Phones can connect on every network that matters. */
        data object Open : State

        /** Not allowed on [profiles]; [blockedByRule] when a block rule is the reason, e.g. a Cancel on Windows' prompt. */
        data class NotAllowed(val profiles: Int, val blockedByRule: Boolean) : State

        /** "Block all incoming connections" is on for [profiles]. A rule cannot fix that; only the user's setting can. */
        data class BlockAll(val profiles: Int) : State

        /** A development run, or the check itself failed. */
        data class Unknown(val reason: String) : State
    }

    /**
     * The verdict for [exe]. Pure, so it can be tested without a firewall.
     *
     * Per profile, in Windows' own order: firewall off lets everything in; "block all" beats every
     * rule; a block rule beats an allow rule; with neither, the connection is refused.
     */
    fun evaluate(policy: Policy, exe: File): State {
        val profiles = PRIVATE or PUBLIC or (policy.current and DOMAIN)
        val mine = policy.rules.filter {
            it.inbound && it.enabled && (it.protocol == PROTO_TCP || it.protocol == PROTO_ANY) && samePath(it.app, exe)
        }
        var blockAll = 0
        var notAllowed = 0
        var byRule = false
        for (p in listOf(DOMAIN, PRIVATE, PUBLIC)) {
            if (profiles and p == 0 || policy.enabled and p == 0) continue
            val applying = mine.filter { it.profiles and p != 0 }
            when {
                policy.blockAll and p != 0 -> blockAll = blockAll or p
                applying.any { !it.allow } -> { notAllowed = notAllowed or p; byRule = true }
                applying.none { it.allow } -> notAllowed = notAllowed or p
            }
        }
        return when {
            blockAll != 0 -> State.BlockAll(blockAll)
            notAllowed != 0 -> State.NotAllowed(notAllowed, byRule)
            else -> State.Open
        }
    }

    /** "private and public networks", for messages. */
    fun describe(profiles: Int): String = buildList {
        if (profiles and DOMAIN != 0) add("work (domain)")
        if (profiles and PRIVATE != 0) add("private")
        if (profiles and PUBLIC != 0) add("public")
    }.joinToString(" and ") + " networks"

    /** Reads the policy and judges the running PhotoHost.exe. Blocking; call off the UI thread. */
    fun check(): State {
        val exe = Autostart.executable ?: return State.Unknown("Available when running the installed PhotoHost.exe")
        return try {
            evaluate(readPolicy(), exe)
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the firewall rules", t)
            State.Unknown("Could not read the Windows Firewall settings")
        }
    }

    sealed interface AllowResult {
        data object Done : AllowResult
        data object Declined : AllowResult
        data class Failed(val message: String) : AllowResult
    }

    /**
     * Replaces every inbound rule for this PhotoHost.exe with one that allows TCP on all networks.
     *
     * Every inbound rule for the program goes, not only block rules: after a Cancel, Windows leaves
     * a block rule for one network type and nothing for another, and after an Allow it leaves one
     * rule per protocol. One rule, named after the app, is easy to find and remove by hand.
     * TCP only, because that is all PhotoHost listens on.
     *
     * One administrator prompt, for one cmd.exe that runs both netsh commands. Blocking until the
     * user answers the prompt and netsh finishes; call off the UI thread.
     */
    fun allow(): AllowResult {
        val exe = Autostart.executable ?: return AllowResult.Failed("Only the installed PhotoHost.exe can be allowed")
        // netsh takes the program path in double quotes, and a Windows path cannot contain one.
        val path = exe.absolutePath
        val params = "/c netsh advfirewall firewall delete rule name=all dir=in program=\"$path\"" +
            " & netsh advfirewall firewall add rule name=\"$RULE_NAME\" dir=in action=allow" +
            " program=\"$path\" enable=yes profile=any protocol=tcp" +
            " description=\"Lets your phones reach the PhotoHost photo library. Added from the PhotoHost window.\""

        val info = ShellAPI.SHELLEXECUTEINFO().apply {
            cbSize = size()
            fMask = SEE_MASK_NOCLOSEPROCESS or SEE_MASK_NOASYNC
            lpVerb = "runas"
            lpFile = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\cmd.exe").absolutePath
            lpParameters = params
            nShow = 0 // SW_HIDE: two netsh lines are not worth a console window
        }
        if (!Shell32.INSTANCE.ShellExecuteEx(info)) {
            val error = Native.getLastError()
            return if (error == W32Errors.ERROR_CANCELLED) {
                Log.i(TAG, "firewall: administrator prompt declined")
                AllowResult.Declined
            } else {
                Log.w(TAG, "firewall: could not start netsh, error $error")
                AllowResult.Failed("Windows could not start the firewall change (error $error)")
            }
        }
        val process = info.hProcess ?: return AllowResult.Failed("Windows did not report the firewall change")
        try {
            if (Kernel32.INSTANCE.WaitForSingleObject(process, 60_000) != WinBase.WAIT_OBJECT_0) {
                return AllowResult.Failed("The firewall change did not finish")
            }
            val code = IntByReference()
            Kernel32.INSTANCE.GetExitCodeProcess(process, code)
            Log.i(TAG, "firewall: netsh finished with ${code.value}")
            // The exit code is the add's; the delete legitimately fails when there was nothing to
            // delete. The caller re-reads the rules either way, which is the real test.
            return if (code.value == 0) AllowResult.Done else AllowResult.Failed("netsh reported error ${code.value}")
        } finally {
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }

    private const val SEE_MASK_NOCLOSEPROCESS = 0x00000040
    private const val SEE_MASK_NOASYNC = 0x00000100

    /**
     * Through the firewall's COM API (HNetCfg.FwPolicy2) in a PowerShell child, which any user may
     * read. Only rules that name a program are listed; the path goes last so nothing in it can shift
     * the fields. UTF-8 output, or a path with non-ASCII characters would not compare equal.
     */
    private val SCRIPT = """
        [Console]::OutputEncoding = [Text.Encoding]::UTF8
        ${'$'}p = New-Object -ComObject HNetCfg.FwPolicy2
        'P|' + ${'$'}p.CurrentProfileTypes
        foreach (${'$'}t in 1, 2, 4) { 'F|' + ${'$'}t + '|' + [int]${'$'}p.FirewallEnabled(${'$'}t) + '|' + [int]${'$'}p.BlockAllInboundTraffic(${'$'}t) }
        foreach (${'$'}r in ${'$'}p.Rules) {
            if (${'$'}r.ApplicationName) { 'R|' + ${'$'}r.Direction + '|' + ${'$'}r.Action + '|' + [int]${'$'}r.Enabled + '|' + ${'$'}r.Profiles + '|' + ${'$'}r.Protocol + '|' + ${'$'}r.ApplicationName }
        }
    """.trimIndent()

    internal fun readPolicy(): Policy {
        val powershell = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
        val encoded = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(powershell.absolutePath, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("firewall query timed out")
        }
        return parse(output)
    }

    internal fun parse(output: String): Policy {
        var current = -1
        var enabled = 0
        var blockAll = 0
        val rules = mutableListOf<Rule>()
        for (line in output.lineSequence().map { it.trim().removePrefix("\uFEFF") }) {
            val f = line.split('|', limit = 7)
            when (f[0]) {
                "P" -> current = f[1].toInt()
                "F" -> {
                    val p = f[1].toInt()
                    if (f[2] == "1") enabled = enabled or p
                    if (f[3] == "1") blockAll = blockAll or p
                }
                "R" -> if (f.size == 7) rules += Rule(
                    app = f[6],
                    inbound = f[1].toInt() == DIR_IN,
                    allow = f[2].toInt() == ACTION_ALLOW,
                    enabled = f[3] == "1",
                    profiles = f[4].toInt(),
                    protocol = f[5].toInt(),
                )
            }
        }
        check(current >= 0) { "no firewall profile in the output" }
        return Policy(current, enabled, blockAll, rules)
    }

    /**
     * Windows stores the path as it was given, which for rules from its own prompt means lower
     * case, and a rule may use %VARIABLES%. Paths on Windows compare without case.
     */
    private fun samePath(rulePath: String, exe: File): Boolean {
        val expanded = Regex("%([^%]+)%").replace(rulePath) { System.getenv(it.groupValues[1]) ?: it.value }
        return runCatching {
            File(expanded).toPath().toAbsolutePath().normalize().toString()
                .equals(exe.toPath().toAbsolutePath().normalize().toString(), ignoreCase = true)
        }.getOrDefault(false)
    }
}
