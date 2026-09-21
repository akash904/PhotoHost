package dev.gpicalter.probe

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import dev.gpicalter.core.sha256
import dev.gpicalter.core.toHex
import dev.gpicalter.storage.LibraryStore
import dev.gpicalter.storage.SafStore
import dev.gpicalter.storage.StoreEntry
import dev.gpicalter.storage.StoreKind
import dev.gpicalter.storage.joinRel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Random

private const val MIB = 1024L * 1024L
private const val BUF = 256 * 1024
private const val PROBE_DIR = "gpic-probe"

/**
 * M0. Answers the questions the rest of the design depends on, before any product code exists.
 *
 * Runs against either storage backend. Most probes are backend-agnostic because they go through
 * [LibraryStore]; the SAF-specific ones (grant, deterministic document id, DocumentFile
 * benchmark, unplug) report "n/a" on internal storage rather than failing.
 *
 * Running the same probes on internal storage first is genuinely useful, not just a stopgap: it
 * establishes the control case. If throughput or seeking misbehaves on internal storage too, the
 * bug is in this code, not in SAF or the drive.
 */
class ProbeRunner(
    private val context: Context,
    private val store: LibraryStore,
    private val log: (String) -> Unit,
) {
    private val saf: SafStore? = store as? SafStore

    // ---------------------------------------------------------------- probe 1: the backend

    fun backendInfo() {
        section("1. Backend")
        log("kind           = ${store.kind}")
        log("label          = ${store.label}")
        log("mounted        = ${store.isMounted}")
        log("writable       = ${store.isWritable}")

        val s = saf
        if (s == null) {
            log("Internal storage: a plain directory, real POSIX files, no grant to persist and")
            log("no resolution ladder. This is the control case.")
            log("VERDICT: ${if (store.isWritable) "PASS" else "FAIL -- not writable"}")
        } else {
            log("treeUri        = ${s.treeUri}")
            log("volume key     = ${s.volumeKey}")
            log("authority      = ${s.treeUri.authority}")
            if (s.treeUri.authority != "com.android.externalstorage.documents") {
                log("!! NOT ExternalStorageProvider. Probe 4 will likely fail: other providers'")
                log("   document ids are genuinely opaque, so the segment walk becomes the normal")
                log("   resolution path and caching becomes mandatory, not an optimisation.")
            }
            val persisted = context.contentResolver.persistedUriPermissions
            log("persisted      = ${persisted.size} grants / 512 cap")
            persisted.forEach { p ->
                val r = if (p.isReadPermission) "r" else "-"
                val w = if (p.isWritePermission) "w" else "-"
                log("  $r$w ${p.uri}")
            }
            val mine = persisted.firstOrNull { it.uri == s.treeUri }
            when {
                mine == null -> log("VERDICT: FAIL -- this tree is not in the persisted list")
                !mine.isWritePermission -> log("VERDICT: FAIL -- read-only grant, cannot import")
                else -> log("VERDICT: PASS -- persisted read+write grant held")
            }
        }
        log("open fds = ${openFdCount()}")
    }

    // ---------------------------------------------------------------- survey

    suspend fun survey(maxDirs: Int = 400, maxEntries: Int = 100_000): TreeSurvey {
        section("Survey")
        val t0 = System.nanoTime()
        var files = 0
        var dirs = 0
        var largest: StoreEntry? = null
        var largestParent: String? = null
        var fattestDir: String? = null
        var fattestCount = -1

        val queue = ArrayDeque<String>()
        queue.add("")
        var dirsVisited = 0
        var entries = 0

        while (queue.isNotEmpty() && dirsVisited < maxDirs && entries < maxEntries) {
            currentCoroutineContext().ensureActive()
            val parent = queue.removeFirst()
            dirsVisited++
            val children = try {
                store.list(parent)
            } catch (t: Throwable) {
                log("  ! list failed for '$parent': ${t.javaClass.simpleName}: ${t.message}")
                continue
            }
            entries += children.size
            var childFiles = 0
            for (c in children) {
                if (c.isDirectory) {
                    dirs++
                    queue.addLast(c.relPath)
                } else {
                    files++
                    childFiles++
                    val cur = largest
                    if (cur == null || c.size > cur.size) {
                        largest = c
                        largestParent = parent
                    }
                }
            }
            if (childFiles > fattestCount) {
                fattestCount = childFiles
                fattestDir = parent
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        log("visited $dirsVisited dirs, saw $files files / $dirs subdirs in $ms ms")
        if (dirsVisited >= maxDirs) log("  (stopped at the $maxDirs-directory cap)")
        val big = largest
        if (big != null) {
            log("largest file   = ${big.relPath} (${big.size / MIB} MiB)")
        } else {
            log("No files found.")
            if (store.kind == StoreKind.INTERNAL) {
                log("  Push some test files first, e.g.:")
                log("  adb push photo.jpg /sdcard/Android/data/dev.gpicalter/files/library/")
            } else {
                log("  Put a few photos and one large video on the drive first.")
            }
        }
        log("fattest dir    = '${fattestDir ?: ""}' ($fattestCount files)")
        return TreeSurvey(files, dirs, largest, largestParent, fattestDir, fattestCount, ms)
    }

    // ---------------------------------------------------------------- probe 2: seekability

    suspend fun seek(target: StoreEntry) {
        section("2. Seekability (the make-or-break test)")
        log("target = ${target.relPath} (${target.size} bytes)")
        if (target.size < 4 * MIB) {
            log("!! target is under 4 MiB; use a large video for a meaningful test")
        }
        try {
            store.openRead(target.relPath).use { pfd ->
                val fd = pfd.fileDescriptor

                log("pfd.statSize   = ${pfd.statSize}")
                if (pfd.statSize < 0) log("!! statSize -1 means a stream-backed fd: NOT seekable")

                val st = Os.fstat(fd)
                val isReg = OsConstants.S_ISREG(st.st_mode)
                log("fstat st_size  = ${st.st_size}")
                log("S_ISREG        = $isReg  (false => pipe or socket, seeking impossible)")

                val len = minOf(MIB, target.size / 2).toInt().coerceAtLeast(1)
                val offset = ((target.size / 2) / 4096) * 4096

                // Pass A: seek straight to the midpoint.
                val posA = Os.lseek(fd, offset, OsConstants.SEEK_SET)
                log("lseek(to $offset) returned $posA")
                val hashA = readExactly(FileInputStream(fd), len)

                // Pass B: fresh descriptor, same offset reached by sequential skipping. If lseek
                // were being silently ignored these two hashes would differ.
                val hashB = store.openRead(target.relPath).use { p2 ->
                    val ins = FileInputStream(p2.fileDescriptor)
                    var skipped = 0L
                    while (skipped < offset) {
                        val n = ins.skip(offset - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    if (skipped != offset) log("!! sequential skip reached $skipped, wanted $offset")
                    readExactly(ins, len)
                }

                log("lseek hash     = $hashA")
                log("skip  hash     = $hashB")

                val end = Os.lseek(fd, 0, OsConstants.SEEK_END)
                val back = Os.lseek(fd, 0, OsConstants.SEEK_SET)
                log("SEEK_END = $end (expect ${target.size}), back to $back")

                val ok = isReg && pfd.statSize == target.size &&
                    hashA == hashB && end == target.size && back == 0L
                if (ok) {
                    log("VERDICT: PASS -- a real seekable regular file. Range serving is viable.")
                } else if (store.kind == StoreKind.INTERNAL) {
                    log("VERDICT: FAIL on INTERNAL storage -- the bug is in this code, not in SAF.")
                } else {
                    log("VERDICT: FAIL -- stop and redesign. Either copy originals to internal")
                    log("  storage, or accept that video cannot be seeked.")
                }
            }
        } catch (t: Throwable) {
            log("VERDICT: FAIL -- ${t.javaClass.name}: ${t.message}")
        }
        log("open fds = ${openFdCount()}")
    }

    // ---------------------------------------------------------------- probe 4: deterministic id

    fun deterministicDocumentId(target: StoreEntry) {
        section("4. Deterministic document id (validates the resilience scheme)")
        val s = saf
        if (s == null) {
            log("n/a on internal storage -- a relative path IS the address here; there is no")
            log("document id to reconstruct and nothing to lose on a replug.")
            return
        }
        val rebuilt = s.deterministicDocumentId(target.relPath)
        log("relPath        = ${target.relPath}")
        log("rebuilt docId  = $rebuilt")
        log("  (built from volume key + relative path only -- no query, no walk)")

        var opened = false
        var size = -1L
        try {
            context.contentResolver.openFileDescriptor(s.uriFor(rebuilt), "r")?.use {
                opened = true
                size = it.statSize
            }
        } catch (t: Throwable) {
            log("open(rebuilt) threw ${t.javaClass.simpleName}: ${t.message}")
        }
        log("open(rebuilt)  = $opened, statSize = $size (expect ${target.size})")

        if (opened && size == target.size) {
            log("VERDICT: PASS -- (volume serial, relative path) is a sufficient durable key.")
            log("  A brand-new tree grant after a replug resolves every asset with zero walking.")
        } else {
            log("VERDICT: FAIL -- document ids are opaque on this provider.")
            log("  The design still holds, but the segment walk becomes the NORMAL resolution")
            log("  path, so caching is mandatory and replug recovery gets expensive.")
        }
    }

    // ---------------------------------------------------------------- probe 5: capacity

    fun capacity() {
        section("5. Capacity")
        val cap = try {
            store.capacity()
        } catch (t: Throwable) {
            log("VERDICT: FAIL -- ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        if (cap == null) {
            log("VERDICT: FAIL -- the store could not report capacity")
            return
        }
        log("total          = ${cap.totalBytes / MIB} MiB")
        log("available      = ${cap.availableBytes / MIB} MiB")
        log("VERDICT: PASS -- the UI can show real free space.")
    }

    // ---------------------------------------------------------------- probe 3: write/read/verify

    suspend fun writeReadVerify(sizeMiB: Int) {
        section("3. Write + read back + verify ($sizeMiB MiB) on ${store.kind}")
        val rel = joinRel(PROBE_DIR, "probe-${System.currentTimeMillis()}.bin")
        try {
            val block = ByteArray(BUF)
            Random(0xC0FFEEL).nextBytes(block)
            val blocks = (sizeMiB.toLong() * MIB / BUF).toInt()
            val digest = MessageDigest.getInstance("SHA-256")

            val wStart = System.nanoTime()
            store.openWrite(rel).use { pfd ->
                val out = FileOutputStream(pfd.fileDescriptor)
                for (i in 0 until blocks) {
                    currentCoroutineContext().ensureActive()
                    stamp(block, i)
                    digest.update(block)
                    out.write(block)
                    if (i > 0 && i % 256 == 0) log("  wrote ${i.toLong() * BUF / MIB} MiB")
                }
                out.flush()
                runCatching { pfd.fileDescriptor.sync() }
                    .onFailure { log("  fsync refused: ${it.javaClass.simpleName} (expected on exFAT)") }
            }
            val wMs = (System.nanoTime() - wStart) / 1_000_000
            val wroteHash = digest.digest().toHex()
            val bytes = blocks.toLong() * BUF
            log("write          = ${bytes / MIB} MiB in $wMs ms = ${mbPerSec(bytes, wMs)} MB/s")

            val rStart = System.nanoTime()
            val read = store.openRead(rel).use { FileInputStream(it.fileDescriptor).sha256() }
            val rMs = (System.nanoTime() - rStart) / 1_000_000
            log("read           = ${read.bytes / MIB} MiB in $rMs ms = ${mbPerSec(read.bytes, rMs)} MB/s")

            log("wrote hash     = $wroteHash")
            log("read  hash     = ${read.sha256}")
            val intact = wroteHash == read.sha256 && read.bytes == bytes
            val fastEnough = mbPerSecRaw(bytes, wMs) >= 15.0
            when {
                !intact && store.kind == StoreKind.INTERNAL ->
                    log("VERDICT: FAIL on INTERNAL storage -- the bug is in this code, not the drive.")
                !intact -> {
                    log("VERDICT: FAIL -- bytes did not survive the round trip.")
                    log("  Suspect drive power (Risk 2): use a powered USB-C hub with PD passthrough.")
                }
                !fastEnough && store.kind == StoreKind.SAF -> {
                    log("VERDICT: MARGINAL -- intact, but under the 15 MB/s bar.")
                    log("  Check the hub and cable; prefer an SSD over a spinning disk.")
                }
                else -> log("VERDICT: PASS -- bytes intact${if (store.kind == StoreKind.INTERNAL) "" else " and throughput adequate"}.")
            }
            if (store.kind == StoreKind.INTERNAL) {
                log("Keep this number: it is the baseline the USB drive gets compared against.")
            }
        } catch (t: Throwable) {
            log("VERDICT: FAIL -- ${t.javaClass.name}: ${t.message}")
        } finally {
            runCatching { store.delete(rel) }
                .onSuccess { log("cleaned up $rel") }
                .onFailure { log("could not delete $rel: ${it.javaClass.simpleName}") }
            log("open fds = ${openFdCount()}")
        }
    }

    // ---------------------------------------------------------------- probe 6: scan benchmark

    suspend fun scanBenchmark(dirRel: String) {
        section("6. Scan benchmark: DocumentsContract vs DocumentFile")
        val s = saf
        if (s == null) {
            log("n/a on internal storage -- File.listFiles() is one syscall, not a Binder round-trip.")
            val t0 = System.nanoTime()
            val n = store.list(dirRel).size
            log("for reference: listing $n entries took ${(System.nanoTime() - t0) / 1_000_000} ms")
            return
        }
        try {
            val t0 = System.nanoTime()
            val bulk = s.listChildrenOfRel(dirRel)
            val bulkMs = (System.nanoTime() - t0) / 1_000_000
            log("bulk query     = ${bulk.size} entries in $bulkMs ms (name, size, mtime included)")

            currentCoroutineContext().ensureActive()
            val docId = s.resolveDocumentId(dirRel)
            if (docId == null) {
                log("could not resolve '$dirRel'; skipping comparison")
                return
            }
            val df = DocumentFile.fromTreeUri(context, s.uriFor(docId))
            if (df == null) {
                log("DocumentFile.fromTreeUri returned null; skipping comparison")
                return
            }
            val t1 = System.nanoTime()
            val listed = df.listFiles()
            val listMs = (System.nanoTime() - t1) / 1_000_000
            log("listFiles()    = ${listed.size} entries in $listMs ms (no metadata yet)")

            currentCoroutineContext().ensureActive()
            val t2 = System.nanoTime()
            var acc = 0L
            for (f in listed) {
                acc += f.length()
                f.name
            }
            val metaMs = (System.nanoTime() - t2) / 1_000_000
            log("+ name/length  = $metaMs ms more (a Binder round-trip per accessor per file)")
            log("  (summed length $acc, kept so the loop is not optimised away)")

            val total = listMs + metaMs
            val ratio = if (bulkMs > 0) total.toDouble() / bulkMs else Double.NaN
            log("DocumentFile is ${fmt(ratio)}x slower for equivalent information")
            log("VERDICT: informational -- production scanning uses the bulk query only.")
        } catch (t: Throwable) {
            log("benchmark failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- probe 7: unplug drill

    suspend fun unplugDrill(capMiB: Int = 4096) {
        section("7. Unplug drill")
        if (saf == null) {
            log("n/a on internal storage -- there is nothing to unplug.")
            log("Switch the backend to the USB drive to run this one.")
            return
        }
        log("PULL THE DRIVE while this runs. Writing up to $capMiB MiB.")
        log("What we need: a CATCHABLE exception, not process death.")
        val rel = joinRel(PROBE_DIR, "unplug-${System.currentTimeMillis()}.bin")
        var written = 0L
        var caught: Throwable? = null
        try {
            val block = ByteArray(BUF)
            Random(1L).nextBytes(block)
            store.openWrite(rel).use { pfd ->
                val out = FileOutputStream(pfd.fileDescriptor)
                val blocks = capMiB.toLong() * MIB / BUF
                var i = 0L
                while (i < blocks) {
                    currentCoroutineContext().ensureActive()
                    out.write(block)
                    written += BUF
                    if (written % (64 * MIB) == 0L) log("  ${written / MIB} MiB ...")
                    i++
                }
                out.flush()
            }
        } catch (t: Throwable) {
            caught = t
        }
        val ex = caught
        if (ex == null) {
            log("completed ${written / MIB} MiB uninterrupted -- rerun and unplug sooner")
            log("VERDICT: inconclusive")
        } else {
            log("caught after ${written / MIB} MiB: ${ex.javaClass.name}: ${ex.message}")
            log("VERDICT: PASS -- the failure is catchable, so imports can mark jobs BLOCKED")
            log("  and resume after a replug instead of burning retries or crashing.")
        }
        runCatching { store.delete(rel) }
            .onFailure { log("cleanup failed (expected if the drive is still out): ${it.javaClass.simpleName}") }
        log("open fds = ${openFdCount()}")
    }

    // ---------------------------------------------------------------- helpers

    private fun readExactly(ins: FileInputStream, len: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(minOf(len, BUF))
        var remaining = len
        while (remaining > 0) {
            val n = ins.read(buf, 0, minOf(remaining, buf.size))
            if (n < 0) break
            digest.update(buf, 0, n)
            remaining -= n
        }
        return digest.digest().toHex()
    }

    /** Stamps the block index so the payload is not one repeated buffer. */
    private fun stamp(block: ByteArray, i: Int) {
        block[0] = (i ushr 24).toByte()
        block[1] = (i ushr 16).toByte()
        block[2] = (i ushr 8).toByte()
        block[3] = i.toByte()
    }

    private fun section(title: String) {
        log("")
        log("=== $title ===")
    }

    private fun mbPerSecRaw(bytes: Long, ms: Long): Double =
        if (ms <= 0) Double.POSITIVE_INFINITY else bytes.toDouble() / 1_000_000.0 / (ms / 1000.0)

    private fun mbPerSec(bytes: Long, ms: Long): String = fmt(mbPerSecRaw(bytes, ms))

    private fun fmt(d: Double): String = String.format("%.1f", d)

    /**
     * Leak canary. Must return to baseline after streaming work; /health reports it from M2,
     * because one fd leaked per aborted video seek exhausts the process limit within an afternoon.
     */
    fun openFdCount(): Int = File("/proc/self/fd").list()?.size ?: -1
}

data class TreeSurvey(
    val files: Int,
    val dirs: Int,
    val largest: StoreEntry?,
    val largestParentRel: String?,
    val fattestDirRel: String?,
    val fattestDirCount: Int,
    val elapsedMs: Long,
)
