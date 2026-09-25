package dev.gpicalter.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Stand-in for `android.util.Log` with the same call shapes, so files copied from gpicAlter change
 * one import line and nothing else. Keeping the copied code textually close to the phone's is what
 * makes the later extraction into a shared module a move rather than a merge.
 *
 * Writes to stderr and, once [file] is set, appends to a log file -- a tray app has no console
 * anyone is watching, so without the file a failure on a user's machine leaves no trace at all.
 */
object Log {

    @Volatile
    var file: File? = null

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun i(tag: String, msg: String) = write('I', tag, msg, null)

    fun w(tag: String, msg: String, t: Throwable? = null) = write('W', tag, msg, t)

    fun e(tag: String, msg: String, t: Throwable? = null) = write('E', tag, msg, t)

    @Synchronized
    private fun write(level: Char, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(stamp)).append(' ').append(level).append('/')
            append(tag).append(": ").append(msg)
            if (t != null) {
                append('\n')
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                append(sw.toString().trimEnd())
            }
        }
        System.err.println(line)
        file?.let { f -> runCatching { f.appendText(line + "\n") } }
    }
}
