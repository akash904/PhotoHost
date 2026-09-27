package io.github.akash904.photohost.core

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Copies [this] to [out] while hashing in the same pass, so the content hash of an imported file
 * falls out of the copy for free. The alternative -- hash, then copy -- reads every byte off the
 * USB drive twice, which at library scale is hours of avoidable I/O.
 *
 * Returns the SHA-256 as lowercase hex and the byte count. Neither stream is closed.
 */
fun InputStream.copyHashing(out: OutputStream, bufferSize: Int = 256 * 1024): HashedCopy {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (n == 0) continue
        digest.update(buf, 0, n)
        out.write(buf, 0, n)
        total += n
    }
    out.flush()
    return HashedCopy(digest.digest().toHex(), total)
}

/** Hashes [this] without writing anywhere. */
fun InputStream.sha256(bufferSize: Int = 256 * 1024): HashedCopy {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (n == 0) continue
        digest.update(buf, 0, n)
        total += n
    }
    return HashedCopy(digest.digest().toHex(), total)
}

/** Hashes at most [limit] bytes -- the cheap "head hash" pre-filter from the plan. */
fun InputStream.sha256Prefix(limit: Long, bufferSize: Int = 64 * 1024): HashedCopy {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(bufferSize)
    var total = 0L
    while (total < limit) {
        val want = minOf(bufferSize.toLong(), limit - total).toInt()
        val n = read(buf, 0, want)
        if (n < 0) break
        if (n == 0) continue
        digest.update(buf, 0, n)
        total += n
    }
    return HashedCopy(digest.digest().toHex(), total)
}

data class HashedCopy(val sha256: String, val bytes: Long)
