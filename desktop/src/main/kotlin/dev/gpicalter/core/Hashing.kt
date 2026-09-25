package dev.gpicalter.core

import java.io.InputStream
import java.security.MessageDigest

data class DualHash(val contentHash: String, val headHash: String, val bytes: Long)

/**
 * Computes the full SHA-256 and the 64 KiB head hash in a **single** pass.
 *
 * Two hashes, one read. Reading the file twice would double the I/O on the slowest part of the
 * system, and on a USB disk that is the difference between an overnight scan and a two-night one.
 *
 * The head hash exists for rescans where mtime cannot be trusted -- exFAT after an unclean unmount,
 * or files touched by a PC. It narrows "have I seen these bytes before" from a full read down to
 * 64 KiB, and only a head+size collision against a *different* stored hash costs a full re-read.
 */
fun InputStream.dualHash(headBytes: Int = 64 * 1024, bufferSize: Int = 256 * 1024): DualHash {
    val full = MessageDigest.getInstance("SHA-256")
    val head = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (n == 0) continue
        full.update(buf, 0, n)
        if (total < headBytes) {
            val take = minOf(n.toLong(), headBytes - total).toInt()
            head.update(buf, 0, take)
        }
        total += n
    }
    return DualHash(full.digest().toHex(), head.digest().toHex(), total)
}
